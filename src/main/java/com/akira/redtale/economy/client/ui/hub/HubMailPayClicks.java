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
			HubMailPage.Result r = HubMailPage.click(mouseX, mouseY, cx, cy, cw, ch);
			var mc = MinecraftClient.getInstance();
			switch (r.action()) {
				case OPEN_MAIL -> {
					if (mc.player != null) mc.player.networkHandler.sendChatCommand("mail");
					return HubClicks.R.HANDLED;
				}
				case CLAIM -> {
					// T62：按下标领（服务端按 kind+index 定位队列条目，不按金额/名字匹配）
					var snap = MailClientNetworking.LAST;
					int idx = Math.max(0, Math.min(r.index() < 0 ? 0 : r.index(),
							Math.max(0, snap.entries().size() - 1)));
					if (snap.entries().isEmpty()) {
						host.setStatus("没有可领取的邮件");
						return HubClicks.R.HANDLED;
					}
					var e = snap.entries().get(idx);
					// 货币条目 index 固定 -1 ⇒ 用条目自带的 index 发包，不用列表下标
					MailClientNetworking.claim(e.kind(), e.index());
					host.setStatus(e.kind() == com.akira.redtale.economy.net.MailProtocol.KIND_MONEY
							? "正在领取货币…" : "正在取出物品…");
					return HubClicks.R.HANDLED;
				}
				case SELECT -> {
					return HubClicks.R.HANDLED;
				}
				default -> {
					return HubClicks.R.PASS;
				}
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
