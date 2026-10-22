package com.akira.redtale.economy.client.ui.hub;

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

	/** 当前页 id（生产用：接收器判断「用户是否停在这一页」）。 */
	String pageId() {
		return page.id;
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
	boolean shopRequested;
	int passScroll;
	/** T61：正在拖战令滚动条（按下在条上，松手复位）。 */
	boolean passBarDrag;
	final StringBuilder storageQuery = new StringBuilder();
	int storageCat ;
	int storageScroll ;
	int lastSlotKey = -1;
	long lastSlotAt ;
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
		// T51：深链接进被关闭的模块 ⇒ 落到签到页并说明，不是进一个永远空着的页面
		HubPage want = page == null ? HubPage.CHECKIN : page;
		if (!HubPage.accessible(want)) {
			s.page = HubPage.CHECKIN;
			s.statusLine.set(denyReason(want));
		} else {
			s.page = want;
		}
		MinecraftClient.getInstance().setScreen(s);
		// T51：侧栏可见性要用服务端下发的真实开关，首帧先按「全开」渲染，收到包后自动收窄
		ModulesClientNetworking.request();
		// ★首屏也要拉数据★：以前只走 setPage 才请求，于是「直接打开 Hub 落在排行/商店页」
		// （K 键、/rt rank、服务端 HUB_OPEN）会永远停在空态 —— 2026-10-21 专用服截图抓到的。
		s.pullData(s.page);
	}

	/**
	 * 这个页面进不去的原因（给状态栏用）。
	 *
	 * <p>★别一律说「该模块未开启」★ {@link HubPage#ADMIN} 的 module 是 {@code null}（恒开），
	 * 它进不去只可能是没权限。老代码把两种情况都写成「该模块未开启」，非 OP 的服主看到
	 * 这句话会去翻 config 找那个根本不存在的开关 —— 2026-10-22 u6-86 截图抓到的。
	 */
	static String denyReason(HubPage p) {
		if (p != null && p.adminOnly && !HubPage.canAdmin()) return "需要管理员权限";
		return "该模块未开启";
	}

	/** U8b：/rt 切页时复用当前界面，别把搜索词和滚动位置清掉。 */
	public void setPage(HubPage p) {
		if (p == null || p == page) return;
		if (!HubPage.accessible(p)) {
			statusLine.set(denyReason(p));
			return;
		}
		this.page = p;
		statusLine.clear();
		this.searchFocused = false;
		this.rankDropdown = false;
		pullData(p);
	}

	/**
	 * T51：当前页在模块被关掉后必须能下来。
	 *
	 * <p>只在 {@link HubPage#visible()} 里过滤是不够的 —— 玩家正停在该页时
	 * 侧栏已经没有那一行了，但内容区还在画已关闭模块的数据。
	 */
	void clampPageToVisible() {
		if (HubPage.accessible(page)) return;
		this.page = HubPage.SETTINGS;
		pullData(page);
	}

	/** 切到某页时顺手拉一次数据（服务端只回快照，不做写操作）。 */
	private void pullData(HubPage p) {
		HubRequests.pullData(this, p);
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
		// T56：退出界面要清掉 Hub 模式，否则下次从聊天栏用 /tasks 会「只发页面数据、不弹面板」
		TasksHubClientNetworking.request(false);
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

	void requestShopPage() {
		HubRequests.requestShopPage(this);
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
