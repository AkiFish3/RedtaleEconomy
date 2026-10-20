package com.akira.redtale.economy.net;

import com.akira.redtale.economy.RedtaleEconomy;
import com.akira.redtale.economy.config.ShopConfig;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 商店网络层（spec §7）。
 *
 * <p>服务端只做三件事：校验 → 切片 → 回一页。
 * 客户端发来的 page / count / itemId 全部当作不可信输入处理。
 * 买/卖的具体业务由 {@link TransactionHandler} 注入（R7），R4 阶段为空实现。
 */
public final class ShopNetworking {

	private static final Map<UUID, Long> LAST_REQUEST = new ConcurrentHashMap<>();
	private static volatile ShopSource source;
	private static volatile TransactionHandler handler;

	private ShopNetworking() {
	}

	public interface TransactionHandler {
		ShopProtocol.ActionResult buy(ServerPlayerEntity player, String shopId, String itemId, int count);

		ShopProtocol.ActionResult sell(ServerPlayerEntity player, String shopId, String itemId, int count);
	}

	public static void setSource(ShopSource s) {
		source = s;
	}

	public static void setHandler(TransactionHandler h) {
		handler = h;
	}

	// ---------------------------------------------------------------- 服务端

	public static void registerServer() {
		ServerPlayNetworking.registerGlobalReceiver(PacketIds.C2S_REQUEST_PAGE, (server, player, h, buf, sender) -> {
			String shopId = buf.readString(64);
			int page = buf.readVarInt();
			String query = buf.readString(128);
			// U11b：筛选条件追加在尾部；老客户端不发就按「不筛选」处理
			ShopFilter filter = buf.readableBytes() > 0 ? ShopFilter.read(buf) : ShopFilter.NONE;
			server.execute(() -> handleRequestPage(player, shopId, page, query, filter));
		});

		ServerPlayNetworking.registerGlobalReceiver(PacketIds.C2S_BUY, (server, player, h, buf, sender) -> {
			String shopId = buf.readString(64);
			String itemId = buf.readString(128);
			int count = buf.readVarInt();
			server.execute(() -> handleTrade(player, shopId, itemId, count, true));
		});

		ServerPlayNetworking.registerGlobalReceiver(PacketIds.C2S_SELL, (server, player, h, buf, sender) -> {
			String shopId = buf.readString(64);
			String itemId = buf.readString(128);
			int count = buf.readVarInt();
			server.execute(() -> handleTrade(player, shopId, itemId, count, false));
		});
	}

	private static void handleRequestPage(ServerPlayerEntity player, String shopId, int requestedPage,
			String query, ShopFilter filter) {
		long now = System.currentTimeMillis();
		int cd = ShopConfig.pageRequestCooldownMs();
		if (cd > 0) {
			Long last = LAST_REQUEST.get(player.getUuid());
			if (last != null && now - last < cd) {
				return; // 刷包直接丢弃，不回包（不回包也不会让客户端崩）
			}
		}
		LAST_REQUEST.put(player.getUuid(), now);

		String q = query == null ? "" : query.trim();
		int maxLen = ShopConfig.maxSearchLength();
		if (q.length() > maxLen) q = q.substring(0, maxLen);

		ShopSource src = source;
		if (src == null || !src.ready() || !src.has(shopId)) {
			sendPage(player, ShopProtocol.Page.empty(shopId, q));
			return;
		}

		int pageSize = ShopConfig.pageSize();
		ShopFilter f = filter == null ? ShopFilter.NONE : filter;
		int total = Math.max(0, src.count(shopId, q, f));
		int totalPages = ShopProtocol.totalPages(total, pageSize);
		int page = ShopProtocol.clampPage(requestedPage, total, pageSize);
		int offset = page * pageSize;
		var entries = src.slice(shopId, q, f, offset, pageSize);
		// facet 永远按「整个商店」下发，不受当前筛选影响 —— 否则筛完 mod 就看不到别的 mod 了
		sendPage(player, new ShopProtocol.Page(shopId, q, page, totalPages, total, entries,
				src.mods(shopId), src.categories(shopId)));
	}

	private static void handleTrade(ServerPlayerEntity player, String shopId, String itemId, int count, boolean buy) {
		int max = ShopConfig.maxCountPerTrade();
		if (count < 1 || count > max || itemId.isBlank() || itemId.length() > 128) {
			sendAction(player, new ShopProtocol.ActionResult(false, "shop.badRequest",
					com.akira.redtale.economy.economy.BalanceService.get(player.getUuid()), 0));
			return;
		}
		TransactionHandler h = handler;
		ShopProtocol.ActionResult r = h == null
				? new ShopProtocol.ActionResult(false, "shop.notReady",
						com.akira.redtale.economy.economy.BalanceService.get(player.getUuid()), 0)
				: (buy ? h.buy(player, shopId, itemId, count) : h.sell(player, shopId, itemId, count));
		sendAction(player, r);
	}

	// ---------------------------------------------------------------- 发包

	public static void sendPage(ServerPlayerEntity player, ShopProtocol.Page page) {
		PacketByteBuf buf = new PacketByteBuf(io.netty.buffer.Unpooled.buffer());
		try {
			ShopProtocol.writePage(buf, page);
			ServerPlayNetworking.send(player, PacketIds.S2C_PAGE, buf);
		} catch (Exception e) {
			RedtaleEconomy.LOGGER.error("[net] 下发商店页失败：{}", e.getMessage());
		}
	}

	public static void sendAction(ServerPlayerEntity player, ShopProtocol.ActionResult r) {
		PacketByteBuf buf = new PacketByteBuf(io.netty.buffer.Unpooled.buffer());
		try {
			ShopProtocol.writeAction(buf, r);
			ServerPlayNetworking.send(player, PacketIds.S2C_ACTION, buf);
		} catch (Exception e) {
			RedtaleEconomy.LOGGER.error("[net] 下发操作结果失败：{}", e.getMessage());
		}
	}

	/** 让客户端打开商店界面（服务端校验过命令/权限后才调用）。 */
	public static void sendOpen(ServerPlayerEntity player, String shopId) {
		PacketByteBuf buf = new PacketByteBuf(io.netty.buffer.Unpooled.buffer());
		try {
			buf.writeString(shopId == null ? "" : shopId, 64);
			ServerPlayNetworking.send(player, PacketIds.S2C_OPEN, buf);
		} catch (Exception e) {
			RedtaleEconomy.LOGGER.error("[net] 下发打开商店失败：{}", e.getMessage());
		}
	}

	/** 管理员：下发打开「可视化编辑」界面（R16a）。 */
	public static void sendOpenEdit(ServerPlayerEntity player, String shopId) {
		PacketByteBuf buf = new PacketByteBuf(io.netty.buffer.Unpooled.buffer());
		try {
			buf.writeString(shopId == null ? "" : shopId, 64);
			ServerPlayNetworking.send(player, PacketIds.S2C_OPEN_EDIT, buf);
		} catch (Exception e) {
			RedtaleEconomy.LOGGER.error("[net] 下发打开编辑界面失败：{}", e.getMessage());
		}
	}

	/** 玩家退出时清理限流记录。 */
	public static void forget(UUID uuid) {
		LAST_REQUEST.remove(uuid);
	}
}
