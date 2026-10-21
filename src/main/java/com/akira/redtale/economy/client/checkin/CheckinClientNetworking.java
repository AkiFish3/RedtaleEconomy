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

	/** 最近一次快照，供 Hub 的签到页复用（U3）。 */
	public static volatile CheckinProtocol.Snapshot LAST = CheckinProtocol.Snapshot.empty();

	private CheckinClientNetworking() {
	}

	public static void register() {
		ClientPlayNetworking.registerGlobalReceiver(PacketIds.S2C_CHECKIN, (client, handler, buf, sender) -> {
			CheckinProtocol.Snapshot snap = CheckinProtocol.read(buf);
			LAST = snap;
			client.execute(() -> {
				// 诊断（2026-10-21 T32 轮）：u6-04 拍了 13 次都没开屏，服务端数据确认 checkin 已执行，
				// 说明卡在客户端这条分支上。这行日志下次跑夹具就能定位（info，不弹窗）。
				RedtaleEconomy.LOGGER.info("[checkin] snapshot received: useHub={} screen={}",
						com.akira.redtale.economy.client.ui.hub.ClientUiConfig.useHub,
						client.currentScreen == null ? "null"
								: client.currentScreen.getClass().getSimpleName());
				if (client.currentScreen instanceof com.akira.redtale.economy.client.ui.hub.HubScreen) {
					return; // Hub 已在显示，只更新数据
				}
				if (com.akira.redtale.economy.client.ui.hub.ClientUiConfig.useHub) {
					com.akira.redtale.economy.client.ui.hub.HubScreen.open(com.akira.redtale.economy.client.ui.hub.HubPage.CHECKIN);
				} else {
					client.setScreen(new CheckinScreen(snap));
				}
			});
		});
		RedtaleEconomy.LOGGER.info("[checkin] 签到面板接收器已注册");
	}
}
