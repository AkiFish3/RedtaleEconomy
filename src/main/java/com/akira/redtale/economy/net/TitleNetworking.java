package com.akira.redtale.economy.net;

import com.akira.redtale.economy.module.impl.TitleModule;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.List;

/** 称号：下发给玩家个人（别人看不到，也不需要）。 */
public final class TitleNetworking {

	private TitleNetworking() {
	}

	public static void send(ServerPlayerEntity player) {
		if (player == null) return;
		List<String> owned = TitleModule.owned(player.getUuid());
		TitleModule.TitleData d = TitleModule.data(player.getUuid());
		PacketByteBuf buf = new PacketByteBuf(io.netty.buffer.Unpooled.buffer());
		TitleProtocol.write(buf, new TitleProtocol.Snapshot(
				TitleModule.enabled(), d.title, d.suffix, d.gradient, d.color1, d.color2, owned));
		ServerPlayNetworking.send(player, PacketIds.S2C_TITLES, buf);
	}

	public static void register() {
		ServerPlayNetworking.registerGlobalReceiver(PacketIds.C2S_TITLE_REQ,
				(server, player, h, buf, sender) -> server.execute(() -> send(player)));

		ServerPlayNetworking.registerGlobalReceiver(PacketIds.C2S_TITLE_SELECT,
				(server, player, h, buf, sender) -> {
					String t = buf.readString(128);
					server.execute(() -> {
						if (TitleModule.owned(player.getUuid()).contains(t)) {
							TitleModule.setTitle(player, t);
						}
						send(player);
					});
				});

		ServerPlayNetworking.registerGlobalReceiver(PacketIds.C2S_TITLE_REMOVE,
				(server, player, h, buf, sender) -> {
					String t = buf.readString(128);
					server.execute(() -> {
						TitleModule.revoke(player, t);
						send(player);
					});
				});

		ServerPlayNetworking.registerGlobalReceiver(PacketIds.C2S_TITLE_CLEAR,
				(server, player, h, buf, sender) -> server.execute(() -> {
					TitleModule.clearTitle(player);
					send(player);
				}));
	}
}
