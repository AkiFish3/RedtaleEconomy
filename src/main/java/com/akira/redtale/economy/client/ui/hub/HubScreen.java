package com.akira.redtale.economy.client.ui.hub;

import com.akira.redtale.economy.client.ShopClientNetworking;
import com.akira.redtale.economy.client.ShopClientState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

/**
 * Hub 主界面（U4，按 spec §37/§43/§47/§48 重写）。
 *
 * <p>结构：左侧可配置侧边栏（玩家区 + 页面列表）+ 顶部当前页名 + 内容区 + 底部状态/提示行。
 * 第一版只把「框架 + 设置页」做实，其余页面显示占位，后续逐页迁移。
 */
public class HubScreen extends Screen {

	boolean autoCompact;

	boolean vanilla() {
		return "vanilla".equals(ClientUiConfig.theme);
	}

	int sidebarW() {
		return frame.sidebarW();
	}

	/** T32 批次2：侧栏行号判定（侧栏实例不外露，只给 handler 一个查询口）。 */
	int sidebarRowAt(double my) {
		return sidebar.rowAt(frame, my);
	}

	/** 面板几何（T32：所有坐标只算一次，见 {@link HubFrame}）。 */
	HubFrame frame;
	final HubSidebar sidebar = new HubSidebar();
	final HubStatusLine statusLine = new HubStatusLine();


	HubPage page = HubPage.CHECKIN;
	int panelX ;
	int panelY ;
	int panelW ;
	int panelH ;

	int monthOffset ;
	final StringBuilder shopQuery = new StringBuilder();
	int shopSelected = -1;
	/** R40：市场页选中的行（-1 = 未选） */
	int marketSelected = -1;
	private boolean shopRequested;
	int passScroll;
	final StringBuilder storageQuery = new StringBuilder();
	int storageCat ;
	int storageScroll ;
	int lastSlotKey = -1;
	long lastSlotAt ;
	/** U11b：点击筛选 chip → 循环下一个取值（""/"全部" 也算一档，循环回得去）。 */
	void cycleFilter(int slot) {
		var f = ShopClientState.filter();
		var page = ShopClientState.page();
		switch (slot) {
			case 0 -> {
				var mods = page.mods();
				int i = mods.indexOf(f.modOrEmpty());
				String next = (i < 0 || i + 1 >= mods.size()) ? "" : mods.get(i + 1);
				ShopClientState.setFilter(new com.akira.redtale.economy.net.ShopFilter(
						next, f.categoryOrEmpty(), f.minPrice(), f.maxPrice(), f.inStockOnly()));
			}
			case 1 -> {
				var cats = page.categories();
				int i = cats.indexOf(f.categoryOrEmpty());
				String next = (i < 0 || i + 1 >= cats.size()) ? "" : cats.get(i + 1);
				ShopClientState.setFilter(new com.akira.redtale.economy.net.ShopFilter(
						f.modOrEmpty(), next, f.minPrice(), f.maxPrice(), f.inStockOnly()));
			}
			case 2 -> {
				long[] steps = {0, 10, 100, 1_000, 10_000, 100_000};
				int i = 0;
				for (int k = 0; k < steps.length; k++) {
					if (steps[k] == f.maxPrice()) {
						i = k;
						break;
					}
				}
				long next = steps[(i + 1) % steps.length];
				ShopClientState.setFilter(new com.akira.redtale.economy.net.ShopFilter(
						f.modOrEmpty(), f.categoryOrEmpty(), 0, next, f.inStockOnly()));
			}
			default -> ShopClientState.setFilter(new com.akira.redtale.economy.net.ShopFilter(
					f.modOrEmpty(), f.categoryOrEmpty(), f.minPrice(), f.maxPrice(), !f.inStockOnly()));
		}
		shopSelected = -1;
	}

