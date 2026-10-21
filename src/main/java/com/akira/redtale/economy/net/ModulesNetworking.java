package com.akira.redtale.economy.net;

import com.akira.redtale.economy.RedtaleEconomy;
import com.akira.redtale.economy.module.Modules;
import com.akira.redtale.economy.perm.Permissions;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.network.ServerPlayerEntity;

/**
 * T51 模块总开关：服务端收发。
 *
 * <p>权限一律在服务端判 {@link Permissions#NODE_OWNER}（默认 4 = OP）：
 * <ul>
 *   <li>请求：非管理员拿到 {@code admin=false} + 空列表（能看见「无权限」，不是一直转圈）</li>
 *   <li>切换：非管理员<b>直接忽略并记一行</b>，不落盘、不改状态</li>
 * </ul>
 *
 * <p>切换成功后给<b>所有在线玩家</b>重发一次快照：侧栏隐藏/恢复是按客户端缓存算的，
 * 只回给操作者会让别人那边还留着已关闭的入口（点了进空页）。
 */
public final class ModulesNetworking {

	private static final int LOG_EVERY_MS = 30_000;
	private static long lastDenyLogAt;

	private ModulesNetworking() {
	}

	public static void register() {
		ServerPlayNetworking.registerGlobalReceiver(PacketIds.C2S_MODULES_REQ,
				(server, player, h, buf, sender) -> {
					try {
						ModulesProtocol.readQuery(buf);
					} catch (Exception e) {
						RedtaleEconomy.LOGGER.warn("[modules] 请求包解析失败（{}），已忽略", e.getMessage());
						return;
					}
					server.execute(() -> send(player));
				});
		ServerPlayNetworking.registerGlobalReceiver(PacketIds.C2S_MODULE_SET,
				(server, player, h, buf, sender) -> {
					ModulesProtocol.Set req;
					try {
						req = ModulesProtocol.readSet(buf);
					} catch (Exception e) {
						RedtaleEconomy.LOGGER.warn("[modules] 切换包解析失败（{}），已忽略", e.getMessage());
						return;
					}
					server.execute(() -> apply(server, player, req));
				});
	}

	private static void apply(net.minecraft.server.MinecraftServer server, ServerPlayerEntity player,
			ModulesProtocol.Set req) {
		if (!Permissions.has(player, Permissions.NODE_OWNER)) {
			long now = System.currentTimeMillis();
			if (now - lastDenyLogAt > LOG_EVERY_MS) {
				lastDenyLogAt = now;
				RedtaleEconomy.LOGGER.info("[modules] 非管理员尝试切换 {}，已拒绝：{}",
						req.id(), player.getGameProfile().getName());
			}
			player.sendMessage(net.minecraft.text.Text.literal("§c需要管理员权限"), true);
			return;
		}
		Modules.Result r = Modules.set(req.id(), req.on());
		player.sendMessage(net.minecraft.text.Text.literal((r.ok() ? "§a" : "§c") + r.note()), true);
		for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) send(p);
	}

	/** 下发全量快照（非管理员拿空表，控制台/侧栏据此显示无权限）。 */
	public static void send(ServerPlayerEntity player) {
		if (player == null) return;
		if (!ServerPlayNetworking.canSend(player, PacketIds.S2C_MODULES)) return;
		boolean admin = Permissions.has(player, Permissions.NODE_OWNER);
		ModulesProtocol.Snapshot snap = admin
				? new ModulesProtocol.Snapshot(true, Modules.views())
				: ModulesProtocol.Snapshot.empty();
		try {
			PacketByteBuf buf = new PacketByteBuf(io.netty.buffer.Unpooled.buffer());
			ModulesProtocol.write(buf, snap);
			ServerPlayNetworking.send(player, PacketIds.S2C_MODULES, buf);
		} catch (Exception e) {
			RedtaleEconomy.LOGGER.error("[modules] 下发模块状态失败：{}", e.toString());
		}
	}
}
