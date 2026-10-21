package com.akira.redtale.economy.client.admin;

import com.akira.redtale.economy.client.ShopClientNetworking;
import com.akira.redtale.economy.client.ShopClientState;
import com.akira.redtale.economy.net.ShopProtocol;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import java.util.List;

/**
 * 商店可视化编辑界面（R16a）。
 *
 * <p>左侧列当前页商品，右侧改价 / 改库存 / 上下架 / 删除 / 新增。
 * 所有写操作都<b>走现有管理命令</b>（{@code /shop admin ...}），因此权限、价格护栏、回收站删除
 * 全部由服务端那一份逻辑保证——界面不复制任何校验，也不直接碰文件。
 *
 * <p>数据只来自服务端下发的一页（{@link ShopClientState}），界面不做本地缓存修改：
 * 每次操作后重新 {@code requestPage}，看到的永远是服务端真实状态。
 */
@Environment(EnvType.CLIENT)
public final class ShopEditScreen extends Screen {

	private static final int ROW_H = 12;
	private static final int LIST_W = 250;
	private static final int PAD = 6;

	private final String shopId;
	private int page;
	private int scroll;
	private int selected = -1;
	private ShopProtocol.Page shown = ShopProtocol.Page.empty("", "");

	private TextFieldWidget fItem;
	private TextFieldWidget fBuy;
	private TextFieldWidget fSell;
	private TextFieldWidget fStock;

	private ButtonWidget bApply;
	private ButtonWidget bToggle;
	private ButtonWidget bStock;
	private ButtonWidget bRemove;
	private ButtonWidget bAdd;
	private ButtonWidget bPrev;
	private ButtonWidget bNext;
	private ButtonWidget bRefresh;

	private String flash = "";
	private long flashAt;

	private int left;
	private int top;
	private int listTop;

	public ShopEditScreen(String shopId) {
		super(Text.translatable("gui.redtale_economy.edit.title"));
		this.shopId = shopId == null ? "" : shopId;
	}

	private int rows() {
		return Math.max(1, Math.min(12, ShopClientState.visibleRows() + 3));
	}

	private int panelH() {
		return 24 + rows() * ROW_H + 52;
	}

	@Override
	protected void init() {
		left = (width - (LIST_W + 190)) / 2;
		top = (height - panelH()) / 2;
		listTop = top + 30;
		ShopClientNetworking.requestPage(shopId, page, "");

		int fx = left + LIST_W + 12;
		int fy = top + 34;
		fItem = new TextFieldWidget(textRenderer, fx, fy, 160, 16, Text.translatable("gui.redtale_economy.edit.item"));
		fItem.setMaxLength(96);
		addDrawableChild(fItem);

		fBuy = numberField(fx, fy + 36, "gui.redtale_economy.edit.buy");
		fSell = numberField(fx, fy + 56, "gui.redtale_economy.edit.sell");
		fStock = numberField(fx, fy + 76, "gui.redtale_economy.edit.stock");

		bApply = button(fx, fy + 100, 76, "gui.redtale_economy.edit.apply", b -> applyPrice());
		bToggle = button(fx + 84, fy + 100, 76, "gui.redtale_economy.edit.toggle", b -> toggle());
		bStock = button(fx, fy + 124, 76, "gui.redtale_economy.edit.setStock", b -> applyStock());
		bRemove = button(fx + 84, fy + 124, 76, "gui.redtale_economy.edit.remove", b -> remove());
		bAdd = button(fx, fy + 148, 160, "gui.redtale_economy.edit.add", b -> add());

		int navY = top + panelH() - 24;
		bPrev = button(left, navY, 50, "gui.redtale_economy.edit.prev", b -> {
			if (page > 0) {
				page--;
				selected = -1;
				ShopClientNetworking.requestPage(shopId, page, "");
			}
		});
		bRefresh = button(left + 56, navY, 60, "gui.redtale_economy.edit.refresh", b -> {
			selected = -1;
			ShopClientNetworking.requestPage(shopId, page, "");
		});
		bNext = button(left + 122, navY, 50, "gui.redtale_economy.edit.next", b -> {
			page++;
			selected = -1;
			ShopClientNetworking.requestPage(shopId, page, "");
		});
		button(left + LIST_W + 12, navY, 160, "gui.redtale_economy.edit.close", b -> close());
	}

	private TextFieldWidget numberField(int x, int y, String key) {
		TextFieldWidget f = new TextFieldWidget(textRenderer, x, y, 160, 16, Text.translatable(key));
		f.setMaxLength(12);
		f.setText("0");
		addDrawableChild(f);
		return f;
	}

	private ButtonWidget button(int x, int y, int w, String key, ButtonWidget.PressAction action) {
		ButtonWidget b = ButtonWidget.builder(Text.translatable(key), action).dimensions(x, y, w, 20).build();
		addDrawableChild(b);
		return b;
	}

	// ---------------------------------------------------------------- 操作

	private ShopProtocol.Entry current() {
		List<ShopProtocol.Entry> list = shown.entries();
		if (selected < 0 || selected >= list.size()) return null;
		return list.get(selected);
	}

	private void applyPrice() {
		ShopProtocol.Entry e = current();
		if (e == null) return;
		cmd(EditCommands.price(shopId, e.itemId(), num(fBuy, e.buyPrice()), num(fSell, e.sellPrice())));
		after("gui.redtale_economy.edit.sent");
	}

	private void applyStock() {
		ShopProtocol.Entry e = current();
		if (e == null) return;
		cmd(EditCommands.stock(shopId, e.itemId(), num(fStock, e.stock())));
		after("gui.redtale_economy.edit.sent");
	}

