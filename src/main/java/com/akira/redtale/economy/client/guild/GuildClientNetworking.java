package com.akira.redtale.economy.client.guild;

import com.akira.redtale.economy.net.GuildProtocol;
import com.akira.redtale.economy.net.PacketIds;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

/** 协会快照接收。 */
@Environment(EnvType.CLIENT)
public final class GuildClientNetworking {

	public static volatile GuildProtocol.Snapshot LAST = GuildProtocol.Snapshot.empty();

	private GuildClientNetworking() {
	}

	public static void register() {
		ClientPlayNetworking.registerGlobalReceiver(PacketIds.S2C_GUILD, (client, handler, buf, sender) -> {
			GuildProtocol.Snapshot snap = GuildProtocol.read(buf);
			LAST = snap;
			client.execute(() -> {
				if (client.currentScreen instanceof com.akira.redtale.economy.client.ui.hub.HubScreen) {
					return;
				}
				com.akira.redtale.economy.client.ui.hub.HubScreen.open(
						com.akira.redtale.economy.client.ui.hub.HubPage.GUILD);
			});
		});
	}
}
