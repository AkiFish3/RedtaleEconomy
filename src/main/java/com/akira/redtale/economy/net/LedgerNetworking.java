package com.akira.redtale.economy.net;

import com.akira.redtale.economy.RedtaleEconomy;
import com.akira.redtale.economy.config.StorageConfig;
import com.akira.redtale.economy.perm.Permissions;
import com.akira.redtale.economy.storage.LedgerQuery;
import com.akira.redtale.economy.util.DataRoot;
import com.akira.redtale.economy.util.TimeUtil;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.network.ServerPlayerEntity;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * T16 流水查看器：服务端查询 + 下发。
 *
 * <p>权限口径：{@link Permissions#NODE_ADMIN} 才允许 {@code all=true}（看所有人）；
 * 普通玩家无论请求里写什么，都被强制成「只看自己」—— 服务端判定，不信客户端。
 *
 * <p>名字解析走 {@code UserCache}（离线 uuid 查不到就退回 uuid 前 8 位），
 * 不引入玩家数据耦合 —— 流水文件里只有 uuid，没有名字。
 */
public final class LedgerNetworking {

	/** 一页条数（与协议上限一致）。 */
	private static final int PAGE_ROWS = 32;

	private LedgerNetworking() {
	}

	public static void register() {
		ServerPlayNetworking.registerGlobalReceiver(PacketIds.C2S_LEDGER_REQ,
				(server, player, h, buf, sender) -> {
					LedgerProtocol.Query q;
					try {
						q = LedgerProtocol.readQuery(buf);
					} catch (Exception e) {
						// 坏包只记一行，不踢人：跨版本混服时这就是个噪声包
						RedtaleEconomy.LOGGER.warn("[ledger] 请求包解析失败（{}），已忽略", e.getMessage());
						return;
					}
					server.execute(() -> send(player, q));
				});
	}

	/** 玩家默认视图（命令入口 / 页面首次打开）。 */
	public static void send(ServerPlayerEntity player) {
		send(player, LedgerProtocol.Query.defaults());
	}

	public static LedgerProtocol.Snapshot snapshot(ServerPlayerEntity player, LedgerProtocol.Query q) {
		if (player == null) return LedgerProtocol.Snapshot.empty();
		boolean admin = Permissions.has(player, Permissions.NODE_ADMIN);
		boolean all = q.all() && admin;
		int daysIdx = clamp(q.daysIdx(), 0, LedgerQuery.DAYS.length - 1);
		int dir = clamp(q.dir(), 0, 2);
		int typeIdx = clamp(q.typeIdx(), 0, LedgerQuery.TYPE_FILTERS.length - 1);
		Path root = DataRoot.path().resolve(StorageConfig.ledgerDir());
		UUID who = all ? null : player.getUuid();
		LedgerQuery.Result res = LedgerQuery.query(root, StorageConfig.granularity(),
				TimeUtil.businessDate(0), LedgerQuery.daysOf(daysIdx), dir, typeIdx, who, PAGE_ROWS);
		Map<String, String> names = new HashMap<>();
		List<LedgerProtocol.Row> rows = new ArrayList<>(res.rows().size());
		for (LedgerQuery.Row r : res.rows()) {
			String name = all ? names.computeIfAbsent(r.uuid(), u -> nameOf(player, u)) : player.getGameProfile().getName();
			rows.add(new LedgerProtocol.Row(r.ts(), name, r.type(), r.delta(), r.after(), r.ref()));
		}
		return new LedgerProtocol.Snapshot(daysIdx, dir, typeIdx, all, admin, res.total(), rows);
	}

	private static int clamp(int v, int lo, int hi) {
		return Math.max(lo, Math.min(hi, v));
	}

	private static String nameOf(ServerPlayerEntity viewer, String uuid) {
		if (uuid == null || uuid.isEmpty()) return "-";
		try {
			Optional<com.mojang.authlib.GameProfile> p = viewer.getServer() == null ? Optional.empty()
					: viewer.getServer().getUserCache().getByUuid(UUID.fromString(uuid));
			if (p.isPresent() && p.get().getName() != null) return p.get().getName();
		} catch (Exception ignored) {
			// 非法 uuid / 缓存未初始化 —— 退回短 uuid，不影响显示
		}
		return uuid.length() > 8 ? uuid.substring(0, 8) : uuid;
	}

	public static void send(ServerPlayerEntity player, LedgerProtocol.Query q) {
		if (player == null) return;
		if (!ServerPlayNetworking.canSend(player, PacketIds.S2C_LEDGER)) return;
		try {
			PacketByteBuf buf = new PacketByteBuf(io.netty.buffer.Unpooled.buffer());
			LedgerProtocol.write(buf, snapshot(player, q));
			ServerPlayNetworking.send(player, PacketIds.S2C_LEDGER, buf);
		} catch (Exception e) {
			RedtaleEconomy.LOGGER.error("[ledger] 下发流水失败：{}", e.getMessage());
		}
	}
}
