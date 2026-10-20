package com.akira.redtale.economy.net;

import com.akira.redtale.economy.economy.BalanceService;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.network.ServerPlayerEntity;

/** 余额下发。 */
public final class BalanceNetworking {

	private BalanceNetworking() {
	}

	public static void register() {
		net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.registerGlobalReceiver(
				PacketIds.C2S_BALANCE_REQ, (server, player, h, buf, sender) ->
						server.execute(() -> send(player)));
	}

	public static void send(ServerPlayerEntity player) {
		if (player == null) return;
		PacketByteBuf buf = new PacketByteBuf(io.netty.buffer.Unpooled.buffer());
		BalanceProtocol.write(buf, BalanceService.get(player.getUuid()));
		ServerPlayNetworking.send(player, PacketIds.S2C_BALANCE, buf);
	}
}
