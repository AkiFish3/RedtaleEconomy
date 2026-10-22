package com.akira.redtale.economy.client.ui.hub;

/**
 * T32 批次3：HubScreen 的键鼠入口（原 charTyped / keyPressed / mouseDragged /
 * mouseReleased / mouseScrolled，共 198 行）。
 *
 * <p>返回 {@code null} 表示「不认领」⇒ 调用方转 {@code super.*}，与拆 handleClick 时
 * 的三态语义一致（硬坑：统一 return true 会把「点空白」变成吃掉点击）。
 */
final class HubInput {
	private HubInput() {
	}

	/** 返回 null = 交给 super.charTyped。 */
	static Boolean charTyped(HubScreen host, char chr, int modifiers) {
		if (host.page == HubPage.PAY && host.payFocus > 0 && !Character.isISOControl(chr)) {
			if (host.payFocus == 1 && host.payName.length() < 24
					&& (Character.isLetterOrDigit(chr) || chr == '_')) {
				host.payName.append(chr);
			} else if (host.payFocus == 2 && Character.isDigit(chr) && host.payAmount.length() < 12) {
				host.payAmount.append(chr);
			}
			return true;
		}
		// 硬坑 7：搜索框没焦点就不收字符（否则 Q/数字键被吞）
		if (host.page == HubPage.STORAGE && host.searchFocused && !Character.isISOControl(chr)) {
			host.storageQuery.append(chr);
			return true;
		}
		if (host.page == HubPage.SHOP && host.searchFocused && !Character.isISOControl(chr)) {
			host.shopQuery.append(chr);
			return true;
		}
		return null;
	}

	/** 返回 null = 交给 super.keyPressed。 */
	static Boolean keyPressed(HubScreen host, int keyCode, int scanCode, int modifiers) {
		if (keyCode == 256 && host.searchFocused) { // 聚焦时 ESC 先失焦
			host.searchFocused = false;
			return true;
		}
		if (keyCode == 256) { // ESC 再清搜索，最后关界面
			if (host.page == HubPage.SHOP && host.shopQuery.length() > 0) {
				host.shopQuery.setLength(0);
				host.requestShopPage();
				return true;
			}
			if (host.page == HubPage.STORAGE && host.storageQuery.length() > 0) {
				host.storageQuery.setLength(0);
				return true;
			}
		}
		if (host.page == HubPage.PAY && keyCode == 259) { // backspace
			if (host.payFocus == 1 && host.payName.length() > 0) {
				host.payName.setLength(host.payName.length() - 1);
			}
			if (host.payFocus == 2 && host.payAmount.length() > 0) {
				host.payAmount.setLength(host.payAmount.length() - 1);
			}
			return true;
		}
		if (!host.searchFocused && host.page == HubPage.STORAGE && keyCode == 81) { // Q
			com.akira.redtale.economy.client.warehouse.WarehouseClientNetworking.dropCursor();
			return true;
		}
		if (!host.searchFocused && host.page == HubPage.STORAGE && keyCode >= 49 && keyCode <= 57) {
			var held = com.akira.redtale.economy.client.warehouse.WarehouseClientNetworking.LAST.cursor();
			if (held != null && !held.isEmpty()) {
				com.akira.redtale.economy.client.warehouse.WarehouseClientNetworking.hotbar(keyCode - 49);
				return true;
			}
		}
		if (host.searchFocused && host.page == HubPage.STORAGE && keyCode == 259
				&& host.storageQuery.length() > 0) {
			host.storageQuery.setLength(host.storageQuery.length() - 1);
			return true;
		}
		if (host.page == HubPage.SHOP && host.searchFocused) {
			if (keyCode == 259 && host.shopQuery.length() > 0) { // backspace
				host.shopQuery.setLength(host.shopQuery.length() - 1);
				return true;
			}
			if (keyCode == 257 || keyCode == 335) { // enter
				host.requestShopPage();
				host.shopSelected = -1;
				return true;
			}
		}
		return null;
	}

