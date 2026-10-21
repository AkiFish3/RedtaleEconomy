package com.akira.redtale.economy.client.admin;

import com.akira.redtale.economy.client.ShopClientNetworking;
import com.akira.redtale.economy.client.ShopClientState;
import com.akira.redtale.economy.net.ShopProtocol;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 商店表格编辑器 V2（R16 / spec §9.3）。
 *
 * <p>相比 V1 的单行表单，这里是一次看整页、可勾选多行、批量改价、按列排序、搜索。
 * <b>所有写操作仍然只拼 {@code /shop admin ...} 命令</b>（{@link EditCommands}），
 * 权限、价格上下限、回收站、二次确认全部由服务端负责，界面不做任何本地校验或本地改值，
 * 因此不会出现「界面显示改成 0、服务端其实拒绝了」的假象。
 */
@Environment(EnvType.CLIENT)
public final class TableEditorScreen extends Screen {

	private enum Sort { NAME, BUY, SELL, STOCK }

	private static final int ROW_H = 12;
	private static final int PAD = 6;

	private final String shopId;
	private int page;
	private Sort sort = Sort.NAME;
	private boolean sortAsc = true;
	private String query = "";

	private final Set<String> selected = new LinkedHashSet<>();
	private ShopProtocol.Page shown = ShopProtocol.Page.empty("", "");

	private TextFieldWidget fSearch;
	private TextFieldWidget fBuy;
	private TextFieldWidget fSell;
	private TextFieldWidget fItem;

	private int left;
	private int top;
	private int tableTop;
	private int tableW;
	private String flash = "";
	private long flashAt;

	public TableEditorScreen(String shopId) {
		super(Text.translatable("gui.redtale_economy.table.title"));
		this.shopId = shopId == null ? "" : shopId;
	}

	public static void open(String shopId) {
		net.minecraft.client.MinecraftClient mc = net.minecraft.client.MinecraftClient.getInstance();
		mc.setScreen(new TableEditorScreen(shopId));
	}

	private int rows() {
		return 10;
	}

	private int panelH() {
		return 26 + rows() * ROW_H + 78;
	}

	@Override
	protected void init() {
		tableW = 300;
		left = (width - tableW) / 2;
		top = (height - panelH()) / 2;
		tableTop = top + 30;
		ShopClientNetworking.requestPage(shopId, page, query);

		fSearch = new TextFieldWidget(textRenderer, left, top + panelH() - 52, 140, 16,
				Text.translatable("gui.redtale_economy.table.search"));
		fSearch.setMaxLength(64);
		fSearch.setText(query);
		addDrawableChild(fSearch);

		fBuy = numField(left + 4, top + panelH() - 32, 60, "gui.redtale_economy.edit.buy");
		fSell = numField(left + 70, top + panelH() - 32, 60, "gui.redtale_economy.edit.sell");
		fItem = new TextFieldWidget(textRenderer, left + 136, top + panelH() - 32, 80, 16,
				Text.translatable("gui.redtale_economy.edit.item"));
		fItem.setMaxLength(96);
		addDrawableChild(fItem);

		button(left + 144, top + panelH() - 52, 60, "gui.redtale_economy.table.searchBtn", b -> applySearch());
		button(left + 208, top + panelH() - 52, 88, "gui.redtale_economy.table.batch", b -> batchPrice());
		// T11：批量上架/批量移除（服务端一次读盘一次写盘；界面只拼一条命令，不逐行发）
		addDrawableChild(ButtonWidget.builder(Text.literal("批量上架"), b -> batchAddSelected())
				.dimensions(left + 4, top + panelH() - 74, 96, 20).build());
		addDrawableChild(ButtonWidget.builder(Text.literal("批量移除"), b -> batchRemoveSelected())
				.dimensions(left + 104, top + panelH() - 74, 96, 20).build());
		button(left + 220, top + panelH() - 32, 76, "gui.redtale_economy.table.add", b -> addOne());
		button(left, top + panelH() - 12, 56, "gui.redtale_economy.edit.prev", b -> turn(-1));
		button(left + 62, top + panelH() - 12, 56, "gui.redtale_economy.table.copy", b -> copyFirst());
		button(left + 124, top + panelH() - 12, 56, "gui.redtale_economy.edit.refresh", b -> refresh());
		button(left + 186, top + panelH() - 12, 56, "gui.redtale_economy.edit.next", b -> turn(1));
		button(left + 248, top + panelH() - 12, 48, "gui.redtale_economy.edit.close", b -> close());
	}

	private TextFieldWidget numField(int x, int y, int w, String key) {
		TextFieldWidget f = new TextFieldWidget(textRenderer, x, y, w, 16, Text.translatable(key));
		f.setMaxLength(12);
		addDrawableChild(f);
		return f;
	}

	private ButtonWidget button(int x, int y, int w, String key, ButtonWidget.PressAction action) {
		return addDrawableChild(ButtonWidget.builder(Text.translatable(key), action)
				.dimensions(x, y, w, 20).build());
	}

