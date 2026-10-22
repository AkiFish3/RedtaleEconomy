package com.akira.redtale.economy.client.ui.hub;

/**
 * T32 批次4：HubScreen 的 dev 夹具探针（原 HubScreen.dev* 16 个方法）。
 *
 * <p>只给 {@code client/dev/UiShots} 用：全部走真实命中路径（{@code mouseClicked} /
 * {@code charTyped}）而不是直接改状态，否则截图验的是「我改过的字段」不是「用户点得到的按钮」。
 * 生产路径不引用本类；挪出来是为了让 HubScreen 只剩界面骨架。
 */
public final class HubDev {
	private HubDev() {
	}

	public static boolean clickSidebar(HubScreen host, int index) {
		HubPage[] all = HubPage.visible();
		if (index < 0 || index >= all.length) return false;
		// ★先把目标行滚进条目区★ 427×240 下条目区只有 5 行，第 12 行（称号）的中心落在
		// entryBottom 之下 ⇒ 直接按 rowCenterY 点必然被 rowAt 的 [entryTop,entryBottom) 夹掉
		// （2026-10-23 实测 `page click TITLES hit=false`，拍出来还是上一页）。
		// 这里只走真实的滚动状态（sidebar.setScroll），不改任何渲染/命中口径。
		HubFrame f = host.frame;
		int want = Math.max(0, Math.min(f.maxScroll(), index * HubFrame.ROW_H));
		host.sidebar.setScroll(want);
		double y = host.sidebar.rowCenterY(f, index);
		return host.mouseClicked(host.panelX + host.sidebarW() / 2.0, y, 0);
	}

	public static String pageId(HubScreen host) {
		return host.pageId();
	}

	/** 状态栏文字（截图看不出「为什么落在这页」，用日志断言）。 */
	public static String statusText(HubScreen host) {
		return host.statusLine.text();
	}

	/**
	 * 夹具专用：绕过 {@link HubPage#accessible} 直接落到指定页。
	 *
	 * <p>★为什么需要★ {@code HubScreen.open(ADMIN)} 在「没有玩家」时会被判成非 OP ⇒ 静默回落
	 * CHECKIN，于是截图文件名叫 {@code u6-86-hub-admin}、日志里 {@code page=checkin} ——
	 * 文件名对、内容错，按文件名判绿的扫描器完全看不出来（2026-10-22 实证）。
	 * 本方法只给 dev 夹具用，不进任何玩家可达路径。
	 *
	 * <p>★顺手清状态栏★ 回落时 {@code open()} 已经写了「需要管理员权限」；不清掉的话
	 * 截图里控制台正文写着「需要管理员权限」、底栏还挂着上一页的提示，看起来像产品缺陷
	 * （2026-10-22 u6-86 截图就是这样，白查一轮）。
	 */
	public static void forcePage(HubScreen host, HubPage page) {
		if (host == null || page == null) return;
		host.page = page;
		host.statusLine.clear();
	}

	/**
	 * 按页面点侧栏。
	 *
	 * <p>★别写死下标★：模块开关（T51）会让 {@link HubPage#visible()} 少一项，之后的每个下标都前移，
	 * 写死的 11 可能点到「排行」。
	 */
	public static boolean clickSidebar(HubScreen host, HubPage page) {
		HubPage[] all = HubPage.visible();
		for (int i = 0; i < all.length; i++) {
			if (all[i] == page) return clickSidebar(host, i);
		}
		return false;
	}

	public static boolean clickSettingsRow(HubScreen host, HubSettingsPage.Row row) {
		if (host.page != HubPage.SETTINGS) return false;
		int cx = host.frame.contentX();
		int cy = host.frame.contentY();
		int cw = host.frame.contentW();
		int ch = host.frame.contentH();
		double[] p = HubSettingsPage.rowCenter(row, cx, cy, cw, ch);
		if (p == null) return false;
		return host.mouseClicked(p[0], p[1], 0);
	}

	/** T51 夹具：点某个模块行（走真实 mouseClicked，命中不到就返回 false）。 */
	public static boolean clickModuleRow(HubScreen host, String moduleId) {
		if (host.page != HubPage.SETTINGS) return false;
		double[] p = HubSettingsPage.rowCenterForModule(moduleId,
				host.frame.contentX(), host.frame.contentY(),
				host.frame.contentW(), host.frame.contentH());
		if (p == null) return false;
		return host.mouseClicked(p[0], p[1], 0);
	}