	/** 返回 null = 交给 super.mouseDragged。 */
	static Boolean mouseDragged(HubScreen host, double mouseX, double mouseY, int button) {
		// T61：战令滚动条拖拽（优先于仓库拖拽，两者不会同时成立）
		if (host.passBarDrag && host.page == HubPage.PASS) {
			host.passScroll = HubPassPage.scrollForDrag(mouseX,
					host.frame.contentX(), host.frame.contentW());
			return true;
		}
		if (host.page != HubPage.STORAGE || !host.dragPlacing) return null;
		var r = HubStoragePage.click(mouseX, mouseY, host.frame.contentX(), host.frame.contentY(),
				host.frame.contentW(), host.frame.contentH(),
				host.dragButton < 0 ? 0 : host.dragButton, host.storageScroll);
		if (r.index() < 0 || r.index() == host.lastDragSlot) return true;
		boolean shift = host.shiftDown();
		var wnet = com.akira.redtale.economy.client.warehouse.WarehouseClientNetworking.class;
		if (r.hit() == HubStoragePage.Hit.STORAGE) {
			if (shift) {
				com.akira.redtale.economy.client.warehouse.WarehouseClientNetworking.localQuickOut(r.index());
				com.akira.redtale.economy.client.warehouse.WarehouseClientNetworking.quick(r.index());
			} else {
				var cur = com.akira.redtale.economy.client.warehouse.WarehouseClientNetworking.CURSOR;
				if (cur == null || cur.isEmpty()) return true;
				com.akira.redtale.economy.client.warehouse.WarehouseClientNetworking.localPlace(r.index(), true);
				com.akira.redtale.economy.client.warehouse.WarehouseClientNetworking.place(r.index(), true);
			}
			host.lastDragSlot = r.index();
			return true;
		}
		if (r.hit() == HubStoragePage.Hit.INV) {
			int real = HubStoragePage.displayToSlot(r.index());
			if (shift) {
				com.akira.redtale.economy.client.warehouse.WarehouseClientNetworking.localQuickIn(real);
				com.akira.redtale.economy.client.warehouse.WarehouseClientNetworking.quickInv(real);
			} else {
				var cur = com.akira.redtale.economy.client.warehouse.WarehouseClientNetworking.CURSOR;
				if (cur == null || cur.isEmpty()) return true;
				com.akira.redtale.economy.client.warehouse.WarehouseClientNetworking.localPlaceToInv(real, true);
				com.akira.redtale.economy.client.warehouse.WarehouseClientNetworking.placeToInv(real, true);
			}
			host.lastDragSlot = 1000 + r.index();
			return true;
		}
		return null;
	}

	/** 松手：★一次性把经过的格子交给服务端平分★，然后清空拖拽态。 */
	static void mouseReleased(HubScreen host) {
		host.passBarDrag = false;
		var wnet = com.akira.redtale.economy.client.warehouse.WarehouseClientNetworking.class;
		if (host.dragActive && !host.pendingInv && !host.dragSlots.isEmpty()) {
			int[] arr = new int[host.dragSlots.size()];
			for (int i = 0; i < arr.length; i++) arr[i] = host.dragSlots.get(i);
			com.akira.redtale.economy.client.warehouse.WarehouseClientNetworking.spread(arr, host.pendingAll);
			host.dragSlots.clear();
			host.pendingIdx = -1;
		}
		// 持物按下后没有拖到别的格子 -> 视为普通点击，整叠/单个放下
		if (host.dragActive && host.pendingIdx >= 0 && host.lastDragSlot == host.pressKey) {
			if (host.pendingInv) {
				com.akira.redtale.economy.client.warehouse.WarehouseClientNetworking.localPlaceToInv(
						host.pendingIdx, !host.pendingAll);
				com.akira.redtale.economy.client.warehouse.WarehouseClientNetworking.placeToInv(
						host.pendingIdx, !host.pendingAll);
			} else {
				com.akira.redtale.economy.client.warehouse.WarehouseClientNetworking.localPlace(
						host.pendingIdx, !host.pendingAll);
				com.akira.redtale.economy.client.warehouse.WarehouseClientNetworking.place(
						host.pendingIdx, !host.pendingAll);
			}
		}
		host.pendingIdx = -1;
		host.dragPlacing = false;
		host.dragActive = false;
		host.dragButton = -1;
		host.lastDragSlot = -1;
	}