	// ------------------------------------------------------------------ 数据/操作

	private List<ShopProtocol.Entry> view() {
		List<ShopProtocol.Entry> list = TableSort.filter(shown.entries(),
				fSearch == null ? "" : fSearch.getText());
		return TableSort.sort(list, sort.name().toLowerCase(Locale.ROOT), sortAsc);
	}

	private void applySearch() {
		query = fSearch.getText().trim();
		page = 0;
		selected.clear();
		ShopClientNetworking.requestPage(shopId, page, query);
	}

	private void turn(int delta) {
		page = Math.max(0, page + delta);
		selected.clear();
		ShopClientNetworking.requestPage(shopId, page, query);
	}

	private void refresh() {
		selected.clear();
		ShopClientNetworking.requestPage(shopId, page, query);
	}

	/** 批量改价：只对勾选行生效；留空的字段表示「保持原值」。 */
	private void batchPrice() {
		List<ShopProtocol.Entry> rows = view();
		long buy = parseOr(fBuy.getText(), -1);
		long sell = parseOr(fSell.getText(), -1);
		if (buy < 0 && sell < 0) {
			flash("gui.redtale_economy.table.needPrice");
			return;
		}
		int n = 0;
		for (ShopProtocol.Entry e : rows) {
			if (!selected.contains(e.itemId())) continue;
			long nb = buy >= 0 ? buy : e.buyPrice();
			long ns = sell >= 0 ? sell : e.sellPrice();
			send(EditCommands.price(shopId, e.itemId(), nb, ns));
			n++;
		}
		flash("gui.redtale_economy.table.batchDone");
		selected.clear();
		if (n == 0) flash("gui.redtale_economy.table.noSelection");
	}

	/**
	 * T11 批量上架：id 来源优先「物品输入框里的逗号列表」，为空则取背包里所有非空堆叠
	 * （按物品去重）。价格取买/卖两个输入框，留空按 0。
	 *
	 * <p>只发 <b>一条</b> 命令 —— 服务端 {@code ShopCatalog.addItems} 一次读盘一次写盘，
	 * 避免逐行发命令时「第 7 件失败但前 6 件已经写盘」的中间态。
	 */
	private void batchAddSelected() {
		List<String> ids = new ArrayList<>(
				com.akira.redtale.economy.shop.BatchIds.parse(fItem.getText()));
		if (ids.isEmpty()) {
			net.minecraft.client.MinecraftClient mc = net.minecraft.client.MinecraftClient.getInstance();
			if (mc.player != null) {
				var inv = mc.player.getInventory();
				for (int i = 0; i < inv.size(); i++) {
					var st = inv.getStack(i);
					if (st == null || st.isEmpty()) continue;
					ids.add(net.minecraft.registry.Registries.ITEM.getId(st.getItem()).toString());
				}
			}
		}
		if (ids.isEmpty()) {
			toast("没有可上架的物品：输入框为空，背包也空");
			return;
		}
		long buy = parseOr(fBuy.getText(), 0);
		long sell = parseOr(fSell.getText(), 0);
		send(EditCommands.batchAdd(shopId, buy, sell, ids));
		toast("已发批量上架：" + ids.size() + " 件（价格买 " + buy + " / 卖 " + sell + "）");
	}

	/** T11 批量移除：只对勾选行生效（未勾选时明确提示，不做「整页删」这种危险默认）。 */
	private void batchRemoveSelected() {
		List<String> ids = new ArrayList<>(selected);
		if (ids.isEmpty()) {
			toast("先勾选要移除的行（点行左侧方框）");
			return;
		}
		send(EditCommands.batchRemove(shopId, ids));
		toast("已发批量移除：" + ids.size() + " 件（服务端整批写回收站）");
		selected.clear();
	}

	/** 界面自己的提示串（中文直接给，不走 lang：assets 归 B 队列）。 */
	private void toast(String s) {
		flash = s;
		flashAt = System.currentTimeMillis();
	}

	/** 复制选中第一行的价格到输入框（再配合批量改价即可「一处定价、全店套用」）。 */
	private void copyFirst() {
		for (ShopProtocol.Entry e : view()) {
			if (!selected.contains(e.itemId())) continue;
			fBuy.setText(Long.toString(e.buyPrice()));
			fSell.setText(Long.toString(e.sellPrice()));
			flash("gui.redtale_economy.table.copied");
			return;
		}
		flash("gui.redtale_economy.table.noSelection");
	}

	private void addOne() {
		String id = fItem.getText().trim();
		if (id.isEmpty()) {
			flash("gui.redtale_economy.edit.needItem");
			return;
		}
		long buy = parseOr(fBuy.getText(), 0);
		long sell = parseOr(fSell.getText(), 0);
		send(EditCommands.add(shopId, id, buy, sell));
		flash("gui.redtale_economy.table.added");
	}

	private void send(String cmd) {
		var p = net.minecraft.client.MinecraftClient.getInstance().player;
		if (p != null && p.networkHandler != null) p.networkHandler.sendChatCommand(cmd);
	}