	boolean dragPlacing ;
	int dragButton = -1;
	boolean searchFocused ;
	long pageAnimAt;
	boolean dragActive ;
	int pendingIdx = -1;
	boolean pendingInv ;
	boolean pendingAll ;
	int pressKey = -9999;
	final StringBuilder payName = new StringBuilder();
	final StringBuilder payAmount = new StringBuilder();
	int payFocus ;
	final java.util.List<Integer> dragSlots = new java.util.ArrayList<>();
	long lastShopActionAt;
	/** T01：最近一次点买/卖的商品与金额，用来在回包后补「含税 x 铜」。 */
	String lastTradeItem = "";
	long lastTradeAmount ;
	boolean lastTradeBuy = true;
	long lastMarketActionAt;
	/** §43.6：排行榜「同步」按钮 60 秒冷却（只读缓存，防刷）。 */
	long lastRankSyncAt ;
	Object lastShopPage;
	int lastDragSlot = -1;
	int rankBoard ;
	/**
	 * 「只看活跃」开关。**默认 true**（spec §43.6：排行榜默认只统计 lastSeen 在 activeDays 内的人）。
	 *
	 * <p>曾经默认 false ⇒ 首次打开看到的是「含长期未上线」的全量榜，
	 * 而 {@code /rt rank} 推下来的是活跃榜、复选框却画成未勾 ⇒ 界面状态和数据对不上。
	 */
	boolean rankActiveOnly = true;
	boolean rankDropdown ;

	public HubScreen() {
		super(Text.literal("Redtale"));
		ClientUiConfig.load();
		HubTheme.apply(ClientUiConfig.theme);
		Tips.load();
	}

	/** 打开 Hub（可指定初始页）。 */
	public static void open(HubPage page) {
		HubScreen s = new HubScreen();
		s.page = page == null ? HubPage.CHECKIN : page;
		MinecraftClient.getInstance().setScreen(s);
		// ★首屏也要拉数据★：以前只走 setPage 才请求，于是「直接打开 Hub 落在排行/商店页」
		// （K 键、/rt rank、服务端 HUB_OPEN）会永远停在空态 —— 2026-10-21 专用服截图抓到的。
		s.pullData(s.page);
	}

	/** U8b：/rt 切页时复用当前界面，别把搜索词和滚动位置清掉。 */
	public void setPage(HubPage p) {
		if (p == null || p == page) return;
		this.page = p;
		statusLine.clear();
		this.searchFocused = false;
		this.rankDropdown = false;
		pullData(p);
	}

	/** 切到某页时顺手拉一次数据（服务端只回快照，不做写操作）。 */
	private void pullData(HubPage p) {
		var mc = MinecraftClient.getInstance();
		if (mc.player == null) return;
		switch (p) {
			case SHOP -> {
				String sid = ShopClientState.shopIdToRequest();
				if (!sid.isEmpty()) ShopClientNetworking.requestPage(sid, 0, "");
			}
			case RANK -> com.akira.redtale.economy.client.rank.RankClientNetworking.request(rankActiveOnly);
			case MARKET -> com.akira.redtale.economy.client.market.MarketClientNetworking.request();
			case BOARD -> com.akira.redtale.economy.client.ui.hub.BoardClientNetworking.request();
			case LEDGER -> com.akira.redtale.economy.client.ui.hub.LedgerClientNetworking.request();
			case CHECKIN -> mc.player.networkHandler.sendChatCommand("checkin status");
			default -> {
			}
		}
	}

	/** T32 批次3：Screen.renderBackground / hasShiftDown 是 protected，给同包渲染类一个桥。 */
	void paintBackground(DrawContext dc) {
		renderBackground(dc);
	}

	@Override
	public boolean shouldPause() {
		// §39 D1：不暂停单人游戏
		return false;
	}

	@Override
	protected void init() {
		// 仓储页要塞 54 格 + 背包，整体放大一点
		// 窗口太窄就自动切紧凑（GUI Scale 大时尤其明显）
		autoCompact = this.width < 460;
		panelW = Math.max(240, Math.min(470, this.width - 20));
		panelH = Math.max(180, Math.min(340, this.height - 20));
		panelX = (this.width - panelW) / 2;
		panelY = (this.height - panelH) / 2;
		frame = new HubFrame(panelX, panelY, panelW, panelH, autoCompact);
		statusLine.resetTipTimer();
		// 只查询状态，不会触发签到（服务端 /checkin status 会下发快照）
		var mc = MinecraftClient.getInstance();
		if (mc.player != null) {
			mc.player.networkHandler.sendChatCommand("checkin status");
			com.akira.redtale.economy.client.balance.BalanceClientNetworking.request();
			com.akira.redtale.economy.client.warehouse.WarehouseClientNetworking.request();
			com.akira.redtale.economy.client.title.TitlesClientNetworking.request();
		}
		// 直接以商店页开场时（/rt shop、S2C_OPEN 打开）必须自己拉一次页，
		// 否则只有侧边栏点击才会请求，页面永远停在上一份空快照。
		if (page == HubPage.SHOP && !shopRequested) requestShopPage();
	}

