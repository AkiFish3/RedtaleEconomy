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


	private static final int ROW_H = 24;
	private static final int TOP_BAR = 24;
	private static final int BOTTOM_BAR = 20;
	private static final int PROFILE_H = 56;

	private boolean autoCompact;

	private boolean isVanilla() {
		return "vanilla".equals(ClientUiConfig.theme);
	}

	private int sidebarW() {
		return (ClientUiConfig.showLabels && !autoCompact) ? 88 : 40;
	}


	private HubPage page = HubPage.CHECKIN;
	private int sidebarScroll;
	private int panelX;
	private int panelY;
	private int panelW;
	private int panelH;

	private String status = "";
	private long statusUntil;
	private int monthOffset;
	private final StringBuilder shopQuery = new StringBuilder();
	private int shopSelected = -1;
	/** R40：市场页选中的行（-1 = 未选） */
	private int marketSelected = -1;
	private boolean shopRequested;
	private int passScroll;
	private final StringBuilder storageQuery = new StringBuilder();
	private int storageCat;
	private int storageScroll;
	private int lastSlotKey = -1;
	private long lastSlotAt;
	/** U11b：点击筛选 chip → 循环下一个取值（""/"全部" 也算一档，循环回得去）。 */
	private void cycleFilter(int slot) {
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

	private boolean dragPlacing;
	private int dragButton = -1;
	private boolean searchFocused;
	private long pageAnimAt;
	private long shownRaw = -1;
	private long rawTarget = -1;
	private long rawAnimAt;
	private long rippleAt;
	private int rippleRow = -1;
	private boolean dragActive;
	private int pendingIdx = -1;
	private boolean pendingInv;
	private boolean pendingAll;
	private int pressKey = -9999;
	private final java.util.List<Integer> dragSlots = new java.util.ArrayList<>();
	private double rippleX, rippleY;
	private long lastShopActionAt;
	private long lastMarketActionAt;
	private Object lastShopPage;
	private int lastDragSlot = -1;
	private int rankBoard;
	private boolean rankActiveOnly;
	private boolean rankDropdown;
	private int tipIndex;
	private long tipSwitchAt;

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
	}

	/** U8b：/rt 切页时复用当前界面，别把搜索词和滚动位置清掉。 */
	public void setPage(HubPage p) {
		if (p == null || p == page) return;
		this.page = p;
		this.status = "";
		this.statusUntil = 0;
		this.searchFocused = false;
		this.rankDropdown = false;
		// 切页顺手拉一次数据（服务端只回快照，不做写操作）
		var mc = MinecraftClient.getInstance();
		if (mc.player != null) {
			switch (p) {
				case SHOP -> {
					String sid = ShopClientState.shopIdToRequest();
					if (!sid.isEmpty()) ShopClientNetworking.requestPage(sid, 0, "");
				}
				case RANK -> com.akira.redtale.economy.client.rank.RankClientNetworking.request(rankActiveOnly);
				case MARKET -> com.akira.redtale.economy.client.market.MarketClientNetworking.request();
				case CHECKIN -> mc.player.networkHandler.sendChatCommand("checkin status");
				default -> {
				}
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
		tipSwitchAt = System.currentTimeMillis() + Tips.intervalSeconds() * 1000L;
		// 只查询状态，不会触发签到（服务端 /checkin status 会下发快照）
		var mc = MinecraftClient.getInstance();
		if (mc.player != null) {
			mc.player.networkHandler.sendChatCommand("checkin status");
			com.akira.redtale.economy.client.balance.BalanceClientNetworking.request();
			com.akira.redtale.economy.client.warehouse.WarehouseClientNetworking.request();
		}
		// 直接以商店页开场时（/rt shop、S2C_OPEN 打开）必须自己拉一次页，
		// 否则只有侧边栏点击才会请求，页面永远停在上一份空快照。
		if (page == HubPage.SHOP && !shopRequested) requestShopPage();
	}

	private int entryTop() {
		return panelY + TOP_BAR + PROFILE_H;
	}

	private int entryBottom() {
		return panelY + panelH - BOTTOM_BAR;
	}

	private int maxScroll() {
		int visible = entryBottom() - entryTop();
		int total = HubPage.values().length * ROW_H;
		return Math.max(0, total - visible);
	}

	@Override
	public void render(DrawContext dc, int mouseX, int mouseY, float delta) {
		boolean vanilla = isVanilla();
		if (vanilla) {
			HubVanilla.background(dc, this.width, this.height);
			HubVanilla.panel(dc, panelX, panelY, panelW, panelH);
		} else {
			this.renderBackground(dc);
			dc.fill(panelX - 1, panelY - 1, panelX + panelW + 1, panelY + panelH + 1, HubTheme.LINE);
			dc.fill(panelX, panelY, panelX + panelW, panelY + panelH, HubTheme.PANEL);
		}

		// 顶部：当前页名（左）；商店页带上店名，否则只有「商店」两个字看不出是哪家
		String head = page.label;
		if (page == HubPage.SHOP) {
			ShopProtocol.Page sp = ShopClientState.page();
			String sn = sp == null ? "" : sp.shopName();
			if (sn != null && !sn.isBlank()) head = page.label + " · " + sn;
		}
		dc.drawText(textRenderer, Text.literal(head), panelX + 10, panelY + 8, 0xFFF0E6D2, false);

		// 顶部：状态（右）—— §37.3「标题左、状态右，不随内容滚动」
		// 窄面板/紧凑模式下会挤掉标题，所以只在够宽时画。
		long bal = com.akira.redtale.economy.client.balance.BalanceClientNetworking.RAW;
		String balText = bal < 0 ? "" : com.akira.redtale.economy.economy.Currency.format(bal);
		if (!balText.isEmpty()) {
			int bw = textRenderer.getWidth(balText);
			int bx = panelX + panelW - 24 - bw;
			int titleEnd = panelX + 12 + textRenderer.getWidth(head);
			if (bx > titleEnd + 8) {
				dc.drawText(textRenderer, Text.literal(balText), bx, panelY + 8, HubTheme.GOLD, false);
			}
		}

		// 右上角关闭
		int closeSize = 12;
		int closeX = panelX + panelW - closeSize - 4;
		int closeY = panelY + 9;
		boolean closeHover = mouseX >= closeX - 2 && mouseX < closeX + closeSize + 2
				&& mouseY >= closeY - 2 && mouseY < closeY + closeSize + 2;
		if (vanilla) {
			HubVanilla.button(dc, closeX - 3, closeY - 3, closeSize + 6, 20, closeHover, false);
		} else {
			dc.fill(closeX - 2, closeY - 2, closeX + closeSize + 2, closeY + closeSize + 2,
					closeHover ? 0xFF7A3A3A : 0xFF000000);
		}
		dc.drawText(textRenderer, Text.literal("×"), closeX + 2, closeY, 0xFFE0E0E0, false);

		// ★顶栏下 / 底栏上 / 侧边栏右 三条分隔线（三主题通用）★
		dc.fill(panelX, panelY + TOP_BAR - 1, panelX + panelW, panelY + TOP_BAR, HubTheme.OUTLINE);
		dc.fill(panelX, panelY + panelH - BOTTOM_BAR, panelX + panelW,
				panelY + panelH - BOTTOM_BAR + 1, HubTheme.OUTLINE);
		dc.fill(panelX + sidebarW() - 1, panelY + TOP_BAR, panelX + sidebarW(),
				panelY + panelH - BOTTOM_BAR, HubTheme.OUTLINE);

		// 侧边栏
		if (!vanilla) {
			dc.fill(panelX, panelY + TOP_BAR, panelX + sidebarW(), panelY + panelH - BOTTOM_BAR, HubTheme.BAR);
		}
		dc.fill(panelX + sidebarW(), panelY + TOP_BAR, panelX + sidebarW() + 1,
				panelY + panelH - BOTTOM_BAR, HubTheme.LINE);
		renderProfile(dc, mouseX, mouseY);
		renderEntries(dc, mouseX, mouseY);

		// 内容区（切换页面时轻微滑入）
		var mat = dc.getMatrices();
		long since = System.currentTimeMillis() - pageAnimAt;
		float t = pageAnimAt == 0 ? 1f : Math.min(1f, since / 120f);
		int slide = (int) (6 * (1 - t));
		mat.push();
		mat.translate(slide, 0, 0);
		int cx = panelX + sidebarW() + 12;
		int cy = panelY + TOP_BAR + 8;
		int cw = panelW - sidebarW() - 24;
		int ch = panelH - TOP_BAR - BOTTOM_BAR - 16;
		// 商店操作结果回显（买/卖成功或失败都进界面状态行，不进聊天）
		if (page == HubPage.SHOP) {
			if (ShopClientState.page() != lastShopPage) {
				lastShopPage = ShopClientState.page();
				shopSelected = -1;
			}
			var act = ShopClientState.lastAction();
			if (act != null && ShopClientState.lastActionAt() > lastShopActionAt) {
				lastShopActionAt = ShopClientState.lastActionAt();
				setStatus(act.ok() ? "已购买" : net.minecraft.text.Text.translatable(act.messageKey()).getString());
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
		} else if (page == HubPage.GUILD) {
			HubGuildPage.render(dc, textRenderer, cx, cy, cw, ch, mouseX, mouseY);
		} else if (page == HubPage.MAIL) {
			HubMailPage.render(dc, textRenderer, cx, cy, cw, ch, mouseX, mouseY);
		} else if (page == HubPage.STORAGE) {
			HubStoragePage.render(dc, textRenderer, cx, cy, cw, ch,
					storageQuery.toString(), storageCat, storageScroll, mouseX, mouseY);
		} else if (page == HubPage.PASS) {
			HubPassPage.render(dc, textRenderer, cx, cy, cw, ch, passScroll, mouseX, mouseY);
		} else if (page == HubPage.RANK) {
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

		// 搜索框统一重绘：聚焦时显示光标并隐藏占位文字
		if (page == HubPage.SHOP || page == HubPage.STORAGE) {
			int bx = panelX + sidebarW() + 12;
			int by = panelY + TOP_BAR + 8 + (page == HubPage.SHOP ? 0 : 16);
			int bw = panelW - sidebarW() - 24 - (page == HubPage.SHOP ? 40 : 92);
			int bh = page == HubPage.SHOP ? 18 : 16;
			String q = page == HubPage.SHOP ? shopQuery.toString() : storageQuery.toString();
			if (isVanilla()) {
				HubVanilla.field(dc, bx, by, bw, bh, searchFocused);
			} else {
				dc.fill(bx, by, bx + bw, by + bh, searchFocused ? 0xCC000000 : 0x99000000);
				dc.fill(bx, by, bx + bw, by + 1, searchFocused ? HubTheme.GOLD : HubTheme.LINE);
				dc.fill(bx, by + bh - 1, bx + bw, by + bh, HubTheme.LINE);
			}
			if (searchFocused) {
				dc.drawText(textRenderer, Text.literal(q), bx + 4, by + (bh - 8) / 2,
						isVanilla() ? 0xFFFFFFFF : HubTheme.TEXT, false);
				boolean blink = (System.currentTimeMillis() / 500) % 2 == 0;
				if (blink) {
					int cx2 = bx + 4 + textRenderer.getWidth(q);
					dc.fill(cx2, by + 3, cx2 + 1, by + bh - 3,
							isVanilla() ? 0xFFFFFFFF : HubTheme.GOLD);
				}
			}
		}


		// 底部：状态优先，空闲出 tip
		renderBottom(dc);

		// ★下拉最后画★，否则会被底栏/后面的元素盖住
		if (page == HubPage.RANK && HubRankPage.needsDropdownOverlay()) {
			int rx = panelX + sidebarW() + 12;
			int ry = panelY + TOP_BAR + 8;
			HubRankPage.drawDropdown(dc, textRenderer, rx, ry, rankBoard,
					panelY + panelH - BOTTOM_BAR, mouseX, mouseY);
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
			int cx2 = panelX + sidebarW() + 12;
			int cy2 = panelY + TOP_BAR + 8;
			int cw2 = panelW - sidebarW() - 24;
			int ch2 = panelH - TOP_BAR - BOTTOM_BAR - 16;
			var rr = HubStoragePage.click(mouseX, mouseY, cx2, cy2, cw2, ch2,
					dragButton < 0 ? 0 : dragButton, storageScroll);
			if (rr.hit() == HubStoragePage.Hit.STORAGE && rr.index() >= 0
					&& !dragSlots.contains(rr.index())) {
				dragSlots.add(rr.index());
			}
		}
		if (page == HubPage.STORAGE && HubStoragePage.needsCatOverlay()) {
			int sx2 = panelX + sidebarW() + 12;
			int sy2 = panelY + TOP_BAR + 8;
			int sw2 = panelW - sidebarW() - 24;
			HubStoragePage.drawCatDropdown(dc, textRenderer, sx2, sy2, sw2, storageCat,
					mouseX, mouseY);
		}
		// ★光标物品永远在最上层★
		if (page == HubPage.STORAGE) {
			HubStoragePage.drawCursor(dc, textRenderer, mouseX, mouseY);
		}

		super.render(dc, mouseX, mouseY, delta);
	}

	private void renderProfile(DrawContext dc, int mouseX, int mouseY) {
		int x = panelX;
		int y = panelY + TOP_BAR;
		dc.fill(x, y, x + sidebarW(), y + PROFILE_H,
				isVanilla() ? 0xFFC6C6C6 : 0xFF1B1B1B);
		boolean withText = ClientUiConfig.showLabels && !autoCompact;
		int headSize = withText ? 32 : 26;
		int headX = withText ? x + 6 : x + (sidebarW() - headSize) / 2;
		int headY = withText ? y + 6 : y + 8;
		dc.fill(headX - 1, headY - 1, headX + headSize + 1, headY + headSize + 1,
				isVanilla() ? 0xFF8B8B8B : 0xFF3B3226);
		drawHead(dc, headX, headY, headSize);
		if (withText) {
			MinecraftClient mc = MinecraftClient.getInstance();
			String name = mc.player == null ? "—" : mc.player.getGameProfile().getName();
			dc.drawText(textRenderer, Text.literal(trim(name, sidebarW() - 44)), x + 42, y + 8, HubTheme.TEXT, false);
			long raw = com.akira.redtale.economy.client.balance.BalanceClientNetworking.RAW;
			// 余额变化时做 250ms 数字滚动（l1ght 的 animatedBalance 同款手感）
			long now = System.currentTimeMillis();
			if (raw != rawTarget) {
				rawTarget = raw;
				if (shownRaw < 0) shownRaw = raw;
				rawAnimAt = now;
			}
			long shown = rawTarget;
			if (shownRaw >= 0 && now - rawAnimAt < 250) {
				float k = (now - rawAnimAt) / 250f;
				shown = shownRaw + (long) ((rawTarget - shownRaw) * k);
			} else if (shownRaw >= 0) {
				shownRaw = rawTarget;
			}
			drawCoins(dc, x + 4, y + 40, sidebarW() - 8, shown);
		}
		boolean hoverHead = withText
				? false
				: (mouseX >= headX && mouseX < headX + headSize
						&& mouseY >= headY && mouseY < headY + headSize);
		if (hoverHead) {
			MinecraftClient mc2 = MinecraftClient.getInstance();
			String nm = mc2.player == null ? "—" : mc2.player.getGameProfile().getName();
			long raw2 = com.akira.redtale.economy.client.balance.BalanceClientNetworking.RAW;
			String money2 = raw2 < 0 ? "…" : com.akira.redtale.economy.economy.Currency.format(raw2);
			dc.drawTooltip(textRenderer,
					java.util.List.of(Text.literal(nm), Text.literal(money2)), mouseX, mouseY);
		}
		dc.fill(x, y, x + sidebarW(), y + 1, HubTheme.LINE);              // 顶边
		dc.fill(x, y, x + 1, y + PROFILE_H, HubTheme.LINE);              // 左边
		dc.fill(x, y + PROFILE_H - 1, x + sidebarW(), y + PROFILE_H, HubTheme.LINE); // 底边
	}

	/**
	 * 余额显示（§47.7）：装了 Numismatic 就画硬币图标 + 数量（金→银→铜，0 跳过）；
	 * 没装则用紧凑文字「12银30铜」。宽度不够时自动跳过后面币种。
	 */
	private void drawCoins(DrawContext dc, int x, int y, int w, long raw) {
		if (raw < 0) {
			dc.drawText(textRenderer, Text.literal("…"), x, y, HubTheme.TEXT, false);
			return;
		}
		long gc = Math.max(1, com.akira.redtale.economy.economy.Currency.goldInCopper());
		long sc = Math.max(1, com.akira.redtale.economy.economy.Currency.silverInCopper());
		long gold = raw / gc;
		long rest = raw % gc;
		long silver = rest / sc;
		long copper = rest % sc;

		boolean numis = net.fabricmc.loader.api.FabricLoader.getInstance()
				.isModLoaded("numismatic-overhaul");
		if (!numis) {
			String txt = (gold > 0 ? gold + "金" : "") + (silver > 0 ? silver + "银" : "")
					+ (copper > 0 || (gold == 0 && silver == 0) ? copper + "铜" : "");
			dc.drawText(textRenderer, Text.literal(trim(txt, w)), x, y, HubTheme.TEXT, false);
			return;
		}
		if (gold == 0 && silver == 0 && copper == 0) {
			coin(dc, x, y, "numismatic-overhaul:bronze_coin", 0, x + w, true);
			return;
		}
		int right = x + w;
		int cx = coin(dc, x, y, "numismatic-overhaul:gold_coin", gold, right, false);
		cx = coin(dc, cx, y, "numismatic-overhaul:silver_coin", silver, right, false);
		coin(dc, cx, y, "numismatic-overhaul:bronze_coin", copper, right, false);
	}

	private int coin(DrawContext dc, int x, int y, String itemId, long value, int right, boolean force) {
		if (value <= 0 && !force) return x;
		String n = value >= 10000 ? (value / 1000) + "k" : String.valueOf(value);
		int needW = 17 + textRenderer.getWidth(n);
		// right 是**绝对坐标**的右边界（之前误把它当宽度，导致永远画不出来）
		if (x + needW > right) return x;
		try {
			var item = net.minecraft.registry.Registries.ITEM.get(
					new net.minecraft.util.Identifier(itemId));
			if (item != null && item != net.minecraft.item.Items.AIR) {
				dc.drawItem(new net.minecraft.item.ItemStack(item), x, y - 4);
			}
		} catch (Exception ignored) {
			// 图标缺失只影响观感
		}
		dc.drawText(textRenderer, Text.literal(n), x + 17, y, HubTheme.TEXT, false);
		return x + needW + 2;
	}

	/** 称号：取 Styled Chat 的 display_name 首段（拿不到就空）。 */
	private String titleText() {
		try {
			MinecraftClient mc = MinecraftClient.getInstance();
			if (mc.player == null) return "";
			String dn = mc.player.getDisplayName().getString();
			int i = dn.indexOf(']');
			return i > 0 && dn.startsWith("[") ? dn.substring(0, i + 1) : "";
		} catch (Exception ex) {
			return "";
		}
	}

	/** 玩家头像（用皮肤贴图的 8×8 脸部放大）。 */
	private void drawHead(DrawContext dc, int x, int y, int size) {
		try {
			MinecraftClient mc = MinecraftClient.getInstance();
			if (mc.player == null) throw new IllegalStateException();
			var tex = mc.getSkinProvider().loadSkin(mc.player.getGameProfile());
			var m = dc.getMatrices();
			m.push();
			m.translate(x, y, 0);
			float k = size / 8f;
			m.scale(k, k, 1f);
			dc.drawTexture(tex, 0, 0, 8, 8, 8, 8, 64, 64);
			// 外层（帽子）叠在脸上，位置 40,8
			dc.drawTexture(tex, 0, 0, 40, 8, 8, 8, 64, 64);
			m.pop();
		} catch (Exception ex) {
			dc.fill(x, y, x + size, y + size, 0xFF3B3226);
		}
	}

	private void drawIcon(DrawContext dc, String itemId, int x, int y) {
		try {
			var item = net.minecraft.registry.Registries.ITEM.get(new net.minecraft.util.Identifier(itemId));
			if (item != null && item != net.minecraft.item.Items.AIR) {
				dc.drawItem(new net.minecraft.item.ItemStack(item), x, y);
			}
		} catch (Exception ignored) {
			// 图标缺失不影响文字
		}
	}

	private void renderEntries(DrawContext dc, int mouseX, int mouseY) {
		int top = entryTop();
		int bottom = entryBottom();
		// ★裁剪★：不让滚动时条目画到顶栏/底栏上
		boolean clip = bottom > top;
		if (clip) dc.enableScissor(panelX, top, panelX + sidebarW(), bottom);
		int y = top - sidebarScroll;
		for (HubPage p : HubPage.values()) {
			if (y + ROW_H > top && y < bottom) {
				boolean sel = p == page;
				boolean hovered = mouseX >= panelX && mouseX < panelX + sidebarW()
						&& mouseY >= y && mouseY < y + ROW_H;
				if (isVanilla()) {
					if (sel || hovered) {
						HubVanilla.button(dc, panelX + 2, y + 2, sidebarW() - 4, 20, hovered, false);
					}
				} else if (sel) {
					HubWidgets.roundRect(dc, panelX + 2, y + 2, sidebarW() - 4, ROW_H - 4, 2, HubTheme.SELECT);
					HubWidgets.indicator(dc, panelX + 2, y + 2, ROW_H - 4);
				} else if (hovered) {
					HubWidgets.roundRect(dc, panelX + 2, y + 2, sidebarW() - 4, ROW_H - 4, 2, HubTheme.HOVER);
				}
				int color = sel ? 0xFFFFE9A8 : (hovered ? 0xFFFFFFFF : HubTheme.TEXT);
				drawIcon(dc, p.icon, panelX + (sidebarW() > 44 ? 8 : 11), y + 5);
				if (sidebarW() > 44) {
					dc.drawText(textRenderer, Text.literal(p.label), panelX + 28, y + 8, color, false);
				} else if (mouseX >= panelX && mouseX < panelX + sidebarW()
						&& mouseY >= y && mouseY < y + ROW_H) {
					dc.drawTooltip(textRenderer, java.util.List.of(Text.literal(p.label)), mouseX, mouseY);
				}
			}
			y += ROW_H;
		}
		// 溢出时画细滚动条
		int ms = maxScroll();
		if (ms > 0) {
			int barH = Math.max(12, (bottom - top) * (bottom - top) / (HubPage.values().length * ROW_H));
			int barY = top + (bottom - top - barH) * sidebarScroll / ms;
			dc.fill(panelX + sidebarW() - 3, top, panelX + sidebarW() - 1, bottom, 0xFF333333);
			dc.fill(panelX + sidebarW() - 3, barY, panelX + sidebarW() - 1, barY + barH, 0xFF8A8A8A);
		}
		if (rippleRow >= 0) {
			long dt = System.currentTimeMillis() - rippleAt;
			if (dt < 260) {
				int ry = entryTop() - sidebarScroll + rippleRow * ROW_H;
				HubWidgets.ripple(dc, panelX + 2, ry + 2, sidebarW() - 4, ROW_H - 4,
						rippleX, rippleY, dt / 260f, 0xFFFFFF);
			} else {
				rippleRow = -1;
			}
		}
		if (clip) dc.disableScissor();
	}

	private void renderPlaceholder(DrawContext dc, int cx, int cy, int cw) {
		dc.fill(cx, cy, cx + cw, cy + 64, HubTheme.CARD);
		dc.drawText(textRenderer, Text.literal("这一页正在迁移到新界面"), cx + 8, cy + 10, HubTheme.TEXT, false);
		dc.drawText(textRenderer, Text.literal("命令仍然可用：/rt " + page.id), cx + 8, cy + 28, HubTheme.MUTED, false);
		dc.drawText(textRenderer, Text.literal("侧边栏 / 顶栏 / 底部提示已生效"), cx + 8, cy + 44, HubTheme.MUTED, false);
	}

	private void renderSettings(DrawContext dc, int cx, int cy, int cw) {
		String[][] rows = {
				{"外观", "界面主题", "原版"},
				{"外观", "按钮音效", "开"},
				{"界面", "侧边栏显示文字", "开"},
				{"界面", "界面缩放", "跟随原版"},
				{"语言", "显示语言", "简体中文"},
				{"按键", "打开主界面", "未设置"},
				{"高级", "调试信息", "收起"},
		};
		String lastGroup = "";
		int y = cy;
		for (String[] r : rows) {
			if (!r[0].equals(lastGroup)) {
				lastGroup = r[0];
				dc.drawText(textRenderer, Text.literal(r[0]), cx, y, 0xFF6A6A6A, false);
				y += 14;
			}
			dc.fill(cx, y, cx + cw, y + 18, HubTheme.CARD);
			dc.drawText(textRenderer, Text.literal(r[1]), cx + 6, y + 5, HubTheme.TEXT, false);
			int w = textRenderer.getWidth(r[2]);
			dc.drawText(textRenderer, Text.literal(r[2]), cx + cw - 6 - w, y + 5, HubTheme.MUTED, false);
			y += 20;
			if (y > panelY + panelH - BOTTOM_BAR - 30) break;
		}
	}

	private void renderBottom(DrawContext dc) {
		int y = panelY + panelH - BOTTOM_BAR;
		if (!isVanilla()) {
			dc.fill(panelX, y, panelX + panelW, panelY + panelH, 0xFF1B1B1B);
			dc.fill(panelX, y, panelX + panelW, y + 1, HubTheme.LINE);
		}

		long now = System.currentTimeMillis();
		boolean showStatus = now < statusUntil && !status.isEmpty();
		String text;
		int color;
		if (showStatus) {
			text = status;
			color = HubTheme.MUTED;
		} else if (!ClientUiConfig.tipsEnabled) {
			return;
		} else {
			if (now > tipSwitchAt) {
				tipIndex++;
				tipSwitchAt = now + Tips.intervalSeconds() * 1000L;
			}
			Tips.Tip tip = Tips.at(tipIndex);
			text = tip.text();
			color = tip.ad() ? 0xFFC9A227 : HubTheme.MUTED;
		}
		if (!text.isEmpty()) {
			int maxW = panelW - 20;
			dc.drawText(textRenderer, Text.literal(trim(text, maxW)), panelX + 10, y + 6, color, false);
		}
	}

	private String trim(String s, int maxWidth) {
		if (textRenderer.getWidth(s) <= maxWidth) return s;
		String out = s;
		while (out.length() > 1 && textRenderer.getWidth(out + "…") > maxWidth) {
			out = out.substring(0, out.length() - 1);
		}
		return out + "…";
	}

	/** 供页面调用的状态提示（§38：界面内提示，不进聊天）。 */
	public void setStatus(String text) {
		this.status = text == null ? "" : text;
		this.statusUntil = System.currentTimeMillis() + 5000L;
	}

	/**
	 * R39/U10：所有点击统一走这里 —— 命中（返回 true）才播一次按钮音。
	 *
	 * <p>音效开关由「设置 → 按钮音效」控制（{@link ClientUiConfig#click()} 内部判 silent）。
	 * 之前 Hub 内点击完全没接音效，silent 与 vanilla 听不出区别。
	 */
	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		boolean handled = handleClick(mouseX, mouseY, button);
		if (handled && button == 0) ClientUiConfig.click();
		return handled;
	}

	private boolean handleClick(double mouseX, double mouseY, int button) {
		// ★确认条在搜索框上方★：必须先判它，否则点"确认"会变成聚焦搜索框
		if (page == HubPage.STORAGE && HubStoragePage.confirmUpgrade) {
			int cx = panelX + sidebarW() + 12;
			int cy = panelY + TOP_BAR + 8;
			int cw = panelW - sidebarW() - 24;
			int ch = panelH - TOP_BAR - BOTTOM_BAR - 16;
			var r0 = HubStoragePage.click(mouseX, mouseY, cx, cy, cw, ch, button, storageScroll);
			if (r0.hit() == HubStoragePage.Hit.CONFIRM_YES) {
				HubStoragePage.confirmUpgrade = false;
				com.akira.redtale.economy.client.warehouse.WarehouseClientNetworking.upgrade();
				setStatus("正在扩容…");
				return true;
			}
			if (r0.hit() == HubStoragePage.Hit.CONFIRM_NO) {
				HubStoragePage.confirmUpgrade = false;
				return true;
			}
		}
		if (inSearchBox(mouseX, mouseY)) {
			searchFocused = true;
			return true;
		}
		searchFocused = false;
		int cs = 12;
		double cx2 = panelX + panelW - cs - 4;
		double cy2 = panelY + 9;
		if (button == 0 && mouseX >= cx2 - 2 && mouseX < cx2 + cs + 2
				&& mouseY >= cy2 - 2 && mouseY < cy2 + cs + 2) {
			this.close();
			return true;
		}
		if (button == 0 && mouseX >= panelX && mouseX < panelX + sidebarW()) {
			int idx = ((int) mouseY - entryTop() + sidebarScroll) / ROW_H;
			HubPage[] all = HubPage.values();
			if (idx >= 0 && idx < all.length) {
				if (page != all[idx]) ClientUiConfig.click();
				searchFocused = false;
				if (page == HubPage.STORAGE && all[idx] != HubPage.STORAGE) {
					HubStoragePage.confirmUpgrade = false;
					com.akira.redtale.economy.client.warehouse.WarehouseClientNetworking.dropCursor();
				}
				page = all[idx];
				if (page == HubPage.SHOP) requestShopPage();
				if (page == HubPage.MARKET) com.akira.redtale.economy.client.market.MarketClientNetworking.request();
				if (page == HubPage.BOUNTY) requestBounty();
				if (page == HubPage.PASS) requestPass();
				if (page == HubPage.GUILD) requestGuild();
				if (page == HubPage.STORAGE) {
					com.akira.redtale.economy.client.warehouse.WarehouseClientNetworking.request();
				}
				return true;
			}
		}
		// R40 市场：动作全部先改客户端视图状态，再发请求；服务端回包覆盖
		if (button == 0 && page == HubPage.MARKET) {
			int cx = panelX + sidebarW() + 12;
			int cy = panelY + TOP_BAR + 8;
			int cw = panelW - sidebarW() - 24;
			int ch = panelH - TOP_BAR - BOTTOM_BAR - 16;
			int[] idx = {-1};
			HubMarketPage.Action a = HubMarketPage.click(textRenderer, mouseX, mouseY, cx, cy, cw, ch,
					marketSelected, idx);
			var snap = com.akira.redtale.economy.client.market.MarketClientNetworking.LAST;
			switch (a) {
				case TOGGLE_MINE -> {
					com.akira.redtale.economy.client.market.MarketClientNetworking.request(0,
							!snap.mineOnly(), com.akira.redtale.economy.client.market.MarketClientNetworking.query);
					marketSelected = -1;
				}
				case SYNC -> {
					com.akira.redtale.economy.client.market.MarketClientNetworking.request();
					setStatus("正在刷新市场…");
				}
				case CYCLE_PRICE -> HubMarketPage.cyclePrice();
				case SELL -> {
					com.akira.redtale.economy.client.market.MarketClientNetworking.sell(HubMarketPage.price());
					setStatus("正在挂单…");
				}
				case PREV -> {
					com.akira.redtale.economy.client.market.MarketClientNetworking.request(
							Math.max(0, snap.page() - 1), snap.mineOnly(),
							com.akira.redtale.economy.client.market.MarketClientNetworking.query);
					marketSelected = -1;
				}
				case NEXT -> {
					com.akira.redtale.economy.client.market.MarketClientNetworking.request(
							snap.page() + 1, snap.mineOnly(),
							com.akira.redtale.economy.client.market.MarketClientNetworking.query);
					marketSelected = -1;
				}
				case BUY -> {
					if (marketSelected >= 0 && marketSelected < snap.offers().size()) {
						com.akira.redtale.economy.client.market.MarketClientNetworking.buy(
								snap.offers().get(marketSelected).id());
						setStatus("正在购买…");
					}
				}
				case CANCEL -> {
					if (marketSelected >= 0 && marketSelected < snap.offers().size()) {
						com.akira.redtale.economy.client.market.MarketClientNetworking.cancel(
								snap.offers().get(marketSelected).id());
						setStatus("正在撤单…");
					}
				}
				default -> {
					if (idx[0] >= 0) marketSelected = idx[0];
				}
			}
			ClientUiConfig.click();
			return true;
		}
		if (button == 0 && page == HubPage.MAIL) {
			int cx = panelX + sidebarW() + 12;
			int cy = panelY + TOP_BAR + 8;
			int cw = panelW - sidebarW() - 24;
			int ch = panelH - TOP_BAR - BOTTOM_BAR - 16;
			HubMailPage.Action a = HubMailPage.click(mouseX, mouseY, cx, cy, cw, ch);
			var mc = MinecraftClient.getInstance();
			if (a == HubMailPage.Action.OPEN_MAIL) {
				if (mc.player != null) mc.player.networkHandler.sendChatCommand("mail");
				return true;
			}
			if (a == HubMailPage.Action.CLAIM) {
				if (mc.player != null) mc.player.networkHandler.sendChatCommand("mail claim");
				setStatus("正在领取邮件附件…");
				return true;
			}
		}
		if ((button == 0 || button == 1) && page == HubPage.STORAGE) {
			int cx = panelX + sidebarW() + 12;
			int cy = panelY + TOP_BAR + 8;
			int cw = panelW - sidebarW() - 24;
			int ch = panelH - TOP_BAR - BOTTOM_BAR - 16;
			var r = HubStoragePage.click(mouseX, mouseY, cx, cy, cw, ch, button, storageScroll);
			boolean shift = hasShiftDown();
			var wnet = com.akira.redtale.economy.client.warehouse.WarehouseClientNetworking.class;
			if (r.hit() == HubStoragePage.Hit.UPGRADE) {
				if (button == 0) {
					if (com.akira.redtale.economy.client.warehouse.WarehouseClientNetworking.NEXT_SLOTS <= 0) {
						setStatus("仓储已是最大格数");
						return true;
					}
					HubStoragePage.confirmUpgrade = true;
				}
				return true;
			}
			if (r.hit() == HubStoragePage.Hit.CONFIRM_YES) {
				HubStoragePage.confirmUpgrade = false;
				com.akira.redtale.economy.client.warehouse.WarehouseClientNetworking.upgrade();
				setStatus("正在扩容…");
				return true;
			}
			if (r.hit() == HubStoragePage.Hit.CONFIRM_NO) {
				HubStoragePage.confirmUpgrade = false;
				return true;
			}
			if (r.hit() == HubStoragePage.Hit.SORT) {
				if (button == 0) {
					com.akira.redtale.economy.client.warehouse.WarehouseClientNetworking.localSort();
					com.akira.redtale.economy.client.warehouse.WarehouseClientNetworking.sort();
					setStatus("正在整理…");
				}
				return true;
			}
			if (r.hit() == HubStoragePage.Hit.CAT) {
				if (button == 0) {
					int hit = HubStoragePage.hitCat(mouseX, mouseY, cx, cy, cw);
					if (hit == -2) {
						HubStoragePage.catOpen = !HubStoragePage.catOpen;
						ClientUiConfig.click();
					} else if (hit >= 0) {
						storageCat = hit;
						HubStoragePage.catOpen = false;
						ClientUiConfig.click();
					}
				}
				return true;
			}
			if (r.hit() == HubStoragePage.Hit.STORAGE || r.hit() == HubStoragePage.Hit.INV) {
				int idx = r.index();
				int key = (r.hit() == HubStoragePage.Hit.STORAGE ? 1000 : 0) + idx;
				long now = System.currentTimeMillis();
				boolean dbl = now - lastSlotAt < 400 && !dragPlacing;
				lastSlotKey = key;
				lastSlotAt = now;

				// ★必须用镜像 CURSOR★：LAST.cursor() 要等服务端回包，会滞后一帧导致“抓起来又当成空手”
				var cur = com.akira.redtale.economy.client.warehouse.WarehouseClientNetworking.CURSOR;
				boolean holding = cur != null && !cur.isEmpty();

				if (dbl && holding) {
					com.akira.redtale.economy.client.warehouse.WarehouseClientNetworking.localCollect();
					com.akira.redtale.economy.client.warehouse.WarehouseClientNetworking.collect();
					return true;
				}
				if (r.hit() == HubStoragePage.Hit.STORAGE) {
					var snap = com.akira.redtale.economy.client.warehouse.WarehouseClientNetworking.LAST;
					if (idx >= 0 && idx < snap.stacks().size()) {
						var st = snap.stacks().get(idx);
						if (st != null && !st.isEmpty()
								&& !HubStoragePage.visible(st, storageQuery.toString(), storageCat)) {
							return true;
						}
					}
					if (shift) {
						com.akira.redtale.economy.client.warehouse.WarehouseClientNetworking.localQuickOut(idx);
						com.akira.redtale.economy.client.warehouse.WarehouseClientNetworking.quick(idx);
						dragPlacing = true;
						dragActive = true;
						dragButton = button;
						lastDragSlot = (r.hit() == HubStoragePage.Hit.STORAGE ? 1000 : 0) + idx;
					} else if (holding) {
						// ★按下不放下★：先进入拖拽态，松手时若没拖动才整叠放下
						dragActive = true;
						dragButton = button;
						pendingIdx = idx;
						pendingInv = false;
						pendingAll = button == 0;
						pressKey = 1000 + idx;
						lastDragSlot = pressKey;
						dragSlots.clear();
						dragSlots.add(idx);
					} else {
						com.akira.redtale.economy.client.warehouse.WarehouseClientNetworking.localPick(
								idx, button == 1);
						com.akira.redtale.economy.client.warehouse.WarehouseClientNetworking.pick(
								idx, button == 1);
						dragPlacing = true;
						dragActive = true;
						dragButton = button;
						lastDragSlot = (r.hit() == HubStoragePage.Hit.STORAGE ? 1000 : 0) + idx;
					}
				} else {
					final int realSlot = HubStoragePage.displayToSlot(idx);
					var mc2 = MinecraftClient.getInstance();
					if (mc2.player != null) {
						var st2 = mc2.player.getInventory().getStack(realSlot);
						if (!st2.isEmpty()
								&& !HubStoragePage.visible(st2, storageQuery.toString(), storageCat)) {
							return true;
						}
					}
					if (shift) {
						com.akira.redtale.economy.client.warehouse.WarehouseClientNetworking.localQuickIn(realSlot);
						com.akira.redtale.economy.client.warehouse.WarehouseClientNetworking.quickInv(realSlot);
						dragPlacing = true;
						dragActive = true;
						dragButton = button;
						lastDragSlot = (r.hit() == HubStoragePage.Hit.STORAGE ? 1000 : 0) + idx;
					} else if (!holding) {
						com.akira.redtale.economy.client.warehouse.WarehouseClientNetworking.localInvPick(
								realSlot, button == 1);
						com.akira.redtale.economy.client.warehouse.WarehouseClientNetworking.invPick(
								realSlot, button == 1);
						dragPlacing = true;
						dragActive = true;
						dragButton = button;
						lastDragSlot = (r.hit() == HubStoragePage.Hit.STORAGE ? 1000 : 0) + idx;
					} else {
						dragActive = true;
						dragButton = button;
						pendingIdx = realSlot;
						pendingInv = true;
						pendingAll = button == 0;
						pressKey = realSlot;
						lastDragSlot = realSlot;
					}
				}
				return true;
			}
			return true;
		}
		if (button == 0 && page == HubPage.PASS) {
			int cx = panelX + sidebarW() + 12;
			int cy = panelY + TOP_BAR + 8;
			int cw = panelW - sidebarW() - 24;
			int ch = panelH - TOP_BAR - BOTTOM_BAR - 16;
			HubPassPage.Claim c = HubPassPage.click(mouseX, mouseY, cx, cy, cw, ch, passScroll);
			if (c != null) {
				var mc = MinecraftClient.getInstance();
				String cmd = "premium".equals(c.track())
						? "pass claim " + c.level() + " premium"
						: "pass claim " + c.level();
				if (mc.player != null) mc.player.networkHandler.sendChatCommand(cmd);
				setStatus("正在领取 Lv" + c.level() + " · " + ("premium".equals(c.track()) ? "付费" : "免费"));
				return true;
			}
		}
		if (button == 0 && page == HubPage.BOUNTY) {
			int cx = panelX + sidebarW() + 12;
			int cy = panelY + TOP_BAR + 8;
			int cw = panelW - sidebarW() - 24;
			int ch = panelH - TOP_BAR - BOTTOM_BAR - 16;
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
						setStatus("正在领取：" + e.id());
					}
				}
				return true;
			}
		}
		if (button == 0 && page == HubPage.SETTINGS) {
			int cx = panelX + sidebarW() + 12;
			int cy = panelY + TOP_BAR + 8;
			int cw = panelW - sidebarW() - 24;
			int ch = panelH - TOP_BAR - BOTTOM_BAR - 16;
			HubSettingsPage.Row row = HubSettingsPage.click(mouseX, mouseY, cx, cy, cw, ch);
			if (row != null) {
				ClientUiConfig.click();
				setStatus(HubSettingsPage.toggle(row));
				return true;
			}
		}
		if (button == 0 && page == HubPage.RANK) {
			int cx = panelX + sidebarW() + 12;
			int cy = panelY + TOP_BAR + 8;
			int cw = panelW - sidebarW() - 24;
			int dd = HubRankPage.hitDropdown(mouseX, mouseY, cx, cy, rankDropdown, panelY + panelH - BOTTOM_BAR);
			if (dd == -2) {
				rankDropdown = !rankDropdown;
				ClientUiConfig.click();
				return true;
			}
			if (dd >= 0) {
				rankBoard = dd;
				rankDropdown = false;
				ClientUiConfig.click();
				return true;
			}
			if (rankDropdown) {
				rankDropdown = false;
				return true;
			}
			HubRankPage.Action ra = HubRankPage.click(mouseX, mouseY, cx, cy, cw,
					panelH - TOP_BAR - BOTTOM_BAR - 16, rankBoard);
			if (ra == HubRankPage.Action.ACTIVE_TOGGLE) {
				rankActiveOnly = !rankActiveOnly;
				ClientUiConfig.click();
				// U9：开关要真的影响数据 —— 带参数重新请求，服务端挑另一份缓存
				com.akira.redtale.economy.client.rank.RankClientNetworking.request(rankActiveOnly);
				setStatus(rankActiveOnly ? "只显示活跃玩家" : "显示全部玩家（含长期未上线）");
				return true;
			}
			if (ra == HubRankPage.Action.SYNC) {
				com.akira.redtale.economy.client.rank.RankClientNetworking.request(rankActiveOnly);
				setStatus("正在同步榜单…");
				return true;
			}
		}
		if (button == 0 && page == HubPage.SHOP) {
			int cx = panelX + sidebarW() + 12;
			int cy = panelY + TOP_BAR + 8;
			int cw = panelW - sidebarW() - 24;
			int ch = panelH - TOP_BAR - BOTTOM_BAR - 16;
			int[] idx = {shopSelected};
			HubShopPage.Action a = HubShopPage.click(textRenderer, mouseX, mouseY, cx, cy, cw, ch, shopSelected, idx);
			if (a == HubShopPage.Action.NONE && idx[0] != shopSelected) {
				shopSelected = idx[0];
				return true;
			}
			var st = ShopClientState.page();
			switch (a) {
				case SYNC -> {
					requestShopPage();
					setStatus("正在同步商品…");
				}
				case PREV -> {
					ShopClientNetworking.requestPage(st.shopId(), Math.max(0, st.page() - 1), shopQuery.toString());
					shopSelected = -1;
				}
				case NEXT -> {
					ShopClientNetworking.requestPage(st.shopId(), st.page() + 1, shopQuery.toString());
					shopSelected = -1;
				}
				case BUY -> {
					if (shopSelected >= 0 && shopSelected < st.entries().size()) {
						var e = st.entries().get(shopSelected);
						ShopClientNetworking.buy(st.shopId(), e.itemId(), 1);
						setStatus("正在购买 " + e.itemId());
					}
				}
				case SELL -> {
					if (shopSelected >= 0 && shopSelected < st.entries().size()) {
						var e = st.entries().get(shopSelected);
						ShopClientNetworking.sell(st.shopId(), e.itemId(), 1);
						setStatus("正在出售 " + e.itemId());
					}
				}
				// U11b：筛选变化一律回到第 1 页，条件随请求发服务端
				case FILTER_MOD -> {
					cycleFilter(0);
					requestShopPage();
				}
				case FILTER_CAT -> {
					cycleFilter(1);
					requestShopPage();
				}
				case FILTER_PRICE -> {
					cycleFilter(2);
					requestShopPage();
				}
				case FILTER_STOCK -> {
					cycleFilter(3);
					requestShopPage();
				}
				default -> {
				}
			}
			if (a != HubShopPage.Action.NONE) return true;
		}
		if (button == 0 && page == HubPage.CHECKIN) {
			int cx = panelX + sidebarW() + 12;
			int cy = panelY + TOP_BAR + 8;
			int cw = panelW - sidebarW() - 24;
			int ch = panelH - TOP_BAR - BOTTOM_BAR - 16;
			HubCheckinPage.Action a = HubCheckinPage.click(mouseX, mouseY, cx, cy, cw, ch,
					com.akira.redtale.economy.client.checkin.CheckinClientNetworking.LAST, monthOffset);
			var mc = MinecraftClient.getInstance();
			switch (a) {
				case PREV_MONTH -> monthOffset = Math.max(-12, monthOffset - 1);
				case NEXT_MONTH -> monthOffset = Math.min(0, monthOffset + 1);
				case CHECKIN -> {
					if (mc.player != null) mc.player.networkHandler.sendChatCommand("checkin");
					setStatus("正在签到…");
				}
				case MAKEUP -> {
					if (mc.player != null) mc.player.networkHandler.sendChatCommand("checkin make-up");
					setStatus("正在补签…");
				}
				default -> {
				}
			}
			if (a != HubCheckinPage.Action.NONE) return true;
		}
		return super.mouseClicked(mouseX, mouseY, button);
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
	private boolean inSearchBox(double mx, double my) {
		int cx = panelX + sidebarW() + 12;
		int cy = panelY + TOP_BAR + 8;
		int cw = panelW - sidebarW() - 24;
		if (page == HubPage.SHOP) {
			return mx >= cx && mx < cx + cw - 40 && my >= cy && my < cy + 18;
		}
		if (page == HubPage.STORAGE) {
			return mx >= cx && mx < cx + cw - 92 && my >= cy + 16 && my < cy + 32;
		}
		return false;
	}

	private void requestGuild() {
		var mc = MinecraftClient.getInstance();
		if (mc.player != null) mc.player.networkHandler.sendChatCommand("guild");
	}

	private void requestPass() {
		var mc = MinecraftClient.getInstance();
		if (mc.player != null) mc.player.networkHandler.sendChatCommand("pass");
	}

	private void requestBounty() {
		var mc = MinecraftClient.getInstance();
		if (mc.player != null) mc.player.networkHandler.sendChatCommand("bounty list");
	}

	private void requestShopPage() {
		shopRequested = true;
		String sid = ShopClientState.shopIdToRequest();
		if (sid.isEmpty()) return;   // 还没收到 S2C_OPEN，等它到了再请求，别发空 id 换回空页
		ShopClientNetworking.requestPage(sid, 0, shopQuery.toString());
	}

	@Override
	public boolean charTyped(char chr, int modifiers) {
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
			int cx = panelX + sidebarW() + 12;
			int cy = panelY + TOP_BAR + 8;
			int cw = panelW - sidebarW() - 24;
			int ch = panelH - TOP_BAR - BOTTOM_BAR - 16;
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
		if (page == HubPage.STORAGE && mouseX > panelX + sidebarW()) {
			storageScroll = Math.max(0, Math.min(
					com.akira.redtale.economy.client.warehouse.WarehouseClientNetworking.SLOT_COUNT,
					storageScroll - (int) Math.signum(amount)));
			return true;
		}
		if (page == HubPage.PASS && mouseX > panelX + sidebarW()) {
			int cw = panelW - sidebarW() - 24;
			int max = HubPassPage.maxScroll(cw);
			if (max > 0) {
				passScroll = Math.max(0, Math.min(max, passScroll - (int) Math.signum(amount)));
				return true;
			}
		}
		if (mouseX >= panelX && mouseX < panelX + sidebarW()) {
			int ms = maxScroll();
			if (ms > 0) {
				sidebarScroll -= (int) (amount * ROW_H);
				sidebarScroll = Math.max(0, Math.min(ms, sidebarScroll));
				return true;
			}
		}
		return super.mouseScrolled(mouseX, mouseY, amount);
	}
}