	private static long parseOr(String s, long def) {
		try {
			return Long.parseLong(s.trim());
		} catch (Exception e) {
			return def;
		}
	}

	private void flash(String key) {
		flash = Text.translatable(key).getString();
		flashAt = System.currentTimeMillis();
	}

	@Override
	public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
		renderBackground(ctx);
		shown = ShopClientState.page();
		ctx.fill(left, top, left + tableW, top + panelH(), 0xFF3B2E23);
		ctx.fill(left + 2, top + 2, left + tableW - 2, top + panelH() - 2, 0xFFE8DCC8);
		ctx.drawBorder(left, top, tableW, panelH(), 0xFFC9A227);
		ctx.drawText(textRenderer, Text.translatable("gui.redtale_economy.table.title"), left + PAD, top + 6,
				0xFFC9A227, false);
		ctx.drawText(textRenderer, Text.literal(shopId), left + 120, top + 6, 0xFF2B2118, false);
		ctx.drawText(textRenderer, Text.literal((page + 1) + "/" + Math.max(1, shown.totalPages())
						+ "  " + shown.totalItems() + " items"),
				left + 200, top + 6, 0xFF2B2118, false);

		// 表头（点击排序）
		int hy = tableTop - 12;
		header(ctx, left + 14, hy, "gui.redtale_economy.table.colName", Sort.NAME, 180);
		header(ctx, left + 150, hy, "gui.redtale_economy.edit.buy", Sort.BUY, 50);
		header(ctx, left + 200, hy, "gui.redtale_economy.edit.sell", Sort.SELL, 50);
		header(ctx, left + 250, hy, "gui.redtale_economy.edit.stock", Sort.STOCK, 44);

		List<ShopProtocol.Entry> rows = view();
		for (int i = 0; i < Math.min(rows.size(), rows()); i++) {
			ShopProtocol.Entry e = rows.get(i);
			int y = tableTop + i * ROW_H;
			boolean sel = selected.contains(e.itemId());
			if (sel) ctx.fill(left + 4, y - 1, left + tableW - 4, y + ROW_H - 2, 0x33C9A227);
			ctx.drawText(textRenderer, Text.literal(sel ? "☑" : "☐"), left + 6, y, 0xFF2B2118, false);
			ctx.drawText(textRenderer, Text.literal(e.itemId()), left + 18, y, 0xFF2B2118, false);
			ctx.drawText(textRenderer, Text.literal(Long.toString(e.buyPrice())), left + 150, y, 0xFF2B2118, false);
			ctx.drawText(textRenderer, Text.literal(Long.toString(e.sellPrice())), left + 200, y, 0xFF2B2118, false);
			ctx.drawText(textRenderer, Text.literal(e.stock() < 0 ? "∞" : Long.toString(e.stock())),
					left + 250, y, 0xFF2B2118, false);
		}
		if (rows.isEmpty()) {
			ctx.drawText(textRenderer, Text.translatable("gui.redtale_economy.table.empty"), left + 16,
					tableTop + 8, 0xFFB4462F, false);
		}
		if (System.currentTimeMillis() - flashAt < 4000 && !flash.isEmpty()) {
			ctx.drawText(textRenderer, Text.literal(flash), left + PAD, top + panelH() - 62, 0xFFB4462F, false);
		}
		super.render(ctx, mouseX, mouseY, delta);
	}

	private void header(DrawContext ctx, int x, int y, String key, Sort s, int w) {
		String mark = sort == s ? (sortAsc ? " ▲" : " ▼") : "";
		ctx.drawText(textRenderer, Text.translatable(key).copy().append(Text.literal(mark)), x, y,
				0xFF6B5A45, false);
	}

	@Override
	public boolean mouseClicked(double mx, double my, int button) {
		if (button == 0) {
			// 表头排序
			int hy = tableTop - 12;
			if (my >= hy && my < hy + 10) {
				Sort hit = null;
				if (mx >= left + 150 && mx < left + 200) hit = Sort.BUY;
				else if (mx >= left + 200 && mx < left + 250) hit = Sort.SELL;
				else if (mx >= left + 250 && mx < left + 294) hit = Sort.STOCK;
				else if (mx >= left + 14 && mx < left + 150) hit = Sort.NAME;
				if (hit != null) {
					if (sort == hit) sortAsc = !sortAsc;
					else {
						sort = hit;
						sortAsc = true;
					}
					return true;
				}
			}
			// 行勾选
			List<ShopProtocol.Entry> rows = view();
			for (int i = 0; i < Math.min(rows.size(), rows()); i++) {
				int y = tableTop + i * ROW_H;
				if (my >= y - 1 && my < y + ROW_H - 2 && mx >= left + 4 && mx < left + tableW - 4) {
					String id = rows.get(i).itemId();
					if (!selected.remove(id)) selected.add(id);
					return true;
				}
			}
		}
		return super.mouseClicked(mx, my, button);
	}

	@Override
	public boolean shouldPause() {
		return false;
	}
}