	/** 返回 null = 交给 super.mouseScrolled（内容区各页滚动，侧栏另算）。 */
	static Boolean mouseScrolled(HubScreen host, double mouseX, double amount) {
		boolean inContent = mouseX > host.panelX + host.sidebarW();
		int dir = -(int) Math.signum(amount);
		if (inContent && host.page == HubPage.SHOP) {
			// T44：427×240 下一页 40 条只放得下 4 张卡 ⇒ 必须能滚，否则中间 36 条翻页也翻不到
			HubShopPage.scrollByRows(dir, host.frame.contentW(), host.frame.contentY(), host.frame.contentH());
			return true;
		}
		if (inContent && host.page == HubPage.TASKS) {
			// T56：任务数可配到 20，低分辨率下一页只放得下 5~6 行
			HubTasksPage.scrollByRows(dir);
			return true;
		}
		if (inContent && host.page == HubPage.BOARD) {
			// T12：低分辨率下一页只放得下 2~3 张卡
			HubBoardPage.scrollByRows(dir, host.frame.contentH());
			return true;
		}
		if (inContent && host.page == HubPage.LEDGER) {
			// T16：低分辨率下一页只放得下五六行
			HubLedgerPage.scrollByRows(dir, host.frame.contentH());
			return true;
		}
		if (inContent && host.page == HubPage.ADMIN) {
			// T28：指标卡两列，427×240 下装得下 6 行（12 项），超出的要靠滚
			HubAdminPage.scrollByRows(dir, host.frame.contentH());
			return true;
		}
		if (inContent && host.page == HubPage.MARKET) {
			// 服务端一页 18 条，427×240 只放得下 5 行（T45 同族：不滚就连"下一页"都跳过中间 13 条）
			HubMarketPage.scrollByRows(dir, host.frame.contentH());
			return true;
		}
		if (inContent && host.page == HubPage.MAIL) {
			// T62：待领取可能十几条，列表原来有 scrollRow 却没接滚轮（clamp 只在渲染里）
			HubMailPage.scrollByRows(dir, host.frame.contentY(), host.frame.contentH());
			return true;
		}
		if (inContent && host.page == HubPage.TITLES) {
			// 称号数由管理员发放，可远超一屏
			HubTitlesPage.scrollByRows(dir, host.frame.contentH());
			return true;
		}
		if (inContent && host.page == HubPage.STORAGE) {
			// ★上限只能按「总行数 − 屏内行数」★：原来按 SLOT_COUNT 钳，滚轮能滚到远超真实上限，
			// 渲染又自己钳了一次 ⇒ 看到的窗口与命中的行错位（搬错格）。
			int max = HubStoragePage.maxScrollRows(host.frame.contentW(), host.frame.contentH());
			host.storageScroll = Math.max(0, Math.min(max, host.storageScroll + dir));
			return true;
		}
		if (inContent && host.page == HubPage.SETTINGS) {
			// U10：Scale 2 下设置页内容区装不下全部行（管理员区会被裁掉），必须能滚
			if (HubSettingsPage.maxScroll(host.frame.contentH()) > 0) {
				HubSettingsPage.scrollBy(dir * 20);
				return true;
			}
		}
		if (inContent && host.page == HubPage.PASS) {
			int max = HubPassPage.maxScroll(host.frame.contentW());
			if (max > 0) {
				host.passScroll = Math.max(0, Math.min(max, host.passScroll + dir));
				return true;
			}
		}
		if (mouseX >= host.panelX && mouseX < host.panelX + host.sidebarW()
				&& host.frame.maxScroll() > 0) {
			host.sidebar.scrollBy(host.frame, amount);
			return true;
		}
		return null;
	}
}