	public static int settingsContentHeight(HubScreen host) {
		return host.panelH - HubFrame.TOP_BAR - HubFrame.BOTTOM_BAR - 16;
	}

	public static int settingsScrollMax(HubScreen host) {
		return HubSettingsPage.maxScroll(settingsContentHeight(host));
	}

	public static void scrollSettings(HubScreen host, int v) {
		HubSettingsPage.scrollTo(v);
	}

	public static int scrollShopBottom(HubScreen host) {
		if (host.page != HubPage.SHOP) return -1;
		int cw = host.frame.contentW();
		int ch = host.frame.contentH();
		int cy = host.frame.contentY();
		HubShopPage.scrollByRows(9999, cw, cy, ch);
		return HubShopPage.devScrollRow();
	}

	public static boolean clickTreeRow(HubScreen host, int row) {
		if (host.page != HubPage.SHOP) return false;
		int cx = host.frame.contentX();
		int cy = host.frame.contentY();
		int cw = host.frame.contentW();
		int ch = host.frame.contentH();
		double[] p = HubShopPage.treeRowCenter(cx, cy, ch, row);
		if (p == null) return false;
		return host.mouseClicked(p[0], p[1], 0);
	}

	/**
	 * T69b 夹具：点「视图」chip（走真实 mouseClicked）。{@code true} = 命中并已切换。
	 * 密度 chip 同理走 {@link #clickShopDensityChip}。
	 */
	public static boolean clickShopViewChip(HubScreen host) {
		return clickShopChip(host, 5);
	}

	public static boolean clickShopDensityChip(HubScreen host) {
		return clickShopChip(host, 4);
	}

	private static boolean clickShopChip(HubScreen host, int index) {
		if (host.page != HubPage.SHOP) return false;
		double[] p = HubShopPage.chipCenter(host.text(), host.frame.contentX(),
				host.frame.contentY(), host.frame.contentW(), index);
		if (p == null) return false;
		return host.mouseClicked(p[0], p[1], 0);
	}

	/** T69b 夹具：当前视图模式（0 = 图标网格，1 = 卡片列表）。 */
	public static int shopViewVal(HubScreen host) {
		return ClientUiConfig.shopView;
	}

	/**
	 * 夹具：点第 {@code index} 件商品（走真实 mouseClicked）。
	 * {@code true} = 命中并改选中；坐标由页面按当前模式算，夹具不写死格子尺寸。
	 */
	public static boolean clickShopItem(HubScreen host, int index) {
		if (host.page != HubPage.SHOP) return false;
		double[] p = HubShopPage.itemCenter(index, host.frame.contentX(), host.frame.contentY(),
				host.frame.contentW(), host.frame.contentH());
		if (p == null) return false;
		return host.mouseClicked(p[0], p[1], 0);
	}

	/**
	 * 夹具：切到仓储页第 {@code cat} 个分类（0 全部 … 4 其他），全程走真实 mouseClicked：
	 * 先点 CAT 按钮展开，再点列表里那一行 —— 命中几何由页面自己算，不手写坐标。
	 *
	 * <p>这条路径正是第 N+72 轮修的缺陷：列表行的点击原来落进网格判定（点分类＝抓物品）。
	 */
	public static boolean clickStorageCat(HubScreen host, int cat) {
		if (host.page != HubPage.STORAGE) return false;
		int cx = host.frame.contentX();
		int cy = host.frame.contentY();
		int cw = host.frame.contentW();
		int ch = host.frame.contentH();
		double[] btn = HubStoragePage.catButtonCenter(cx, cy, cw);
		if (btn == null) return false;
		if (!host.mouseClicked(btn[0], btn[1], 0)) return false;          // 展开
		double[] row = HubStoragePage.catRowCenter(cat, cx, cy, cw, ch);
		if (row == null) return false;
		return host.mouseClicked(row[0], row[1], 0);
	}

	/** 夹具：当前仓储分类下标（-1 = 不在仓储页）。 */
	public static int storageCatVal(HubScreen host) {
		return host.page == HubPage.STORAGE ? host.storageCat : -1;
	}

	/** 夹具：分类下拉是否展开。 */
	public static boolean storageCatOpenVal() {
		return HubStoragePage.catOpen;
	}

