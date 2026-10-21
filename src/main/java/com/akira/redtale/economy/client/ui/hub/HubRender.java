package com.akira.redtale.economy.client.ui.hub;

import com.akira.redtale.economy.client.ShopClientState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;

/**
 * T32 批次3：HubScreen 的渲染块（原 {@code HubScreen.render} 主体 148 行）。
 *
 * <p>顺序即层级，改动前先看 {@code _LESSONS.md} 硬坑 1/2：
 * 底版 → 侧栏 → 内容区（带切页滑入）→ 搜索框 → 状态行 → 下拉 → 拖拽轮询 → 分类下拉 → 光标物品 → 侧栏 tip。
 * 只有「浮层」能画在这之后，且位置必须避开控件带。
 */
final class HubRender {
	private HubRender() {
	}

	static void render(HubScreen host, DrawContext dc, int mouseX, int mouseY) {
		boolean vanilla = host.vanilla();
		if (!vanilla) host.paintBackground(dc);
		// 外框（面板/标题/余额/关闭/分隔线/侧栏底色）整块交给 HubChrome
		HubChrome.render(dc, host.text(), host.frame, host.page, vanilla, host.width, host.height,
				HubChrome.headText(host.page), mouseX, mouseY);
		host.sidebar.render(dc, host.text(), host.frame, host.page, mouseX, mouseY);

		// 内容区（切换页面时轻微滑入）
		var mat = dc.getMatrices();
		long since = System.currentTimeMillis() - host.pageAnimAt;
		float t = host.pageAnimAt == 0 ? 1f : Math.min(1f, since / 120f);
		int slide = (int) (6 * (1 - t));
		mat.push();
		mat.translate(slide, 0, 0);
		int cx = host.frame.contentX();
		int cy = host.frame.contentY();
		int cw = host.frame.contentW();
		int ch = host.frame.contentH();

		pollShopAction(host);
		pollMarketAction(host);

		drawPage(host, dc, cx, cy, cw, ch, mouseX, mouseY);

		mat.pop();

		// 搜索框统一重绘（外观由 HubChrome 保证两页一致）
		if (host.page == HubPage.SHOP || host.page == HubPage.STORAGE) {
			HubChrome.renderSearch(dc, host.text(), host.frame, host.page,
					host.page == HubPage.SHOP ? host.shopQuery.toString() : host.storageQuery.toString(),
					host.searchFocused, vanilla);
		}

		// 底部：状态优先，空闲出 tip
		host.statusLine.render(dc, host.text(), host.frame);

		// T54：商店 hover 价格浮层（★浮层最后画★，画在格子里会被相邻格/分页条盖住）
		if (host.page == HubPage.SHOP) {
			HubShopPage.renderHoverOverlay(dc, host.text(), mouseX, mouseY, cx, cy, cw, ch);
		}

		// ★下拉最后画★，否则会被底栏/后面的元素盖住
		if (host.page == HubPage.RANK && HubRankPage.needsDropdownOverlay()) {
			HubRankPage.drawDropdown(dc, host.text(), cx, cy, host.rankBoard,
					host.panelY + host.panelH - HubFrame.BOTTOM_BAR, mouseX, mouseY);
		}
		tickDrag(host, mouseX, mouseY);
		if (host.page == HubPage.STORAGE && HubStoragePage.needsCatOverlay()) {
			HubStoragePage.drawCatDropdown(dc, host.text(), cx, cy, cw, host.storageCat,
					mouseX, mouseY);
		}
		// ★光标物品永远在最上层★
		if (host.page == HubPage.STORAGE) {
			HubStoragePage.drawCursor(dc, host.text(), mouseX, mouseY);
		}
		// ★侧边栏 tooltip 也在最后画★：以前在侧栏内部画，会被内容区/底栏盖住
		host.sidebar.drawPendingTooltip(dc, host.text(), mouseX, mouseY);
	}

	/** 商店买/卖结果回显（成功失败都进状态行，不进聊天）。含 T01 税额补显。 */
	private static void pollShopAction(HubScreen host) {
		if (host.page != HubPage.SHOP) return;
		if (ShopClientState.page() != host.lastShopPage) {
			host.lastShopPage = ShopClientState.page();
			host.shopSelected = -1;
			// 换页 / 换筛选后回到网格顶部：旧滚动位置在新一页上多半是空的
			HubShopPage.resetScroll();
		}
		var act = ShopClientState.lastAction();
		if (act != null && ShopClientState.lastActionAt() > host.lastShopActionAt) {
			host.lastShopActionAt = ShopClientState.lastActionAt();
			String base = act.ok() ? "已购买"
					: Text.translatable(act.messageKey()).getString();
			if (act.ok()) base = host.lastTradeBuy ? "已购买" : "已出售";
			// T01：成功时补税额（失败不说税，免得看起来像被多收了）
			if (act.ok() && host.lastTradeAmount > 0) {
				long tax = host.lastTradeBuy
						? ShopClientState.tax().buyTax(host.lastTradeItem, host.lastTradeAmount)
						: ShopClientState.tax().sellTax(host.lastTradeItem, host.lastTradeAmount);
				if (tax > 0) base = base + "（" + (host.lastTradeBuy ? "含税 " : "扣税 ") + tax + " 铜）";
			}
			host.setStatus(base);
		}
	}

