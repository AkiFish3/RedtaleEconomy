package com.akira.redtale.economy.client.ui.hub;

/**
 * T32 批次2：Hub 点击路由（原 HubScreen.handleClick）。
 *
 * <p>顺序即优先级：确认条 → 搜索框 → 侧栏 → 各页 handler。**顺序不可调**：
 * 确认条画在搜索框之上，先判搜索框会导致点「确认」变成聚焦搜索框。
 */
final class HubClicks {
	private HubClicks() {
	}

	/** 页面 handler 的返回语义：HANDLED = 本页吃掉了这次点击；PASS = 继续往下判。 */
	enum R { HANDLED, PASS }

	static boolean handle(HubScreen host, double mouseX, double mouseY, int button) {
		// ★确认条在搜索框上方★：必须先判它，否则点"确认"会变成聚焦搜索框
		if (host.page == HubPage.STORAGE && HubStoragePage.confirmUpgrade) {
			int cx = host.frame.contentX();
			int cy = host.frame.contentY();
			int cw = host.frame.contentW();
			int ch = host.frame.contentH();
			var r0 = HubStoragePage.click(mouseX, mouseY, cx, cy, cw, ch, button, host.storageScroll);
			if (r0.hit() == HubStoragePage.Hit.CONFIRM_YES) {
				HubStoragePage.confirmUpgrade = false;
				com.akira.redtale.economy.client.warehouse.WarehouseClientNetworking.upgrade();
				host.setStatus("正在扩容…");
				return true;
			}
			if (r0.hit() == HubStoragePage.Hit.CONFIRM_NO) {
				HubStoragePage.confirmUpgrade = false;
				return true;
			}
		}
		if (host.inSearchBox(mouseX, mouseY)) {
			host.searchFocused = true;
			return true;
		}
		host.searchFocused = false;
		// 关闭框已在 mouseClicked 里判定（同一套 host.frame 坐标），这里原来还有一份 12px 的重复命中区，
		// 永远走不到，删掉免得以后改布局只改一处。
		if (button == 0 && mouseX >= host.panelX && mouseX < host.panelX + host.sidebarW()) {
			int idx = host.sidebarRowAt(mouseY);
			HubPage[] all = HubPage.visible();
			if (idx >= 0 && idx < all.length) {
				if (host.page != all[idx]) ClientUiConfig.click();
				host.searchFocused = false;
				if (host.page == HubPage.STORAGE && all[idx] != HubPage.STORAGE) {
					HubStoragePage.confirmUpgrade = false;
					com.akira.redtale.economy.client.warehouse.WarehouseClientNetworking.dropCursor();
				}
				host.page = all[idx];
				if (host.page == HubPage.SHOP) host.requestShopPage();
				if (host.page == HubPage.MARKET) com.akira.redtale.economy.client.market.MarketClientNetworking.request();
				if (host.page == HubPage.BOUNTY) HubRequests.requestBounty();
				if (host.page == HubPage.BOARD) {
					HubBoardPage.resetScroll();
					com.akira.redtale.economy.client.ui.hub.BoardClientNetworking.request();
					host.setStatus("正在读取委托板…");
				}
				if (host.page == HubPage.LEDGER) {
					HubLedgerPage.reset();
					com.akira.redtale.economy.client.ui.hub.LedgerClientNetworking.request();
					host.setStatus("正在读取流水…");
				}
				if (host.page == HubPage.PASS) HubRequests.requestPass();
				if (host.page == HubPage.ADMIN) {
					HubAdminPage.reset();
					AdminClientNetworking.request();
					host.setStatus("正在读取控制台数据…");
				}				if (host.page == HubPage.GUILD) HubRequests.requestGuild();
				if (host.page == HubPage.STORAGE) {
					com.akira.redtale.economy.client.warehouse.WarehouseClientNetworking.request();
			com.akira.redtale.economy.client.title.TitlesClientNetworking.request();
				}
				return true;
			}
		}
		// T28：控制台只有「刷新」一个可点区（其余是只读指标，点了应当落到 super）
		if (host.page == HubPage.ADMIN && button == 0
				&& HubAdminPage.click(mouseX, mouseY, host.frame.contentW())) {
			host.setStatus("正在刷新控制台…");
			return true;
		}
		R r;
		r = HubMarketClicks.handle(host, mouseX, mouseY, button);
		if (r == R.HANDLED) return true;
		r = HubMailPayClicks.handle(host, mouseX, mouseY, button);
		if (r == R.HANDLED) return true;
		r = HubStorageClicks.handle(host, mouseX, mouseY, button);
		if (r == R.HANDLED) return true;
		r = HubPassBountyClicks.handle(host, mouseX, mouseY, button);
		if (r == R.HANDLED) return true;
		r = HubRankClicks.handle(host, mouseX, mouseY, button);
		if (r == R.HANDLED) return true;
		r = HubShopClicks.handle(host, mouseX, mouseY, button);
		if (r == R.HANDLED) return true;
		r = HubPageClicks.checkin(host, mouseX, mouseY, button);
		if (r == R.HANDLED) return true;
		return host.fallbackClick(mouseX, mouseY, button);
	}
}