	public static boolean clickRankToggle(HubScreen host) {
		if (host.page != HubPage.RANK) return false;
		int cx = host.frame.contentX();
		int cy = host.frame.contentY();
		// 复选框矩形 = x..x+70 / y+22..y+36（render 里 sy=y+24，命中判定 -2/+12）
		return host.mouseClicked(cx + 30, cy + 29, 0);
	}

	public static boolean clickRankDropdown(HubScreen host) {
		if (host.page != HubPage.RANK) return false;
		int cx = host.frame.contentX();
		int cy = host.frame.contentY();
		return host.mouseClicked(cx + 20, cy + 9, 0);
	}

	public static boolean rankActiveOnlyVal(HubScreen host) {
		return host.rankActiveOnly;
	}

	public static void search(HubScreen host, String text) {
		if (host.page != HubPage.SHOP || text == null) return;
		host.shopQuery.setLength(0);
		host.searchFocused = true;
		for (int i = 0; i < text.length(); i++) {
			host.charTyped(text.charAt(i), 0);
		}
		host.requestShopPage();
	}

	public static boolean clickRankBoard(HubScreen host, int idx) {
		if (host.page != HubPage.RANK) return false;
		int cx = host.frame.contentX();
		int cy = host.frame.contentY();
		int listH = HubRankPage.BOARDS.length * 16;
		int bottom = host.panelY + host.panelH - HubFrame.BOTTOM_BAR;
		int iy = (cy + 20 + listH <= bottom) ? cy + 20 : cy - listH;
		return host.mouseClicked(cx + 20, iy + idx * 16 + 8, 0);
	}

	public static int rankBoardVal(HubScreen host) {
		return host.rankBoard;
	}

	public static boolean clickRankSync(HubScreen host) {
		if (host.page != HubPage.RANK) return false;
		int cx = host.frame.contentX();
		int cy = host.frame.contentY();
		int cw = host.frame.contentW();
		// 命中区 = x+w-20..x+w / sy-2..sy+12（render 里 sy = cy+24）
		return host.mouseClicked(cx + cw - 10, cy + 29, 0);
	}

	public static int rankSyncCooldownLeftVal(HubScreen host) {
		return host.rankSyncCooldownLeft();
	}

	/**
	 * T61 夹具：点战令滚动条最右端（走真实 mouseClicked，验证「点得到、滚得动」）。
	 *
	 * <p>返回 {@code false} 有两种：当前不在战令页，或整条奖励装得下（没有滚动条）。
	 */
	public static boolean clickPassBarEnd(HubScreen host) {
		if (host.page != HubPage.PASS) return false;
		double[] p = HubPassPage.barEndCenter(host.frame.contentX(), host.frame.contentY(),
				host.frame.contentW(), host.frame.contentH());
		if (p == null) return false;
		return host.mouseClicked(p[0], p[1], 0);
	}

	public static int passScrollVal(HubScreen host) {
		return host.passScroll;
	}

	/**
	 * 夹具：用真实 {@code mouseScrolled} 路径滚当前页（{@code dir > 0} 向下）。
	 *
	 * <p>★别直接改字段★：字段能改不代表滚轮接了线 —— 第 N+70 轮邮件页就是
	 * 「有 scrollRow、clamp 也对，就是 mouseScrolled 没分支」的死滚动，
	 * 改字段的夹具照样"验证通过"。这里走 host 回调，滚不动就证明分支缺失。
	 */
	public static boolean scrollPage(HubScreen host, int dir) {
		if (host == null || dir == 0) return false;
		double mx = host.frame.contentX() + host.frame.contentW() / 2.0;
		double my = host.frame.contentY() + host.frame.contentH() / 2.0;
		// 原版：amount > 0 = 向上 ⇒ 想向下滚就传负值
		host.mouseScrolled(mx, my, -dir);
		return true;
	}

	/** 夹具探针：各页当前滚动行（截图轮用它断言「滚轮真的进了页面」）。 */
	public static String scrollProbe() {
		return "bounty=" + HubBountyPage.scrollRow()
				+ " guild=" + HubGuildPage.scrollRow()
				+ " rank=" + HubRankPage.scrollRow()
				+ " board=" + HubBoardPage.devScrollRow();
	}
}
