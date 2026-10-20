package com.akira.redtale.economy.client.shop;

import com.akira.redtale.economy.client.ShopClientNetworking;
import com.akira.redtale.economy.client.ShopClientState;
import com.akira.redtale.economy.net.ShopProtocol;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.List;

/**
 * 商店界面（R7 分页 GUI）。
 *
 * <p>数据全部来自服务端的一页（默认 40 条），客户端只负责画和发意图。
 * 一屏行数由服务端 {@code shop/settings.json → visibleRows} 随包下发（默认 9），
 * 页内可滚轮滚动，翻页由服务端重新切片。
 * 余额 / 库存 / 能不能买都只以服务端回的 {@code ActionResult} 为准。
 */
public final class ShopScreen extends Screen {

	/** 行高通用于所有布局计算。 */
	private static final int ROW_H = 18;
	private static final int PANEL_W = 360;

	private final String shopId;
	private int page;
	private String query = "";
	private int scroll;          // 页内起始行
	private int selected = -1;    // 当前页内下标
	private int rows = 9;         // 一屏行数（服务端下发，超出屏幕会钳制）
	private ShopProtocol.Page shown;
	private long issuedAt;
	private String flash;

	private int left;
	private int top;
	private int listTop;
	private TextFieldWidget search;
	private ButtonWidget prev;
	private ButtonWidget next;
	private ButtonWidget buy1;
	private ButtonWidget buy8;
	private ButtonWidget sell1;

	public ShopScreen(String shopId) {
		super(Text.translatable("gui.redtale_economy.shop.title"));
		this.shopId = shopId == null ? "" : shopId;
	}

	private int rows() {
		return Math.max(1, Math.min(14, rows));
	}

	private int panelH() {
		return 20 + 22 + rows() * ROW_H + 46;
	}

	@Override
	protected void init() {
		rows = ShopClientState.visibleRows();
		left = (width - PANEL_W) / 2;
		top = (height - panelH()) / 2;
		listTop = top + 44;

		search = new TextFieldWidget(textRenderer, left + 8, top + 22, 200, 18,
				Text.translatable("gui.redtale_economy.shop.search"));
		search.setMaxLength(32);
		search.setText(query);
		addDrawableChild(search);

		addDrawableChild(ButtonWidget.builder(Text.translatable("gui.redtale_economy.shop.searchBtn"),
				b -> applySearch()).dimensions(left + 212, top + 22, 60, 18).build());

		int navY = top + 24 + rows() * ROW_H + 8;
		prev = addDrawableChild(ButtonWidget.builder(Text.literal("<"),
				b -> changePage(-1)).dimensions(left + 8, navY, 24, 18).build());
		next = addDrawableChild(ButtonWidget.builder(Text.literal(">"),
				b -> changePage(1)).dimensions(left + PANEL_W - 32, navY, 24, 18).build());

		buy1 = addDrawableChild(ButtonWidget.builder(Text.translatable("gui.redtale_economy.shop.buy1"),
				b -> trade(true, 1)).dimensions(left + 120, navY, 56, 18).build());
		buy8 = addDrawableChild(ButtonWidget.builder(Text.translatable("gui.redtale_economy.shop.buy8"),
				b -> trade(true, 8)).dimensions(left + 180, navY, 56, 18).build());
		sell1 = addDrawableChild(ButtonWidget.builder(Text.translatable("gui.redtale_economy.shop.sell1"),
				b -> trade(false, 1)).dimensions(left + 240, navY, 56, 18).build());

		request();
	}

	private void rebuild() {
		clearChildren();
		init();
	}

	private void request() {
		issuedAt = System.currentTimeMillis();
		ShopClientNetworking.requestPage(shopId, page, query);
	}

	private void applySearch() {
		query = search.getText() == null ? "" : search.getText().trim();
		page = 0;
		scroll = 0;
		selected = -1;
		request();
	}

	private void changePage(int delta) {
		ShopProtocol.Page p = shown;
		int total = p == null ? 1 : Math.max(1, p.totalPages());
		int target = Math.max(0, Math.min(total - 1, page + delta));
		if (target == page) return;
		page = target;
		scroll = 0;
		selected = -1;
		request();
	}

	private void trade(boolean buy, int count) {
		ShopProtocol.Page p = shown;
		if (p == null || selected < 0 || selected >= p.entries().size()) return;
		String itemId = p.entries().get(selected).itemId();
		if (buy) {
			ShopClientNetworking.buy(shopId, itemId, count);
		} else {
			ShopClientNetworking.sell(shopId, itemId, count);
		}
	}

	@Override
	public void tick() {
		int serverRows = ShopClientState.visibleRows();
		if (serverRows != rows) {
			rows = serverRows;
			rebuild();
			return;
		}
		ShopProtocol.Page p = ShopClientState.page();
		if (p != null && p.shopId().equals(shopId) && (shown == null || p != shown)) {
			shown = p;
			if (page != p.page()) page = p.page();
			int maxScroll = Math.max(0, p.entries().size() - rows());
			if (scroll > maxScroll) scroll = maxScroll;
			selected = -1;
		}
		ShopProtocol.ActionResult r = ShopClientState.lastAction();
		if (r != null && ShopClientState.lastActionAt() >= issuedAt) {
			flash = Text.translatable(r.ok() ? "gui.redtale_economy.shop.ok" : r.messageKey()).getString();
			if (!r.ok() && "shop.notEnoughMoney".equals(r.messageKey())) {
				flash = flash + "（当前 " + r.balance() + " 铜 / 需要 " + r.stockRemaining() + " 铜）";
			}
			if (r.ok()) request(); // 成功后刷新当前页（库存/余额都变了）
		}
	}

