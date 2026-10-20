package com.akira.redtale.economy.client.ui.hub;

import com.akira.redtale.economy.net.PacketIds;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

/**
 * Hub 页面切换接收（U8b）。
 *
 * <p>已经在 Hub 里就<b>切页</b>（保留搜索词/滚动位置），否则新开一个 Hub。
 * 之前每次都用 {@code setScreen(new HubScreen())} 会把玩家正在输的搜索词清掉。
 */
@Environment(EnvType.CLIENT)
public final class HubClientNetworking {

	private HubClientNetworking() {
	}

	public static void register() {
		ClientPlayNetworking.registerGlobalReceiver(PacketIds.S2C_HUB_OPEN, (client, handler, buf, sender) -> {
			String page = buf.readString(24);
			client.execute(() -> {
				HubPage target = HubPage.byId(page);
				if (client.currentScreen instanceof HubScreen hub) {
					hub.setPage(target);
					return;
				}
				HubScreen.open(target);
			});
		});
	}
}
