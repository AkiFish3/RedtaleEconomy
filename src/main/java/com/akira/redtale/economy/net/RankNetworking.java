package com.akira.redtale.economy.net;

import com.akira.redtale.economy.rank.RankService;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.List;

/**
 * 排行榜下发（U9b）。只发前 10 + 自己那一行，绝不发整榜。
 *
 * <p>数据来自 {@link RankService} 的 60 秒缓存，请求路径里<b>不做排序</b>。
 */
public final class RankNetworking {

	private RankNetworking() {
	}

	public static void send(ServerPlayerEntity player) {
		if (player == null) return;
		if (!ServerPlayNetworking.canSend(player, PacketIds.S2C_RANK)) return;
		RankProtocol.Snapshot snap = snapshotFor(player);
		net.minecraft.network.PacketByteBuf buf = new net.minecraft.network.PacketByteBuf(
				io.netty.buffer.Unpooled.buffer());
		RankProtocol.write(buf, snap);
		ServerPlayNetworking.send(player, PacketIds.S2C_RANK, buf);
	}

	public static RankProtocol.Snapshot snapshotFor(ServerPlayerEntity player) {
		List<RankProtocol.Row> rows = RankService.top(10).stream()
				.map(r -> new RankProtocol.Row(r.rank(), r.name(), r.balance()))
				.toList();
		RankService.Row self = RankService.self(player.getUuid(), player.getGameProfile().getName());
		return new RankProtocol.Snapshot(0, rows,
				new RankProtocol.Row(self.rank(), self.name(), self.balance()),
				RankService.total(), RankService.computedAt());
	}
}
