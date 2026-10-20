package com.akira.redtale.economy.client.bounty;

import com.akira.redtale.economy.RedtaleEconomy;
import com.akira.redtale.economy.net.BountyProtocol;
import com.akira.redtale.economy.net.PacketIds;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

/** 悬赏面板接收器（U7）。 */
@Environment(EnvType.CLIENT)
public final class BountyClientNetworking {

	private BountyClientNetworking() {
	}

	public static void register() {
		ClientPlayNetworking.registerGlobalReceiver(PacketIds.S2C_BOUNTY, (client, handler, buf, sender) -> {
			BountyProtocol.Snapshot snap = BountyProtocol.read(buf);
			client.execute(() -> client.setScreen(new BountyScreen(snap)));
		});
		RedtaleEconomy.LOGGER.info("[bounty] 悬赏面板接收器已注册");
	}
}
