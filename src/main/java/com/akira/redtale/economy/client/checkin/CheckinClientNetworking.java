package com.akira.redtale.economy.client.checkin;

import com.akira.redtale.economy.RedtaleEconomy;
import com.akira.redtale.economy.net.CheckinProtocol;
import com.akira.redtale.economy.net.PacketIds;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

/** 签到面板的客户端收包（U3）。 */
@Environment(EnvType.CLIENT)
public final class CheckinClientNetworking {

	private CheckinClientNetworking() {
	}

	public static void register() {
		ClientPlayNetworking.registerGlobalReceiver(PacketIds.S2C_CHECKIN, (client, handler, buf, sender) -> {
			CheckinProtocol.Snapshot snap = CheckinProtocol.read(buf);
			client.execute(() -> client.setScreen(new CheckinScreen(snap)));
		});
		RedtaleEconomy.LOGGER.info("[checkin] 签到面板接收器已注册");
	}
}
