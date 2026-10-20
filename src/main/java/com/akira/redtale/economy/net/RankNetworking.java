package com.akira.redtale.economy.net;

import com.akira.redtale.economy.rank.RankService;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.List;

/**
 * 排行榜下发（U9b / U9 收尾）。只发前 10 + 自己那一行，绝不发整榜。
 *
 * <p>数据来自 {@link RankService} 的 60 秒缓存，请求路径里<b>不做排序</b>。
 *
 * <p>U9 收尾：「只看活跃」不再是画着好看的死开关 —— 客户端把它随 {@code C2S_RANK_REQ}
 * 发上来，服务端在两份现成缓存（活跃 / 全量）里挑一份，不重算。
 */
public final class RankNetworking {

	private RankNetworking() {
	}

	public static void register() {
		ServerPlayNetworking.registerGlobalReceiver(PacketIds.C2S_RANK_REQ,
				(server, player, h, buf, sender) -> {
					boolean activeOnly = RankProtocol.readRequest(buf).activeOnly();
					server.execute(() -> send(player, activeOnly));
				});
	}

	public static void send(ServerPlayerEntity player) {
		send(player, true);
	}

	public static void send(ServerPlayerEntity player, boolean activeOnly) {
		if (player == null) return;
		if (!ServerPlayNetworking.canSend(player, PacketIds.S2C_RANK)) return;
		RankProtocol.Snapshot snap = snapshotFor(player, activeOnly);
		net.minecraft.network.PacketByteBuf buf = new net.minecraft.network.PacketByteBuf(
				io.netty.buffer.Unpooled.buffer());
		RankProtocol.write(buf, snap);
		ServerPlayNetworking.send(player, PacketIds.S2C_RANK, buf);
	}

	public static RankProtocol.Snapshot snapshotFor(ServerPlayerEntity player) {
		return snapshotFor(player, true);
	}

	public static RankProtocol.Snapshot snapshotFor(ServerPlayerEntity player, boolean activeOnly) {
		List<RankProtocol.Row> rows = RankService.top(10, activeOnly).stream()
				.map(r -> new RankProtocol.Row(r.rank(), r.name(), r.balance()))
				.toList();
		RankService.Row self = RankService.self(player.getUuid(), player.getGameProfile().getName(),
				activeOnly);
		return new RankProtocol.Snapshot(0, rows,
				new RankProtocol.Row(self.rank(), self.name(), self.balance()),
				RankService.total(activeOnly), RankService.computedAt(), activeOnly);
	}
}
