package com.akira.redtale.economy.client.ui.hub;

import net.minecraft.client.MinecraftClient;

/** T32 批次2：邮件/转账/称号页点击。 */
final class HubMailPayClicks {
	private HubMailPayClicks() {
	}

	static HubClicks.R handle(HubScreen host, double mouseX, double mouseY, int button) {
		if (button == 0 && host.page == HubPage.MAIL) {
			int cx = host.frame.contentX();
			int cy = host.frame.contentY();
			int cw = host.frame.contentW();
			int ch = host.frame.contentH();
			HubMailPage.Action a = HubMailPage.click(mouseX, mouseY, cx, cy, cw, ch);
			var mc = MinecraftClient.getInstance();
			if (a == HubMailPage.Action.OPEN_MAIL) {
				if (mc.player != null) mc.player.networkHandler.sendChatCommand("mail");
				return HubClicks.R.HANDLED;
			}
			if (a == HubMailPage.Action.CLAIM) {
				if (mc.player != null) mc.player.networkHandler.sendChatCommand("mail claim");
				host.setStatus("正在领取邮件附件…");
				return HubClicks.R.HANDLED;
			}
		}
		if ((button == 0 || button == 1) && host.page == HubPage.PAY) {
			int cx = host.frame.contentX();
			int cy = host.frame.contentY();
			int cw = host.frame.contentW();
			int ch = host.frame.contentH();
			var r = HubPayPage.click(mouseX, mouseY, cx, cy, cw, ch);
			switch (r.hit()) {
				case FIELD_NAME -> host.payFocus = 1;
				case FIELD_AMOUNT -> host.payFocus = 2;
				case CLEAR -> {
					host.payName.setLength(0);
					host.payAmount.setLength(0);
					host.payFocus = 0;
				}
				case SEND -> {
					long amt;
					try {
						amt = Long.parseLong(host.payAmount.toString().trim());
					} catch (Exception e) {
						amt = -1;
					}
					if (host.payName.length() == 0 || amt <= 0) {
						host.setStatus("请填写玩家名和金额");
					} else {
						com.akira.redtale.economy.client.pay.PayClientNetworking.pay(
								host.payName.toString().trim(), amt);
						host.setStatus("已提交转账…");
					}
				}
				default -> host.payFocus = 0;
			}
			return HubClicks.R.HANDLED;
		}
		if ((button == 0 || button == 1) && host.page == HubPage.TITLES) {
			int cx = host.frame.contentX();
			int cy = host.frame.contentY();
			int cw = host.frame.contentW();
			int ch = host.frame.contentH();
			var r = HubTitlesPage.click(mouseX, mouseY, cx, cy, cw, ch, button);
			var owned = com.akira.redtale.economy.client.title.TitlesClientNetworking.LAST.owned();
			if (r.index() >= 0 && r.index() < owned.size()) {
				String t = owned.get(r.index());
				if (r.hit() == HubTitlesPage.Hit.REMOVE) {
					com.akira.redtale.economy.client.title.TitlesClientNetworking.remove(t);
				} else if (r.hit() == HubTitlesPage.Hit.SELECT) {
					com.akira.redtale.economy.client.title.TitlesClientNetworking.select(t);
				}
			}
			return HubClicks.R.HANDLED;
		}
		return HubClicks.R.PASS;
	}

}
