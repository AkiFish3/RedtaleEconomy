package com.akira.redtale.economy.client.ui.hub;

import net.minecraft.client.MinecraftClient;

/** T32 批次2：通行证/委托板/流水/委托/设置页点击。 */
final class HubPassBountyClicks {
	private HubPassBountyClicks() {
	}

	static HubClicks.R handle(HubScreen host, double mouseX, double mouseY, int button) {
		if (button == 0 && host.page == HubPage.PASS) {
			int cx = host.frame.contentX();
			int cy = host.frame.contentY();
			int cw = host.frame.contentW();
			int ch = host.frame.contentH();
			HubPassPage.Claim c = HubPassPage.click(mouseX, mouseY, cx, cy, cw, ch, host.passScroll);
			if (c != null) {
				var mc = MinecraftClient.getInstance();
				String cmd = "premium".equals(c.track())
						? "pass claim " + c.level() + " premium"
						: "pass claim " + c.level();
				if (mc.player != null) mc.player.networkHandler.sendChatCommand(cmd);
				host.setStatus("正在领取 Lv" + c.level() + " · " + ("premium".equals(c.track()) ? "付费" : "免费"));
				return HubClicks.R.HANDLED;
			}
		}
		if (button == 0 && host.page == HubPage.BOUNTY) {
			int cx = host.frame.contentX();
			int cy = host.frame.contentY();
			int cw = host.frame.contentW();
			int ch = host.frame.contentH();
			// 用几何判定，避免为了命中再画一次
			int cardH = 44;
			int idx = (int) ((mouseY - (cy + 16)) / (cardH + 4));
			var list = com.akira.redtale.economy.client.bounty.BountyClientNetworking.LAST.entries();
			if (idx >= 0 && idx < list.size()) {
				var e = list.get(idx);
				if (!e.claimed() && e.done()) {
					var mc = MinecraftClient.getInstance();
					if (mc.player != null) {
						mc.player.networkHandler.sendChatCommand("bounty claim " + e.index());
						host.setStatus("正在领取：" + e.id());
					}
				}
				return HubClicks.R.HANDLED;
			}
		}
		if (button == 0 && host.page == HubPage.LEDGER) {
			int cx = host.frame.contentX();
			int cy = host.frame.contentY();
			int cw = host.frame.contentW();
			int ch = host.frame.contentH();
			if (HubLedgerPage.click(host.text(), mouseX, mouseY, cx, cy, cw, ch)) {
				com.akira.redtale.economy.client.ui.hub.LedgerClientNetworking.request();
				host.setStatus("正在按新条件筛选…");
				return HubClicks.R.HANDLED;
			}
			return HubClicks.R.HANDLED; // 只读页：点了空白也别穿透到底下的东西
		}
		if (button == 0 && host.page == HubPage.BOARD) {
			int cx = host.frame.contentX();
			int cy = host.frame.contentY();
			int cw = host.frame.contentW();
			int ch = host.frame.contentH();
			int[] idx = {-1};
			HubBoardPage.Action a = HubBoardPage.click(host.text(), mouseX, mouseY, cx, cy, cw, ch, idx);
			var list = com.akira.redtale.economy.client.ui.hub.BoardClientNetworking.LAST.entries();
			var mc = MinecraftClient.getInstance();
			if (a == HubBoardPage.Action.REFRESH) {
				com.akira.redtale.economy.client.ui.hub.BoardClientNetworking.request();
				host.setStatus("正在刷新委托板…");
				return HubClicks.R.HANDLED;
			}
			if (a != HubBoardPage.Action.NONE && idx[0] >= 0 && idx[0] < list.size() && mc.player != null) {
				String id = list.get(idx[0]).id();
				String cmd = switch (a) {
					case ACCEPT -> "board accept " + id;
					case DELIVER -> "board deliver " + id;
					case CANCEL -> "board cancel " + id;
					default -> "";
				};
				if (!cmd.isEmpty()) {
					mc.player.networkHandler.sendChatCommand(cmd);
					host.setStatus(switch (a) {
						case ACCEPT -> "正在接单：" + id;
						case DELIVER -> "正在交付：" + id;
						default -> "正在撤销：" + id;
					});
					// 动作回包后由服务端快照覆盖列表；这里不本地改状态，避免与服务端不一致
					return HubClicks.R.HANDLED;
				}
			}
			if (idx[0] >= 0) return HubClicks.R.HANDLED;
		}
		if (button == 0 && host.page == HubPage.SETTINGS) {
			int cx = host.frame.contentX();
			int cy = host.frame.contentY();
			int cw = host.frame.contentW();
			int ch = host.frame.contentH();
			HubSettingsPage.Row row = HubSettingsPage.click(mouseX, mouseY, cx, cy, cw, ch);
			if (row != null) {
				ClientUiConfig.click();
				host.setStatus(HubSettingsPage.toggle(row));
				return HubClicks.R.HANDLED;
			}
		}
		return HubClicks.R.PASS;
	}

}
