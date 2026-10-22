package com.akira.redtale.economy.client.ui.hub;

import net.minecraft.client.MinecraftClient;

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
				// ★拉数据只有 HubRequests.pullData 一套★：侧栏这里原来又写了一条 if 链，
				// 与深链（setPage）覆盖的页面不同 ⇒ 称号/仓储/战令/悬赏/协会深链进来不拉数据；
				// 而且 TITLES 那行被粘进了 STORAGE 分支（点仓储多发一条、点称号不刷新）。
				if (host.page == HubPage.BOARD) {
					HubBoardPage.resetScroll();
					host.setStatus("正在读取委托板…");
				}
				if (host.page == HubPage.LEDGER) {
					HubLedgerPage.reset();
					host.setStatus("正在读取流水…");
				}
				if (host.page == HubPage.ADMIN) {
					HubAdminPage.reset();
					host.setStatus("正在读取控制台数据…");
				}
				HubRequests.pullData(host, host.page);
				return true;
			}
		}
		// T28：控制台只有「刷新」一个可点区（其余是只读指标，点了应当落到 super）
		if (host.page == HubPage.ADMIN && button == 0
				&& HubAdminPage.click(mouseX, mouseY, host.frame.contentW())) {
			host.setStatus("正在刷新控制台…");
			return true;
		}
		// T56：任务页的「领取 / 刷新」（命令由服务端校验，回显走 actionbar）
		if (host.page == HubPage.TASKS && button == 0) {
			int[] slot = {-1};
			HubTasksPage.Action a = HubTasksPage.click(mouseX, mouseY,
					host.frame.contentX(), host.frame.contentY(),
					host.frame.contentW(), host.frame.contentH(), slot);
			if ((a == HubTasksPage.Action.CLAIM || a == HubTasksPage.Action.SUBMIT) && slot[0] >= 0) {
				if (MinecraftClient.getInstance().player != null) {
					// 缴纳型走 submit（扣物品），事件型走 claim；二者校验都只在服务端
					String sub = a == HubTasksPage.Action.SUBMIT ? "submit" : "claim";
					MinecraftClient.getInstance().player.networkHandler
							.sendChatCommand("tasks " + sub + " " + slot[0]);
				}
				host.setStatus("正在处理 #" + slot[0] + "…");
				return true;
			}
			if (a == HubTasksPage.Action.REFRESH) {
				if (MinecraftClient.getInstance().player != null) {
					MinecraftClient.getInstance().player.networkHandler.sendChatCommand("tasks refresh");
				}
				host.setStatus("正在刷新任务…");
				return true;
			}
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