	@Override
	public void render(DrawContext dc, int mouseX, int mouseY, float delta) {
		// T32 批次3：渲染顺序（底版 → 侧栏 → 内容 → 搜索 → 状态 → 浮层 → tip）整块在 HubRender
		HubRender.render(this, dc, mouseX, mouseY);
		super.render(dc, mouseX, mouseY, delta);
	}

	/** 供页面调用的状态提示（§38：界面内提示，不进聊天）。 */
	public void setStatus(String text) {
		statusLine.set(text);
	}

	/**
	 * R39/U10：所有点击统一走这里 —— 命中（返回 true）才播一次按钮音。
	 *
	 * <p>音效开关由「设置 → 按钮音效」控制（{@link ClientUiConfig#click()} 内部判 silent）。
	 * 之前 Hub 内点击完全没接音效，silent 与 vanilla 听不出区别。
	 */
	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		// 关闭框几何只有一个来源（frame），命中判定与绘制共用 HubChrome.closeHit
		if (HubChrome.closeHit(frame, mouseX, mouseY)) {
			this.close();
			return true;
		}
		boolean handled = handleClick(mouseX, mouseY, button);
		if (handled && button == 0) ClientUiConfig.click();
		return handled;
	}

	/**
	 * 仅 dev 截图夹具（{@code client.dev.UiShots}）使用：点侧边栏第 {@code index} 项。
	 *
	 * <p>故意走 {@link #mouseClicked} 而不是直接改 {@code page} —— 这样命中判定的坐标换算
	 * （{@code sidebarScroll}、{@code HubFrame.ROW_H}、裁剪区）才是真被验证的那条代码路径。
	 *
	 * @return 是否命中
	 */
	public boolean devClickSidebar(int index) {
		HubPage[] all = HubPage.values();
		if (index < 0 || index >= all.length) return false;
		double y = sidebar.rowCenterY(frame, index);
		return mouseClicked(panelX + sidebarW() / 2.0, y, 0);
	}

	/** 仅 dev 夹具使用：当前页 id（写截图日志用）。 */
	public String devPageId() {
		return page.id;
	}

	/**
	 * 仅 dev 夹具（U10）：点设置页的某一行。
	 *
	 * <p>坐标来自 {@link HubSettingsPage#rowCenter}，与 {@code handleClick} 里的命中判定同一套布局，
	 * 而且走 {@link #mouseClicked} ⇒ 验的是「真实点击能不能点到」，不是直接改静态开关。
	 *
	 * @return 是否命中（行被裁剪/不存在时返回 false）
	 */
	public boolean devClickSettingsRow(HubSettingsPage.Row row) {
		if (page != HubPage.SETTINGS) return false;
		int cx = frame.contentX();
		int cy = frame.contentY();
		int cw = frame.contentW();
		int ch = frame.contentH();
		double[] p = HubSettingsPage.rowCenter(row, cx, cy, cw, ch);
		if (p == null) return false;
		return mouseClicked(p[0], p[1], 0);
	}

	/** 仅 dev 夹具：设置页内容区高度（用于算滚动上限）。 */
	public int devSettingsContentHeight() {
		return panelH - HubFrame.TOP_BAR - HubFrame.BOTTOM_BAR - 16;
	}

	/** 仅 dev 夹具：设置页滚动上限（0 = 装得下，不需要滚）。 */
	public int devSettingsScrollMax() {
		return HubSettingsPage.maxScroll(devSettingsContentHeight());
	}

	/** 仅 dev 夹具：设置页滚到指定偏移（越界由 render 夹回）。 */
	public void devScrollSettings(int v) {
		HubSettingsPage.scrollTo(v);
	}

	/**
	 * 仅 dev 夹具（T44）：把商店网格滚到底，返回最终滚动行数。
	 *
	 * <p>不用 {@code mouseScrolled} 而是直接调 {@code scrollByRows}：滚轮事件的
	 * 坐标/增量在夹具里没法逐格复现，而这里要验的是「被裁掉的卡片能不能到达」。
	 */
	public int devScrollShopBottom() {
		if (page != HubPage.SHOP) return -1;
		int cw = frame.contentW();
		int ch = frame.contentH();
		int cy = frame.contentY();
		HubShopPage.scrollByRows(9999, cw, cy, ch);
		return HubShopPage.devScrollRow();
	}

	/** 仅 dev 夹具：点商店左侧目录树第 {@code row} 行（走真实 mouseClicked 命中路径）。 */
	public boolean devClickTreeRow(int row) {		if (page != HubPage.SHOP) return false;
		int cx = frame.contentX();
		int cy = frame.contentY();
		int cw = frame.contentW();
		int ch = frame.contentH();
		double[] p = HubShopPage.treeRowCenter(cx, cy, ch, row);
		if (p == null) return false;
		return mouseClicked(p[0], p[1], 0);
	}

	/**
	 * 仅 dev 夹具（U9）：点排行页的「只看活跃」复选框。
	 *
	 * <p>坐标与 {@code handleClick} 里 {@code HubRankPage.click(...)} 判定的矩形同源，
	 * 而且走 {@link #mouseClicked} ⇒ 验的是真命中路径（含 {@code inSearchBox} 让位）。
	 */
	public boolean devClickRankToggle() {
		if (page != HubPage.RANK) return false;
		int cx = frame.contentX();
		int cy = frame.contentY();
		// 复选框矩形 = x..x+70 / y+22..y+36（render 里 sy=y+24，命中判定 -2/+12）
		return mouseClicked(cx + 30, cy + 29, 0);
	}

	/** 仅 dev 夹具（U9/§48.6）：点排行页顶部的榜单下拉（验「其余榜为二级」的下拉是活的）。 */
	public boolean devClickRankDropdown() {
		if (page != HubPage.RANK) return false;
		int cx = frame.contentX();
		int cy = frame.contentY();
		return mouseClicked(cx + 20, cy + 9, 0);
	}

	/** 仅 dev 夹具：当前「只看活跃」开关值（写日志用，不做判定的第二来源）。 */
	public boolean devRankActiveOnly() {
		return rankActiveOnly;
	}

	/** 仅 dev 夹具：走真实 charTyped 通道写字，再请求一页（验搜索真的过服务端）。 */
	public void devSearch(String text) {
		if (page != HubPage.SHOP || text == null) return;
		shopQuery.setLength(0);
		searchFocused = true;
		for (int i = 0; i < text.length(); i++) {
			charTyped(text.charAt(i), 0);
		}
		requestShopPage();
	}

	private boolean handleClick(double mouseX, double mouseY, int button) {
		return HubClicks.handle(this, mouseX, mouseY, button);
	}

	/** 兜底：转发到 Screen.mouseClicked（handler 不认领时走原逻辑）。 */
	boolean fallbackClick(double mouseX, double mouseY, int button) {
		return super.mouseClicked(mouseX, mouseY, button);
	}

	/** 字体渲染器给 handler 用（Screen.textRenderer 是 protected）。 */
	net.minecraft.client.font.TextRenderer text() {
		return this.textRenderer;
	}

	/** 供 handler 用（Screen.hasShiftDown 是 protected static）。 */
	boolean shiftDown() {
		return hasShiftDown();
	}

	@Override
	public void removed() {
		// 关闭界面时把光标里的东西退回背包，避免物品卡在服务端
		if (page == HubPage.STORAGE) {
			HubStoragePage.confirmUpgrade = false;
			com.akira.redtale.economy.client.warehouse.WarehouseClientNetworking.dropCursor();
		}
		super.removed();
	}

	/** 鼠标是否落在当前页的搜索框里。 */
	boolean inSearchBox(double mx, double my) {
		if (page != HubPage.SHOP && page != HubPage.STORAGE) return false;
		// 与渲染共用 frame 的搜索框矩形，别再手写 cw-40 / cy+16
		int x = frame.contentX();
		int y = frame.searchY(page);
		return mx >= x && mx < x + frame.searchW(page)
				&& my >= y && my < y + frame.searchH(page);
	}

	/** 排行页「同步」还剩多少秒冷却（0 = 可点）；冷却秒数读客户端配置，0 表示不限。 */
	int rankSyncCooldownLeft() {
		int cd = ClientUiConfig.rankSyncCooldownSeconds;
		if (cd <= 0 || lastRankSyncAt <= 0) return 0;
		long left = cd * 1000L - (System.currentTimeMillis() - lastRankSyncAt);
		if (left <= 0) return 0;
		return (int) Math.ceil(left / 1000.0);
	}

	/** 仅 dev 夹具：点榜单下拉里的第 {@code idx} 项（走 hitDropdown 同一套坐标）。 */
	public boolean devClickRankBoard(int idx) {
		if (page != HubPage.RANK) return false;
		int cx = frame.contentX();
		int cy = frame.contentY();
		int listH = HubRankPage.BOARDS.length * 16;
		int bottom = panelY + panelH - HubFrame.BOTTOM_BAR;
		int iy = (cy + 20 + listH <= bottom) ? cy + 20 : cy - listH;
		return mouseClicked(cx + 20, iy + idx * 16 + 8, 0);
	}

	/** 仅 dev 夹具：当前选中的榜（0=财富）。 */
	public int devRankBoard() {
		return rankBoard;
	}

	/** 仅 dev 夹具：点排行页的「同步」按钮（验 60 秒冷却与置灰）。 */
	public boolean devClickRankSync() {
		if (page != HubPage.RANK) return false;
		int cx = frame.contentX();
		int cy = frame.contentY();
		int cw = frame.contentW();
		// 命中区 = x+w-20..x+w / sy-2..sy+12（render 里 sy = cy+24）
		return mouseClicked(cx + cw - 10, cy + 29, 0);
	}

	/** 仅 dev 夹具：当前同步冷却剩余秒数（写日志用）。 */
	public int devRankSyncCooldownLeft() {
		return rankSyncCooldownLeft();
	}

	void requestGuild() {
		var mc = MinecraftClient.getInstance();
		if (mc.player != null) mc.player.networkHandler.sendChatCommand("guild");
	}

	void requestPass() {
		var mc = MinecraftClient.getInstance();
		if (mc.player != null) mc.player.networkHandler.sendChatCommand("pass");
	}

	void requestBounty() {
		var mc = MinecraftClient.getInstance();
		if (mc.player != null) mc.player.networkHandler.sendChatCommand("bounty list");
	}

	void requestShopPage() {
		shopRequested = true;
		String sid = ShopClientState.shopIdToRequest();
		if (sid.isEmpty()) return;   // 还没收到 S2C_OPEN，等它到了再请求，别发空 id 换回空页
		ShopClientNetworking.requestPage(sid, 0, shopQuery.toString());
	}

	@Override
	public boolean charTyped(char chr, int modifiers) {
		Boolean r = HubInput.charTyped(this, chr, modifiers);
		return r != null ? r : super.charTyped(chr, modifiers);
	}

	@Override
	public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
		Boolean r = HubInput.keyPressed(this, keyCode, scanCode, modifiers);
		return r != null ? r : super.keyPressed(keyCode, scanCode, modifiers);
	}

	@Override
	public boolean mouseDragged(double mouseX, double mouseY, int button, double dx, double dy) {
		Boolean r = HubInput.mouseDragged(this, mouseX, mouseY, button);
		return r != null ? r : super.mouseDragged(mouseX, mouseY, button, dx, dy);
	}

	@Override
	public boolean mouseReleased(double mouseX, double mouseY, int button) {
		HubInput.mouseReleased(this);
		return super.mouseReleased(mouseX, mouseY, button);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
		Boolean r = HubInput.mouseScrolled(this, mouseX, amount);
		return r != null ? r : super.mouseScrolled(mouseX, mouseY, amount);
	}

}
