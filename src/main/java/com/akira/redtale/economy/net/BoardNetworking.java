package com.akira.redtale.economy.net;

import com.akira.redtale.economy.RedtaleEconomy;
import com.akira.redtale.economy.board.BoardStore;
import com.akira.redtale.economy.module.impl.BoardModule;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 委托板下发（T12）。软失败：发包异常只记日志，不影响命令本身。
 *
 * <p>口径与 {@code /board} 聊天列表一致：只发「还开着的」+「与我有关的」，
 * 已结束且与本人无关的单子不占带宽、也不进界面。
 */
public final class BoardNetworking {

	private BoardNetworking() {
	}

	public static void register() {
		ServerPlayNetworking.registerGlobalReceiver(PacketIds.C2S_BOARD_REQ,
				(server, player, h, buf, sender) -> server.execute(() -> send(player)));
	}

	public static BoardProtocol.Snapshot snapshot(UUID uuid) {
		String me = uuid == null ? "" : uuid.toString();
		List<BoardProtocol.Entry> out = new ArrayList<>();
		long now = System.currentTimeMillis();
		for (BoardStore.Entry e : BoardStore.all()) {
			boolean open = BoardStore.Status.OPEN.name().equals(e.status);
			boolean minePoster = !me.isEmpty() && me.equals(e.poster);
			boolean mineAccepter = !me.isEmpty() && me.equals(e.accepter);
			if (!open && !minePoster && !mineAccepter) continue;
			long remain = e.expiresAt <= 0 ? 0 : Math.max(0, (e.expiresAt - now) / 60_000L);
			out.add(new BoardProtocol.Entry(e.id, e.posterName, e.itemId, e.count, e.bounty,
					statusIndex(e.status), e.accepterName, (int) Math.min(Integer.MAX_VALUE, remain),
					minePoster, mineAccepter));
			if (out.size() >= 64) break;
		}
		return new BoardProtocol.Snapshot(out);
	}

	private static int statusIndex(String status) {
		if (status == null) return 0;
		for (int i = 0; i < BoardProtocol.STATUS.length; i++) {
			if (BoardProtocol.STATUS[i].equals(status)) return i;
		}
		return 0;
	}

	public static void send(ServerPlayerEntity player) {
		if (player == null) return;
		if (!BoardModule.enabled()) return;
		if (!ServerPlayNetworking.canSend(player, PacketIds.S2C_BOARD)) return;
		try {
			PacketByteBuf buf = new PacketByteBuf(io.netty.buffer.Unpooled.buffer());
			BoardProtocol.write(buf, snapshot(player.getUuid()));
			ServerPlayNetworking.send(player, PacketIds.S2C_BOARD, buf);
		} catch (Exception e) {
			RedtaleEconomy.LOGGER.error("[board] 下发委托板失败：{}", e.getMessage());
		}
	}
}
