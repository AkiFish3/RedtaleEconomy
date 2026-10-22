package com.akira.redtale.economy.client.ui.hub;

/** T32 批次2：排行榜页点击（下拉/活跃开关/同步冷却）。 */
final class HubRankClicks {
	private HubRankClicks() {
	}

	static HubClicks.R handle(HubScreen host, double mouseX, double mouseY, int button) {
		if (button == 0 && host.page == HubPage.RANK) {
			int cx = host.frame.contentX();
			int cy = host.frame.contentY();
			int cw = host.frame.contentW();
			int dd = HubRankPage.hitDropdown(mouseX, mouseY, cx, cy, host.rankDropdown, host.panelY + host.panelH - HubFrame.BOTTOM_BAR);
			if (dd == -2) {
				host.rankDropdown = !host.rankDropdown;
				ClientUiConfig.click();
				return HubClicks.R.HANDLED;
			}
			if (dd >= 0) {
				host.rankBoard = dd;
				host.rankDropdown = false;
				ClientUiConfig.click();
				return HubClicks.R.HANDLED;
			}
			if (host.rankDropdown) {
				host.rankDropdown = false;
				return HubClicks.R.HANDLED;
			}
			HubRankPage.Action ra = HubRankPage.click(mouseX, mouseY, cx, cy, cw,
					host.panelH - HubFrame.TOP_BAR - HubFrame.BOTTOM_BAR - 16, host.rankBoard);
			if (ra == HubRankPage.Action.ACTIVE_TOGGLE) {
				host.rankActiveOnly = !host.rankActiveOnly;
				HubRankPage.resetScroll();   // 榜单长度会变，留着旧偏移会落到"空行"
				ClientUiConfig.click();
				// U9：开关要真的影响数据 —— 带参数重新请求，服务端挑另一份缓存
				com.akira.redtale.economy.client.rank.RankClientNetworking.request(host.rankActiveOnly);
				host.setStatus(host.rankActiveOnly ? "只显示活跃玩家" : "显示全部玩家（含长期未上线）");
				return HubClicks.R.HANDLED;
			}
			if (ra == HubRankPage.Action.SYNC) {
				int left = host.rankSyncCooldownLeft();
				if (left > 0) {
					// 冷却中只更新界面内提示，不重发请求（§43.6 防刷）
					host.setStatus("同步冷却中，还需 " + left + " 秒");
					return HubClicks.R.HANDLED;
				}
				host.lastRankSyncAt = System.currentTimeMillis();
				HubRankPage.resetScroll();
				com.akira.redtale.economy.client.rank.RankClientNetworking.request(host.rankActiveOnly);
				host.setStatus("正在同步榜单…");
				return HubClicks.R.HANDLED;
			}
		}
		return HubClicks.R.PASS;
	}

}
