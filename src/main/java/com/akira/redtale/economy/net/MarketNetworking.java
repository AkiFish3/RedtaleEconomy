package com.akira.redtale.economy.net;

import com.akira.redtale.economy.RedtaleEconomy;
import com.akira.redtale.economy.config.MarketConfig;
import com.akira.redtale.economy.market.MarketRules;
import com.akira.redtale.economy.market.MarketService;
import com.akira.redtale.economy.market.MarketStore;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.item.ItemStack;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 玩家市场网络层（R40）。
 *
 * <p>只有四条客户端→服务端：请求列表 / 买 / 撤单 / 挂单。所有写操作都过 {@link MarketService}，
 * 网络层只负责解包 + 限流 + 回包，不碰余额与物品。
 */
public final class MarketNetworking {

	/** 列表请求限流（同一个人 300ms 内重复请求直接丢弃，避免拖滚动条刷包）。 */
	private static final Map<UUID, Long> LAST_REQUEST = new ConcurrentHashMap<>();
	private static final long REQUEST_COOLDOWN_MS = 300L;

	private MarketNetworking() {
	}

	public static void register() {
		ServerPlayNetworking.registerGlobalReceiver(PacketIds.C2S_MARKET_REQ, (server, player, h, buf, sender) -> {
			int page = buf.readVarInt();
			boolean mineOnly = buf.readBoolean();
			String query = buf.readString(64);
			server.execute(() -> handleRequest(player, page, mineOnly, query));
		});

		ServerPlayNetworking.registerGlobalReceiver(PacketIds.C2S_MARKET_BUY, (server, player, h, buf, sender) -> {
			String id = buf.readString(32);
			server.execute(() -> {
				MarketService.Result r = MarketService.buy(player, id);
				pushAction(player, r);
				if (r.ok()) send(player, 0, false, "");
			});
		});

		ServerPlayNetworking.registerGlobalReceiver(PacketIds.C2S_MARKET_CANCEL, (server, player, h, buf, sender) -> {
			String id = buf.readString(32);
			server.execute(() -> {
				MarketService.Result r = MarketService.cancel(player, id);
				pushAction(player, r);
				if (r.ok()) send(player, 0, false, "");
			});
		});

		ServerPlayNetworking.registerGlobalReceiver(PacketIds.C2S_MARKET_SELL, (server, player, h, buf, sender) -> {
			long price = buf.readLong();
			server.execute(() -> {
				MarketService.Result r = MarketService.list(player, price);
				pushAction(player, r);
				if (r.ok()) send(player, 0, true, "");
			});
		});
	}

	private static void handleRequest(ServerPlayerEntity player, int page, boolean mineOnly, String query) {
		long now = System.currentTimeMillis();
		Long last = LAST_REQUEST.get(player.getUuid());
		if (last != null && now - last < REQUEST_COOLDOWN_MS && !mineOnly) {
			return;
		}
		LAST_REQUEST.put(player.getUuid(), now);
		send(player, page, mineOnly, query);
	}

	/** 生成并下发一页（服务端权威切片）。 */
	public static void send(ServerPlayerEntity player, int page, boolean mineOnly, String query) {
		if (player == null) return;
		try {
			MarketService.Filter filter = mineOnly ? MarketService.Filter.MINE : MarketService.Filter.ALL;
			List<MarketStore.Entry> all = MarketService.query(player, filter, query);
			int size = Math.max(1, MarketConfig.pageSize());
			int pages = MarketRules.totalPages(all.size(), size);
			int p = MarketRules.clampPage(page, all.size(), size);

			List<MarketProtocol.Offer> out = new ArrayList<>();
			long now = System.currentTimeMillis();
			String me = player.getUuidAsString();
			for (int i = p * size; i < Math.min(all.size(), (p + 1) * size); i++) {
				MarketStore.Entry e = all.get(i);
				out.add(new MarketProtocol.Offer(e.id, e.escrow, e.itemId, e.count, e.price, e.sellerName,
						me.equals(e.seller), MarketRules.remaining(e.expireAt, now)));
			}
			MarketProtocol.Snapshot snap = new MarketProtocol.Snapshot(p, pages, all.size(), mineOnly,
					query == null ? "" : query, out,
					MarketStore.openCountOf(player.getUuid()), MarketConfig.maxOpenPerPlayer(),
					MarketConfig.commissionPercent());
			PacketByteBuf buf = new PacketByteBuf(io.netty.buffer.Unpooled.buffer());
			MarketProtocol.writeSnapshot(buf, snap);
			ServerPlayNetworking.send(player, PacketIds.S2C_MARKET, buf);
		} catch (Exception e) {
			RedtaleEconomy.LOGGER.error("[market] 下发市场列表失败：{}", e.getMessage());
		}
	}

	/** 操作结果：成功走 actionbar（Hub 内不刷聊天），失败也走 actionbar + 带 code。 */
	private static void pushAction(ServerPlayerEntity player, MarketService.Result r) {
		try {
			PacketByteBuf buf = new PacketByteBuf(io.netty.buffer.Unpooled.buffer());
			MarketProtocol.writeAction(buf, new MarketProtocol.ActionResult(r.ok(), r.code().key,
					r.message(), 0));
			ServerPlayNetworking.send(player, PacketIds.S2C_MARKET_ACTION, buf);
			player.sendMessage(net.minecraft.text.Text.literal(r.message()), true);
		} catch (Exception e) {
			RedtaleEconomy.LOGGER.error("[market] 回传操作结果失败：{}", e.getMessage());
		}
	}

	/** 供命令层复用：按玩家当前物品给一条价格提示（不写盘）。 */
	public static long defaultPrice(ItemStack stack) {
		if (stack == null || stack.isEmpty()) return MarketConfig.minPrice();
		return Math.max(MarketConfig.minPrice(), Math.min(MarketConfig.maxPrice(), stack.getCount() * 10L));
	}
}
