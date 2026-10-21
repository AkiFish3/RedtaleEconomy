package com.akira.redtale.economy.net;

import com.akira.redtale.economy.RedtaleEconomy;
import com.akira.redtale.economy.perm.Permissions;
import com.akira.redtale.economy.storage.AdminStats;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.ArrayList;
import java.util.List;

/**
 * T28 管理员控制台：服务端聚合 + 下发。
 *
 * <p>★权限在服务端判★：请求包没有参数，非管理员拿到的是一份 {@code admin=false} 的空快照。
 * 客户端那侧的 {@code adminOnly} 只是「侧栏看不看得见」，真正的门在这里 ——
 * 玩家改客户端跳过侧栏过滤，也只能拉到一个空页面。
 *
 * <p>数据全来自只读聚合（{@link AdminStats}），本类<b>不落盘、不改状态</b>。
 */
public final class AdminNetworking {

	private static final int LOG_EVERY_MS = 30_000;
	private static long lastDenyLogAt;

	private AdminNetworking() {
	}

	public static void register() {
		ServerPlayNetworking.registerGlobalReceiver(PacketIds.C2S_ADMIN_REQ,
				(server, player, h, buf, sender) -> {
					try {
						AdminProtocol.readQuery(buf); // 只做校验
					} catch (Exception e) {
						// 跨版本混服的噪声包：记一行就好，不踢人
						RedtaleEconomy.LOGGER.warn("[admin] 请求包解析失败（{}），已忽略", e.getMessage());
						return;
					}
					server.execute(() -> send(player));
				});
	}

	public static void send(ServerPlayerEntity player) {
		if (player == null) return;
		if (!ServerPlayNetworking.canSend(player, PacketIds.S2C_ADMIN)) return;
		boolean admin = Permissions.has(player, Permissions.NODE_ADMIN);
		if (!admin) {
			// 被拒也要回包：客户端据此显示「需要管理员权限」而不是一直转圈
			long now = System.currentTimeMillis();
			if (now - lastDenyLogAt > LOG_EVERY_MS) {
				lastDenyLogAt = now;
				RedtaleEconomy.LOGGER.info("[admin] 非管理员请求控制台，已回空快照：{}",
						player.getGameProfile().getName());
			}
		}
		AdminProtocol.Snapshot snap = admin ? snapshot(player) : AdminProtocol.Snapshot.empty();
		try {
			PacketByteBuf buf = new PacketByteBuf(io.netty.buffer.Unpooled.buffer());
			AdminProtocol.write(buf, snap);
			ServerPlayNetworking.send(player, PacketIds.S2C_ADMIN, buf);
		} catch (Exception e) {
			RedtaleEconomy.LOGGER.error("[admin] 下发控制台概览失败：{}", e.toString());
		}
	}

	/** 组装快照（单测/命令入口也能直接调）。 */
	public static AdminProtocol.Snapshot snapshot(ServerPlayerEntity player) {
		List<AdminProtocol.Metric> metrics = new ArrayList<>();
		for (AdminStats.Item it : AdminStats.collect(player == null ? null : player.getServer())) {
			metrics.add(new AdminProtocol.Metric(it.label(), it.value(), it.hint()));
		}
		return new AdminProtocol.Snapshot(System.currentTimeMillis(), true, metrics);
	}
}
