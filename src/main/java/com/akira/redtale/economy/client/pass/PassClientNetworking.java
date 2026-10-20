package com.akira.redtale.economy.client.pass;

import com.akira.redtale.economy.net.PacketIds;
import com.akira.redtale.economy.net.PassProtocol;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

/** 战令快照接收（U4）。 */
@Environment(EnvType.CLIENT)
public final class PassClientNetworking {

	/** 最近一次快照。 */
	public static volatile PassProtocol.Snapshot LAST = PassProtocol.Snapshot.empty();

	private PassClientNetworking() {
	}

	public static void register() {
		ClientPlayNetworking.registerGlobalReceiver(PacketIds.S2C_PASS, (client, handler, buf, sender) -> {
			PassProtocol.Snapshot snap = PassProtocol.read(buf);
			LAST = snap;
			client.execute(() -> {
				if (client.currentScreen instanceof com.akira.redtale.economy.client.ui.hub.HubScreen) {
					return;
				}
				com.akira.redtale.economy.client.ui.hub.HubScreen.open(
						com.akira.redtale.economy.client.ui.hub.HubPage.PASS);
			});
		});
	}
}