	// ---------------------------------------------------------------- 渲染

	@Override
	public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
		renderBackground(ctx);
		com.akira.redtale.economy.client.ui.UiStyle.panel(ctx, left, top, PANEL_W, panelH());

		ctx.drawText(textRenderer, Text.translatable("gui.redtale_economy.shop.title").getString()
				+ " · " + shopId, left + 8, top + 8, com.akira.redtale.economy.client.ui.UiStyle.TEXT, false);

		ShopProtocol.Page p = shown;
		String pageInfo = p == null
				? Text.translatable("gui.redtale_economy.shop.loading").getString()
				: Text.translatable("gui.redtale_economy.shop.page", p.page() + 1,
						Math.max(1, p.totalPages()), p.totalItems()).getString();
		ctx.drawText(textRenderer, pageInfo, left + PANEL_W - 8 - textRenderer.getWidth(pageInfo),
				top + 8, com.akira.redtale.economy.client.ui.UiStyle.TEXT_DIM, false);

		List<ShopProtocol.Entry> list = p == null ? List.of() : p.entries();
		if (list.isEmpty()) {
			ctx.drawText(textRenderer, Text.translatable("gui.redtale_economy.shop.empty").getString(),
					left + 12, listTop + 6, com.akira.redtale.economy.client.ui.UiStyle.TEXT_FAINT, false);
		}
		for (int row = 0; row < rows(); row++) {
			int idx = scroll + row;
			int y = listTop + row * ROW_H;
			if (idx >= list.size()) break;
			ShopProtocol.Entry e = list.get(idx);
			boolean sel = idx == selected;
			boolean hover = mouseX >= left + 6 && mouseX <= left + PANEL_W - 6 && mouseY >= y && mouseY < y + ROW_H;
			ctx.fill(left + 6, y, left + PANEL_W - 6, y + ROW_H - 1,
					sel ? 0xFF3A5A8A : (hover ? com.akira.redtale.economy.client.ui.UiStyle.CARD_HOVER : com.akira.redtale.economy.client.ui.UiStyle.CARD));

			ItemStack stack = stackOf(e.itemId());
			if (!stack.isEmpty()) ctx.drawItem(stack, left + 8, y + 1);

			String name = stack.isEmpty() ? e.itemId() : stack.getName().getString();
			if (textRenderer.getWidth(name) > 150) {
				name = textRenderer.trimToWidth(name, 148) + "…";
			}
			ctx.drawText(textRenderer, name, left + 30, y + 5, com.akira.redtale.economy.client.ui.UiStyle.TEXT, false);

			String price = Text.translatable("gui.redtale_economy.shop.price",
					e.canBuy() ? String.valueOf(e.buyPrice()) : "-",
					e.canSell() ? String.valueOf(e.sellPrice()) : "-").getString();
			ctx.drawText(textRenderer, price, left + 190, y + 5, e.canBuy() ? com.akira.redtale.economy.client.ui.UiStyle.ACCENT : com.akira.redtale.economy.client.ui.UiStyle.TEXT_FAINT, false);

			String stock = e.stock() < 0
					? Text.translatable("gui.redtale_economy.shop.stockInf").getString()
					: Text.translatable("gui.redtale_economy.shop.stock", e.stock()).getString();
			ctx.drawText(textRenderer, stock, left + 292, y + 5, com.akira.redtale.economy.client.ui.UiStyle.TEXT_DIM, false);
		}

		if (flash != null) {
			ctx.drawText(textRenderer, flash, left + 8, top + 24 + rows() * ROW_H - 4, com.akira.redtale.economy.client.ui.UiStyle.GOOD, false);
		}
		super.render(ctx, mouseX, mouseY, delta);
	}

	private static ItemStack stackOf(String id) {
		try {
			Identifier ident = Identifier.tryParse(id);
			if (ident == null) return ItemStack.EMPTY;
			return new ItemStack(Registries.ITEM.get(ident));
		} catch (Exception e) {
			return ItemStack.EMPTY;
		}
	}

	/** 是否要暂停单人游戏（商店界面不需要）。 */
	@Override
	public boolean shouldPause() {
		return false;
	}

	// ---------------------------------------------------------------- 输入

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		if (super.mouseClicked(mouseX, mouseY, button)) return true;
		ShopProtocol.Page p = shown;
		if (p == null) return false;
		for (int row = 0; row < rows(); row++) {
			int idx = scroll + row;
			if (idx >= p.entries().size()) break;
			int y = listTop + row * ROW_H;
			if (mouseX >= left + 6 && mouseX <= left + PANEL_W - 6 && mouseY >= y && mouseY < y + ROW_H) {
				selected = idx;
				return true;
			}
		}
		return false;
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
		ShopProtocol.Page p = shown;
		if (p == null) return false;
		int maxScroll = Math.max(0, p.entries().size() - rows());
		int ns = (int) Math.max(0, Math.min(maxScroll, scroll - Math.signum(amount)));
		if (ns != scroll) {
			scroll = ns;
			selected = -1;
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, amount);
	}

	@Override
	public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
		if (keyCode == 257 || keyCode == 335) { // Enter / KP_Enter
			applySearch();
			return true;
		}
		return super.keyPressed(keyCode, scanCode, modifiers);
	}

	@Override
	public void removed() {
		ShopClientState.clear();
	}
}