	private void toggle() {
		ShopProtocol.Entry e = current();
		if (e == null) return;
		cmd(EditCommands.toggle(shopId, e.itemId()));
		after("gui.redtale_economy.edit.sent");
	}

	private void remove() {
		ShopProtocol.Entry e = current();
		if (e == null) return;
		cmd(EditCommands.remove(shopId, e.itemId()));
		after("gui.redtale_economy.edit.sent");
	}

	private void add() {
		String item = fItem.getText().trim();
		if (item.isEmpty()) {
			flash("gui.redtale_economy.edit.needItem");
			return;
		}
		cmd(EditCommands.add(shopId, item, num(fBuy, 0), num(fSell, 0)));
		after("gui.redtale_economy.edit.sent");
	}

	private long num(TextFieldWidget f, long fallback) {
		try {
			long v = Long.parseLong(f.getText().trim());
			return v < 0 ? fallback : v;
		} catch (NumberFormatException ex) {
			return fallback;
		}
	}

	private void cmd(String command) {
		MinecraftClient c = MinecraftClient.getInstance();
		if (c.getNetworkHandler() == null) return;
		c.getNetworkHandler().sendChatCommand(command);
	}

	private void after(String key) {
		flash(key);
		// 命令是异步的：稍后再拉一次，避免读到操作前的旧页
		ShopClientNetworking.requestPage(shopId, page, "");
	}

	private void flash(String key) {
		flash = key;
		flashAt = System.currentTimeMillis();
	}

	// ---------------------------------------------------------------- 渲染

	@Override
	public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
		ShopProtocol.Page p = ShopClientState.page();
		if (!p.shopId().equals(shown.shopId()) || p.page() != shown.page() || p.totalItems() != shown.totalItems()) {
			shown = p;
			if (selected >= shown.entries().size()) selected = -1;
		}

		super.render(ctx, mouseX, mouseY, delta);

		// 左列表底
		ctx.fill(left - 2, top - 2, left + LIST_W + 2, top + panelH() + 2, 0xFF1B1B22);
		ctx.fill(left, top, left + LIST_W, top + panelH(), 0xE6101014);
		ctx.drawCenteredTextWithShadow(textRenderer, title, left + LIST_W / 2, top + 8, 0xFFFFFF);
		ctx.drawTextWithShadow(textRenderer, Text.literal(shopId), left + PAD, top + 18, 0x8A8A96);

		List<ShopProtocol.Entry> list = shown.entries();
		int visible = rows();
		if (selected >= 0 && selected < scroll) scroll = selected;
		if (selected >= scroll + visible) scroll = selected - visible + 1;

		for (int i = 0; i < visible; i++) {
			int idx = scroll + i;
			if (idx >= list.size()) break;
			ShopProtocol.Entry e = list.get(idx);
			int y = listTop + i * ROW_H;
			if (idx == selected) {
				ctx.fill(left + 2, y - 2, left + LIST_W - 2, y + ROW_H - 3, 0x40FFFFFF);
			}
			String flag = e.canBuy() && e.canSell() ? "●" : "○";
			ctx.drawTextWithShadow(textRenderer, Text.literal(flag + " " + shortId(e.itemId())),
					left + PAD, y, idx == selected ? 0xFFFFFF : 0xC8C8D2);
			ctx.drawTextWithShadow(textRenderer,
					Text.literal(e.buyPrice() + "/" + e.sellPrice() + " ×" + e.stock()),
					left + LIST_W - 108, y, 0x8A8A96);
		}
		if (list.isEmpty()) {
			ctx.drawCenteredTextWithShadow(textRenderer,
					Text.translatable("gui.redtale_economy.edit.empty"), left + LIST_W / 2, listTop + 20, 0x8A8A96);
		}

		ctx.drawTextWithShadow(textRenderer,
				Text.literal("page " + (shown.page() + 1) + "/" + Math.max(1, shown.totalPages())
						+ "  ·  " + shown.totalItems() + " items"),
				left + PAD, top + panelH() - 40, 0x6E6E7A);

		ShopProtocol.Entry cur = current();
		boolean has = cur != null;
		bApply.active = has;
		bToggle.active = has;
		bStock.active = has;
		bRemove.active = has;

		if (!flash.isEmpty() && System.currentTimeMillis() - flashAt < 2500) {
			ctx.drawTextWithShadow(textRenderer, Text.translatable(flash), left + LIST_W + 12, top + panelH() - 40, 0x7EC87E);
		}
		bPrev.active = page > 0;
	}

	/** 物品 id 太长时截断，保留尾部（命名空间更能区分）。 */
	private static String shortId(String id) {
		return id.length() <= 26 ? id : "…" + id.substring(id.length() - 25);
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		if (mouseX >= left && mouseX <= left + LIST_W && mouseY >= listTop - 2) {
			int idx = scroll + (int) ((mouseY - listTop) / ROW_H);
			if (idx >= 0 && idx < shown.entries().size()) {
				selected = idx;
				ShopProtocol.Entry e = shown.entries().get(idx);
				fItem.setText(e.itemId());
				fBuy.setText(Long.toString(e.buyPrice()));
				fSell.setText(Long.toString(e.sellPrice()));
				fStock.setText(Long.toString(e.stock()));
				return true;
			}
		}
		return super.mouseClicked(mouseX, mouseY, button);
	}

	@Override
	public boolean shouldPause() {
		return false;
	}
}
