package com.akira.redtale.economy.net;

import com.akira.redtale.economy.config.TitleConfig;
import com.akira.redtale.economy.module.impl.TitleModule;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.ArrayList;
import java.util.List;

/** 称号：下发给玩家个人（别人看不到，也不需要）。 */
public final class TitleNetworking {

	private TitleNetworking() {
	}

	public static void send(ServerPlayerEntity player) {
		if (player == null) return;
		List<String> owned = TitleModule.owned(player.getUuid());
		TitleModule.TitleData d = TitleModule.data(player.getUuid());
		// T66：强制称号只下发「文本」，客户端不认 id、也不改它
		List<String> forcedTexts = new ArrayList<>();
		for (String id : d.forced) {
			TitleConfig.Forced f = TitleConfig.forcedById(id);
			if (f != null && !f.text().isEmpty() && !forcedTexts.contains(f.text())) {
				forcedTexts.add(f.text());
			}
		}
		PacketByteBuf buf = new PacketByteBuf(io.netty.buffer.Unpooled.buffer());
		TitleProtocol.write(buf, new TitleProtocol.Snapshot(
				TitleModule.enabled(), d.title, d.suffix, d.gradient, d.color1, d.color2, owned,
				TitleModule.slotsOf(d), TitleModule.equipped(player.getUuid()), forcedTexts));
		ServerPlayNetworking.send(player, PacketIds.S2C_TITLES, buf);
	}

	public static void register() {
		ServerPlayNetworking.registerGlobalReceiver(PacketIds.C2S_TITLE_REQ,
				(server, player, h, buf, sender) -> server.execute(() -> send(player)));

		// T60：select 追加「目标槽位」；-1 = 追加到末尾（无槽可加则替换最后一槽）
		ServerPlayNetworking.registerGlobalReceiver(PacketIds.C2S_TITLE_SELECT,
				(server, player, h, buf, sender) -> {
					String t = buf.readString(128);
					int slot = buf.isReadable() ? buf.readVarInt() : -1;
					server.execute(() -> {
						if (TitleModule.owned(player.getUuid()).contains(t)) {
							TitleModule.equip(player, t, slot);
						}
						send(player);
					});
				});

		ServerPlayNetworking.registerGlobalReceiver(PacketIds.C2S_TITLE_UNEQUIP,
				(server, player, h, buf, sender) -> {
					int slot = buf.readVarInt();
					server.execute(() -> {
						TitleModule.unequipSlot(player, slot);
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
