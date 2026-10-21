package com.akira.redtale.economy.client.ui.hub;

import com.akira.redtale.economy.client.ShopClientNetworking;
import com.akira.redtale.economy.client.ShopClientState;
import com.akira.redtale.economy.net.ShopProtocol;
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

	private boolean isVanilla() {
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
	private final HubSidebar sidebar = new HubSidebar();
	private final HubStatusLine statusLine = new HubStatusLine();


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
	private long pageAnimAt;
	boolean dragActive ;
	int pendingIdx = -1;
	boolean pendingInv ;
	boolean pendingAll ;
	int pressKey = -9999;
	final StringBuilder payName = new StringBuilder();
	final StringBuilder payAmount = new StringBuilder();
	int payFocus ;
	final java.util.List<Integer> dragSlots = new java.util.ArrayList<>();
	private long lastShopActionAt;
	/** T01：最近一次点买/卖的商品与金额，用来在回包后补「含税 x 铜」。 */
	String lastTradeItem = "";
	long lastTradeAmount ;
	boolean lastTradeBuy = true;
	private long lastMarketActionAt;
	/** §43.6：排行榜「同步」按钮 60 秒冷却（只读缓存，防刷）。 */
	long lastRankSyncAt ;
	private Object lastShopPage;
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
		boolean vanilla = isVanilla();
		if (!vanilla) this.renderBackground(dc);
		// 外框（面板/标题/余额/关闭/分隔线/侧栏底色）整块交给 HubChrome
		HubChrome.render(dc, textRenderer, frame, page, vanilla, this.width, this.height,
				HubChrome.headText(page), mouseX, mouseY);
		sidebar.render(dc, textRenderer, frame, page, mouseX, mouseY);

		// 内容区（切换页面时轻微滑入）
		var mat = dc.getMatrices();
		long since = System.currentTimeMillis() - pageAnimAt;
		float t = pageAnimAt == 0 ? 1f : Math.min(1f, since / 120f);
		int slide = (int) (6 * (1 - t));
		mat.push();
		mat.translate(slide, 0, 0);
		int cx = frame.contentX();
		int cy = frame.contentY();
		int cw = frame.contentW();
		int ch = frame.contentH();
		// 商店操作结果回显（买/卖成功或失败都进界面状态行，不进聊天）
		if (page == HubPage.SHOP) {
			if (ShopClientState.page() != lastShopPage) {
				lastShopPage = ShopClientState.page();
				shopSelected = -1;
				// 换页 / 换筛选后回到网格顶部：旧滚动位置在新一页上多半是空的
				HubShopPage.resetScroll();
			}
			var act = ShopClientState.lastAction();
			if (act != null && ShopClientState.lastActionAt() > lastShopActionAt) {
				lastShopActionAt = ShopClientState.lastActionAt();
				String base = act.ok() ? "已购买"
						: net.minecraft.text.Text.translatable(act.messageKey()).getString();
				if (act.ok()) base = lastTradeBuy ? "已购买" : "已出售";
				// T01：成功时补税额（失败不说税，免得看起来像被多收了）
				if (act.ok() && lastTradeAmount > 0) {
					long tax = lastTradeBuy
							? ShopClientState.tax().buyTax(lastTradeItem, lastTradeAmount)
							: ShopClientState.tax().sellTax(lastTradeItem, lastTradeAmount);
					if (tax > 0) base = base + "（" + (lastTradeBuy ? "含税 " : "扣税 ") + tax + " 铜）";
				}
				setStatus(base);
			}
		}
		if (page == HubPage.MARKET) {
			var mact = com.akira.redtale.economy.client.market.MarketClientNetworking.LAST_ACTION;
			long mAt = com.akira.redtale.economy.client.market.MarketClientNetworking.LAST_ACTION_AT;
			if (mact != null && mAt > lastMarketActionAt) {
				lastMarketActionAt = mAt;
				setStatus(mact.message());
			}
		}
		if (page == HubPage.SETTINGS) {
			HubSettingsPage.render(dc, textRenderer, cx, cy, cw, ch, mouseX, mouseY);
		} else if (page == HubPage.BOUNTY) {
			int[] hovered = {-1};
			HubBountyPage.render(dc, textRenderer, cx, cy, cw, ch, mouseX, mouseY, hovered);
		} else if (page == HubPage.BOARD) {
			HubBoardPage.render(dc, textRenderer, cx, cy, cw, ch, mouseX, mouseY);
		} else if (page == HubPage.LEDGER) {
			HubLedgerPage.render(dc, textRenderer, cx, cy, cw, ch, mouseX, mouseY);
		} else if (page == HubPage.GUILD) {
			HubGuildPage.render(dc, textRenderer, cx, cy, cw, ch, mouseX, mouseY);
		} else if (page == HubPage.MAIL) {
			HubMailPage.render(dc, textRenderer, cx, cy, cw, ch, mouseX, mouseY);
		} else if (page == HubPage.PAY) {
			HubPayPage.render(dc, textRenderer, cx, cy, cw, ch, payName.toString(),
					payAmount.toString(), payFocus, mouseX, mouseY);
		} else if (page == HubPage.TITLES) {
			HubTitlesPage.render(dc, textRenderer, cx, cy, cw, ch, mouseX, mouseY);
		} else if (page == HubPage.STORAGE) {
			HubStoragePage.render(dc, textRenderer, cx, cy, cw, ch,
					storageQuery.toString(), storageCat, storageScroll, mouseX, mouseY);
		} else if (page == HubPage.PASS) {
			HubPassPage.render(dc, textRenderer, cx, cy, cw, ch, passScroll, mouseX, mouseY);
		} else if (page == HubPage.RANK) {
			HubRankPage.setSyncCooldownSeconds(rankSyncCooldownLeft());
			HubRankPage.render(dc, textRenderer, cx, cy, cw, ch, rankBoard, rankActiveOnly,
					rankDropdown, mouseX, mouseY);
		} else if (page == HubPage.MARKET) {
			HubMarketPage.render(dc, textRenderer, cx, cy, cw, ch, mouseX, mouseY, marketSelected);
		} else if (page == HubPage.SHOP) {
			HubShopPage.render(dc, textRenderer, cx, cy, cw, ch, mouseX, mouseY,
					shopQuery.toString(), shopSelected);
		} else if (page == HubPage.CHECKIN) {
			HubCheckinPage.render(dc, textRenderer, cx, cy, cw, ch,
					com.akira.redtale.economy.client.checkin.CheckinClientNetworking.LAST,
					monthOffset, mouseX, mouseY);
		} else {
			renderPlaceholder(dc, cx, cy, cw);
		}

		mat.pop();

		// 搜索框统一重绘（外观由 HubChrome 保证两页一致）
		if (page == HubPage.SHOP || page == HubPage.STORAGE) {
			HubChrome.renderSearch(dc, textRenderer, frame, page,
					page == HubPage.SHOP ? shopQuery.toString() : storageQuery.toString(),
					searchFocused, vanilla);
		}

		// 底部：状态优先，空闲出 tip
		statusLine.render(dc, textRenderer, frame);

		// ★下拉最后画★，否则会被底栏/后面的元素盖住
		if (page == HubPage.RANK && HubRankPage.needsDropdownOverlay()) {
			int rx = frame.contentX();
			int ry = frame.contentY();
			HubRankPage.drawDropdown(dc, textRenderer, rx, ry, rankBoard,
					panelY + panelH - HubFrame.BOTTOM_BAR, mouseX, mouseY);
		}
		if (page == HubPage.STORAGE) {
			long handle = MinecraftClient.getInstance().getWindow().getHandle();
			boolean down0 = org.lwjgl.glfw.GLFW.glfwGetMouseButton(handle, 0)
					== org.lwjgl.glfw.GLFW.GLFW_PRESS;
			boolean down1 = org.lwjgl.glfw.GLFW.glfwGetMouseButton(handle, 1)
					== org.lwjgl.glfw.GLFW.GLFW_PRESS;
			if (!down0 && !down1) {
				dragActive = false;
				lastDragSlot = -1;
			}
		}
		if (page == HubPage.STORAGE && dragActive) {
			int cx2 = frame.contentX();
			int cy2 = frame.contentY();
			int cw2 = frame.contentW();
			int ch2 = frame.contentH();
			var rr = HubStoragePage.click(mouseX, mouseY, cx2, cy2, cw2, ch2,
					dragButton < 0 ? 0 : dragButton, storageScroll);
			if (rr.hit() == HubStoragePage.Hit.STORAGE && rr.index() >= 0
					&& !dragSlots.contains(rr.index())) {
				dragSlots.add(rr.index());
			}
		}
		if (page == HubPage.STORAGE && HubStoragePage.needsCatOverlay()) {
			int sx2 = frame.contentX();
			int sy2 = frame.contentY();
			int sw2 = frame.contentW();
			HubStoragePage.drawCatDropdown(dc, textRenderer, sx2, sy2, sw2, storageCat,
					mouseX, mouseY);
		}
		// ★光标物品永远在最上层★
		if (page == HubPage.STORAGE) {
			HubStoragePage.drawCursor(dc, textRenderer, mouseX, mouseY);
		}
		// ★侧边栏 tooltip 也在最后画★：以前在侧栏内部画，会被内容区/底栏盖住
		sidebar.drawPendingTooltip(dc, textRenderer, mouseX, mouseY);

		super.render(dc, mouseX, mouseY, delta);
	}

	/**
	 * 余额显示（§47.7）：装了 Numismatic 就画硬币图标 + 数量（金→银→铜，0 跳过）；
	 * 没装则用紧凑文字「12银30铜」。宽度不够时自动跳过后面币种。
	 */
	/** 称号：取 Styled Chat 的 display_name 首段（拿不到就空）。 */
	/** 玩家头像（用皮肤贴图的 8×8 脸部放大）。 */
	private void renderPlaceholder(DrawContext dc, int cx, int cy, int cw) {
		dc.fill(cx, cy, cx + cw, cy + 64, HubTheme.CARD);
		dc.drawText(textRenderer, Text.literal("这一页正在迁移到新界面"), cx + 8, cy + 10, HubTheme.TEXT, false);
		dc.drawText(textRenderer, Text.literal("命令仍然可用：/rt " + page.id), cx + 8, cy + 28, HubTheme.MUTED, false);
		dc.drawText(textRenderer, Text.literal("侧边栏 / 顶栏 / 底部提示已生效"), cx + 8, cy + 44, HubTheme.MUTED, false);
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
		if (page == HubPage.PAY && payFocus > 0 && !Character.isISOControl(chr)) {
			if (payFocus == 1 && payName.length() < 24
					&& (Character.isLetterOrDigit(chr) || chr == '_')) {
				payName.append(chr);
			} else if (payFocus == 2 && Character.isDigit(chr) && payAmount.length() < 12) {
				payAmount.append(chr);
			}
			return true;
		}
		if (page == HubPage.STORAGE && searchFocused && !Character.isISOControl(chr)) {
			storageQuery.append(chr);
			return true;
		}
		if (page == HubPage.SHOP && searchFocused && !Character.isISOControl(chr)) {
			shopQuery.append(chr);
			return true;
		}
		return super.charTyped(chr, modifiers);
	}

	@Override
	public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
		if (keyCode == 256 && searchFocused) { // 聚焦时 ESC 先失焦
			searchFocused = false;
			return true;
		}
		if (keyCode == 256) { // ESC 再清搜索，最后关界面
			if (page == HubPage.SHOP && shopQuery.length() > 0) {
				shopQuery.setLength(0);
				requestShopPage();
				return true;
			}
			if (page == HubPage.STORAGE && storageQuery.length() > 0) {
				storageQuery.setLength(0);
				return true;
			}
		}
		if (page == HubPage.PAY && keyCode == 259) {
			if (payFocus == 1 && payName.length() > 0) payName.setLength(payName.length() - 1);
			if (payFocus == 2 && payAmount.length() > 0) payAmount.setLength(payAmount.length() - 1);
			return true;
		}
		if (!searchFocused && page == HubPage.STORAGE && keyCode == 81) { // Q
			com.akira.redtale.economy.client.warehouse.WarehouseClientNetworking.dropCursor();
			return true;
		}
		if (!searchFocused && page == HubPage.STORAGE && keyCode >= 49 && keyCode <= 57) {
			var held = com.akira.redtale.economy.client.warehouse.WarehouseClientNetworking.LAST.cursor();
			if (held != null && !held.isEmpty()) {
				com.akira.redtale.economy.client.warehouse.WarehouseClientNetworking.hotbar(keyCode - 49);
				return true;
			}
		}
		if (searchFocused && page == HubPage.STORAGE && keyCode == 259 && storageQuery.length() > 0) {
			storageQuery.setLength(storageQuery.length() - 1);
			return true;
		}
		if (page == HubPage.SHOP && searchFocused) {
			if (keyCode == 259 && shopQuery.length() > 0) { // backspace
				shopQuery.setLength(shopQuery.length() - 1);
				return true;
			}
			if (keyCode == 257 || keyCode == 335) { // enter
				requestShopPage();
				shopSelected = -1;
				return true;
			}
		}
		return super.keyPressed(keyCode, scanCode, modifiers);
	}

	@Override
	public boolean mouseDragged(double mouseX, double mouseY, int button, double dx, double dy) {
		if (page == HubPage.STORAGE && dragPlacing) {
			int cx = frame.contentX();
			int cy = frame.contentY();
			int cw = frame.contentW();
			int ch = frame.contentH();
			var r = HubStoragePage.click(mouseX, mouseY, cx, cy, cw, ch,
					dragButton < 0 ? 0 : dragButton, storageScroll);
			if (r.index() < 0 || r.index() == lastDragSlot) return true;
			boolean shift = hasShiftDown();
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
				lastDragSlot = r.index();
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
				lastDragSlot = 1000 + r.index();
				return true;
			}
		}
		return super.mouseDragged(mouseX, mouseY, button, dx, dy);
	}

	@Override
	public boolean mouseReleased(double mouseX, double mouseY, int button) {
		// ★拖拽结束：一次性把经过的格子交给服务端平分★
		if (dragActive && pendingInv == false && !dragSlots.isEmpty()) {
			int[] arr = new int[dragSlots.size()];
			for (int i = 0; i < arr.length; i++) arr[i] = dragSlots.get(i);
			com.akira.redtale.economy.client.warehouse.WarehouseClientNetworking.spread(
					arr, pendingAll);
			dragSlots.clear();
			pendingIdx = -1;
		}
		// 持物按下后没有拖到别的格子 -> 视为普通点击，整叠/单个放下
		if (dragActive && pendingIdx >= 0 && lastDragSlot == pressKey) {
			if (pendingInv) {
				com.akira.redtale.economy.client.warehouse.WarehouseClientNetworking.localPlaceToInv(
						pendingIdx, !pendingAll);
				com.akira.redtale.economy.client.warehouse.WarehouseClientNetworking.placeToInv(
						pendingIdx, !pendingAll);
			} else {
				com.akira.redtale.economy.client.warehouse.WarehouseClientNetworking.localPlace(
						pendingIdx, !pendingAll);
				com.akira.redtale.economy.client.warehouse.WarehouseClientNetworking.place(
						pendingIdx, !pendingAll);
			}
		}
		pendingIdx = -1;
		dragPlacing = false;
		dragActive = false;
		dragButton = -1;
		lastDragSlot = -1;
		return super.mouseReleased(mouseX, mouseY, button);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
		if (page == HubPage.SHOP && mouseX > panelX + sidebarW()) {
			// T44：427×240 下一页 40 条只放得下 4 张卡 ⇒ 必须能滚，否则中间 36 条翻页也翻不到
			int cw = frame.contentW();
			int ch = frame.contentH();
			int cy = frame.contentY();
			HubShopPage.scrollByRows(-(int) Math.signum(amount), cw, cy, ch);
			return true;
		}
		if (page == HubPage.BOARD && mouseX > panelX + sidebarW()) {
			// T12：低分辨率下一页只放得下 2~3 张卡，必须能滚
			HubBoardPage.scrollByRows(-(int) Math.signum(amount), frame.contentH());
			return true;
		}
		if (page == HubPage.LEDGER && mouseX > panelX + sidebarW()) {
			// T16：低分辨率下一页只放得下五六行，必须能滚
			HubLedgerPage.scrollByRows(-(int) Math.signum(amount), frame.contentH());
			return true;
		}
		if (page == HubPage.STORAGE && mouseX > panelX + sidebarW()) {
			storageScroll = Math.max(0, Math.min(
					com.akira.redtale.economy.client.warehouse.WarehouseClientNetworking.SLOT_COUNT,
					storageScroll - (int) Math.signum(amount)));
			return true;
		}
		if (page == HubPage.SETTINGS && mouseX > panelX + sidebarW()) {
			// U10：Scale 2 下设置页内容区装不下全部行（管理员区会被裁掉），必须能滚
			int ch = frame.contentH();
			if (HubSettingsPage.maxScroll(ch) > 0) {
				HubSettingsPage.scrollBy(-(int) Math.signum(amount) * 20);
				return true;
			}
		}
		if (page == HubPage.PASS && mouseX > panelX + sidebarW()) {
			int cw = frame.contentW();
			int max = HubPassPage.maxScroll(cw);
			if (max > 0) {
				passScroll = Math.max(0, Math.min(max, passScroll - (int) Math.signum(amount)));
				return true;
			}
		}
		if (mouseX >= panelX && mouseX < panelX + sidebarW()) {
			if (frame.maxScroll() > 0) {
				sidebar.scrollBy(frame, amount);
				return true;
			}
		}
		return super.mouseScrolled(mouseX, mouseY, amount);
	}
}
