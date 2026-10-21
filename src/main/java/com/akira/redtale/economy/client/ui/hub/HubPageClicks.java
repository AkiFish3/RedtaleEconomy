package com.akira.redtale.economy.client.ui.hub;

import net.minecraft.client.MinecraftClient;

/** T32 批次2：签到页点击（上月/下月/签到/补签）。 */
final class HubPageClicks {
	private HubPageClicks() {
	}

	static HubClicks.R checkin(HubScreen host, double mouseX, double mouseY, int button) {
		if (button == 0 && host.page == HubPage.CHECKIN) {
			int cx = host.frame.contentX();
			int cy = host.frame.contentY();
			int cw = host.frame.contentW();
			int ch = host.frame.contentH();
			HubCheckinPage.Action a = HubCheckinPage.click(mouseX, mouseY, cx, cy, cw, ch,
					com.akira.redtale.economy.client.checkin.CheckinClientNetworking.LAST, host.monthOffset);
			var mc = MinecraftClient.getInstance();
			switch (a) {
				case PREV_MONTH -> host.monthOffset = Math.max(-12, host.monthOffset - 1);
				case NEXT_MONTH -> host.monthOffset = Math.min(0, host.monthOffset + 1);
				case CHECKIN -> {
					if (mc.player != null) mc.player.networkHandler.sendChatCommand("checkin");
					host.setStatus("正在签到…");
				}
				case MAKEUP -> {
					if (mc.player != null) mc.player.networkHandler.sendChatCommand("checkin make-up");
					host.setStatus("正在补签…");
				}
				default -> {
				}
			}
			if (a != HubCheckinPage.Action.NONE) return HubClicks.R.HANDLED;
		}
		return HubClicks.R.PASS;
	}

}