	/** 市场页动作回显（R40）。 */
	private static void pollMarketAction(HubScreen host) {
		if (host.page != HubPage.MARKET) return;
		var mact = com.akira.redtale.economy.client.market.MarketClientNetworking.LAST_ACTION;
		long mAt = com.akira.redtale.economy.client.market.MarketClientNetworking.LAST_ACTION_AT;
		if (mact != null && mAt > host.lastMarketActionAt) {
			host.lastMarketActionAt = mAt;
			host.setStatus(mact.message());
		}
	}

	/** 一页一分支的页面绘制（几何统一取 frame.content*）。 */
	private static void drawPage(HubScreen host, DrawContext dc, int cx, int cy, int cw, int ch,
			int mouseX, int mouseY) {
		TextRenderer tr = host.text();
		switch (host.page) {
			case SETTINGS -> HubSettingsPage.render(dc, tr, cx, cy, cw, ch, mouseX, mouseY);
			case BOUNTY -> HubBountyPage.render(dc, tr, cx, cy, cw, ch, mouseX, mouseY, new int[]{-1});
			case BOARD -> HubBoardPage.render(dc, tr, cx, cy, cw, ch, mouseX, mouseY);
			case LEDGER -> HubLedgerPage.render(dc, tr, cx, cy, cw, ch, mouseX, mouseY);
			case ADMIN -> HubAdminPage.render(dc, tr, cx, cy, cw, ch, mouseX, mouseY);
			case GUILD -> HubGuildPage.render(dc, tr, cx, cy, cw, ch, mouseX, mouseY);
			case MAIL -> HubMailPage.render(dc, tr, cx, cy, cw, ch, mouseX, mouseY);
			case PAY -> HubPayPage.render(dc, tr, cx, cy, cw, ch, host.payName.toString(),
					host.payAmount.toString(), host.payFocus, mouseX, mouseY);
			case TITLES -> HubTitlesPage.render(dc, tr, cx, cy, cw, ch, mouseX, mouseY);
			case STORAGE -> HubStoragePage.render(dc, tr, cx, cy, cw, ch,
					host.storageQuery.toString(), host.storageCat, host.storageScroll, mouseX, mouseY);
			case PASS -> HubPassPage.render(dc, tr, cx, cy, cw, ch, host.passScroll, mouseX, mouseY);
			case RANK -> {
				HubRankPage.setSyncCooldownSeconds(host.rankSyncCooldownLeft());
				HubRankPage.render(dc, tr, cx, cy, cw, ch, host.rankBoard, host.rankActiveOnly,
						host.rankDropdown, mouseX, mouseY);
			}
			case MARKET -> HubMarketPage.render(dc, tr, cx, cy, cw, ch, mouseX, mouseY, host.marketSelected);
			case SHOP -> HubShopPage.render(dc, tr, cx, cy, cw, ch, mouseX, mouseY,
					host.shopQuery.toString(), host.shopSelected);
			case CHECKIN -> HubCheckinPage.render(dc, tr, cx, cy, cw, ch,
					com.akira.redtale.economy.client.checkin.CheckinClientNetworking.LAST,
					host.monthOffset, mouseX, mouseY);
			case TASKS -> HubTasksPage.render(dc, tr, cx, cy, cw, ch, mouseX, mouseY);
			default -> {
				dc.fill(cx, cy, cx + cw, cy + 64, HubTheme.CARD);
				dc.drawText(tr, Text.literal("这一页正在迁移到新界面"), cx + 8, cy + 10, HubTheme.TEXT, false);
				dc.drawText(tr, Text.literal("命令仍然可用：/rt " + host.page.id), cx + 8, cy + 28, HubTheme.MUTED, false);
				dc.drawText(tr, Text.literal("侧边栏 / 顶栏 / 底部提示已生效"), cx + 8, cy + 44, HubTheme.MUTED, false);
			}
		}
	}

	/**
	 * 拖拽态轮询（硬坑 9：不依赖 mouseDragged 事件，直接问 GLFW 是否还按着）。
	 *
	 * <p>松手即清拖拽态；仍按着就把当前经过的格子并进 {@code dragSlots}，
	 * 松手时由 {@link HubInput#mouseReleased} 一次性提交（避免每格一个包）。
	 */
	private static void tickDrag(HubScreen host, int mouseX, int mouseY) {
		if (host.page != HubPage.STORAGE) return;
		long handle = MinecraftClient.getInstance().getWindow().getHandle();
		boolean down0 = org.lwjgl.glfw.GLFW.glfwGetMouseButton(handle, 0)
				== org.lwjgl.glfw.GLFW.GLFW_PRESS;
		boolean down1 = org.lwjgl.glfw.GLFW.glfwGetMouseButton(handle, 1)
				== org.lwjgl.glfw.GLFW.GLFW_PRESS;
		if (!down0 && !down1) {
			host.dragActive = false;
			host.lastDragSlot = -1;
			return;
		}
		if (!host.dragActive) return;
		var rr = HubStoragePage.click(mouseX, mouseY, host.frame.contentX(), host.frame.contentY(),
				host.frame.contentW(), host.frame.contentH(),
				host.dragButton < 0 ? 0 : host.dragButton, host.storageScroll);
		if (rr.hit() == HubStoragePage.Hit.STORAGE && rr.index() >= 0
				&& !host.dragSlots.contains(rr.index())) {
			host.dragSlots.add(rr.index());
		}
	}
}
