package com.akira.redtale.economy.client.ui.hub;

import com.akira.redtale.economy.client.ShopClientState;
import com.akira.redtale.economy.net.ShopProtocol;
import com.akira.redtale.economy.shop.ShopTree;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.List;
import java.util.Locale;

/**
 * Hub 内的商店页（U11：目录树 + 筛选 + 搜索 + 分页）。
 *
 * <p>数据来自 {@link ShopClientState}（服务端下发的分页缓存，客户端不信任何数值）。
 * 左侧目录树的形状由商品的 {@code category} 路径决定（见 {@link ShopTree}），
 * 客户端只用服务端下发的 facet 列表在本地展开，不额外占协议字段。
 */
public final class HubShopPage {

	/**
	 * T54：商店改成「容器式图标格」6×4 网格。
	 *
	 * <p>旧布局是 96×58 的卡片（名字/单价/含税/库存四行字），427×240 下一屏只放得下
	 * 2×2 = 4 张，翻页也翻不到中间那些。现在一格就是一个物品图标：
	 * 目标是 6 列 × 4 行 = 24 格；格子边长取「按宽度分 6 列」和「按高度分 4 行」的较小值，
	 * 所以窗口变化时只缩格子、不改行数；窄到放不下 6 列才减列（最小 18 px）。
	 * 数量走右下角角标，价格/含税/库存走 hover 浮层（浮层最后画）。
	 */
	/** T69 的传统档（点循环时从这里起步）；实际值由 {@link #colsTarget}/{@link #rowsTarget} 决定。 */
	private static final int COLS_TARGET = 6;
	private static final int ROWS_TARGET = 4;
	/** 最小可点格子：再小图标就糊在描边上，改为减列。 */
	private static final int CELL_MIN = 18;
	/** 最大格子：高窗口下别把 24 格拉成一大片太空的方块。 */
	private static final int CELL_MAX = 44;
	private static final int GAP = 3;
	/**
	 * T69b：卡片列表模式的行高/行间距。
	 *
	 * <p>一行一件：左侧 16 px 图标 + 名字 + 右侧单价/库存。22 px 是「16 图标上下各 3 留白」，
	 * 再矮图标会贴着行描边；一行 22+2=24 px，比图标格（≥18+3）装得多，适合「翻列表找东西」。
	 */
	private static final int LIST_ROW = 22;
	private static final int LIST_GAP = 2;
	/** 目录树列宽；没有分类时整列不画，卡片区自动占满。 */
	private static final int TREE_W = 96;
	private static final int TREE_HEAD = 17;
	private static final int TREE_ROW = 14;

	/**
	 * 网格的纵向滚动行数（T44 截图暴露的缺口）。
	 *
	 * <p>服务端一页最多 {@code pageSize}（默认 40）条，而 427×240 逻辑像素下一屏只放得下
	 * 2 列 × 2 行 = 4 张卡 —— 超过的行以前被 {@code break} 直接丢掉，翻页也翻不到
	 * （第 1/1 页 · 共 9 项时中间 5 项彻底不可达）。滚动按「行」而不是像素，
	 * 松手必然停在整行上，不会切半张卡。
	 */
	private static int scrollRow;

	private HubShopPage() {
	}

	public enum Action {
		NONE, SYNC, BUY, SELL, PREV, NEXT,
		/** U11b：四个筛选维度，点击循环取值（含"全部"） */
		FILTER_MOD, FILTER_CAT, FILTER_PRICE, FILTER_STOCK,
		/** U11：点目录树节点（下钻）/ 点面包屑回上一级 */
		TREE_NODE, TREE_UP,
		/** T69：切换网格密度档（自动 → 6×4 → 8×5 → 10×6 → 自动） */
		DENSITY,
		/** T69b：切换「图标网格 ↔ 卡片列表」视图 */
		VIEW
	}

	/** 选中分类的路径（空 = 全部分类）—— 树的位置就是筛选条件，两处不会不同步。 */
	private static String treePath() {
		return ShopClientState.filter().categoryOrEmpty();
	}

	private static int treeWidth(ShopProtocol.Page page) {
		return page != null && !page.categories().isEmpty() ? TREE_W : 0;
	}

	/** 卡片区左边界（树占位后右移）。 */
	private static int gridX(int x, ShopProtocol.Page page) {
		int tw = treeWidth(page);
		return tw == 0 ? x : x + tw + 6;
	}

	private static int gridW(int w, ShopProtocol.Page page) {
		int tw = treeWidth(page);
		return tw == 0 ? w : w - tw - 6;
	}

	/** 筛选栏 chip 的布局（宽随文字变，但两处必须算得一样）。 */
	private static int[] chipWidths(TextRenderer tr, ShopProtocol.Page page) {
		com.akira.redtale.economy.net.ShopFilter f = ShopClientState.filter();
		return new int[]{
				tr.getWidth(modLabel(page, f)) + 10,
				tr.getWidth(catLabel(f)) + 10,
				tr.getWidth(priceLabel(f)) + 10,
				tr.getWidth(stockLabel(f)) + 10,
				tr.getWidth(densityLabel()) + 10,
				tr.getWidth(viewLabel()) + 10,
		};
	}

	private static String modLabel(ShopProtocol.Page page, com.akira.redtale.economy.net.ShopFilter f) {
		return "mod:" + (f.modOrEmpty().isEmpty() ? "全部" : f.modOrEmpty());
	}

	private static String catLabel(com.akira.redtale.economy.net.ShopFilter f) {
		String path = f.categoryOrEmpty();
		// 树里选的是路径（木材/台阶），chip 上只显示末段，否则宽度吃掉整条筛选栏
		return "类:" + (path.isEmpty() ? "全部" : ShopTree.label(path));
	}

	private static String priceLabel(com.akira.redtale.economy.net.ShopFilter f) {
		return f.maxPrice() <= 0 ? "价:不限" : "价:≤" + f.maxPrice();
	}

	private static String stockLabel(com.akira.redtale.economy.net.ShopFilter f) {
		return f.inStockOnly() ? "☑有货" : "☐有货";
	}

	public static void render(DrawContext dc, TextRenderer tr, int x, int y, int w, int h,
			double mouseX, double mouseY, String query, int selected) {
		ShopProtocol.Page page = ShopClientState.page();

		// 搜索框
		dc.fill(x, y, x + w - 40, y + 18, HubTheme.FIELD);
		dc.fill(x, y + 17, x + w - 40, y + 18, HubTheme.LINE);
		String shown = query.isEmpty() ? "搜索：id 或名称（空格分隔）" : query;
		dc.drawText(tr, Text.literal(shown), x + 4, y + 5,
				query.isEmpty() ? HubTheme.HINT : HubTheme.FIELD_TEXT, false);
		dc.drawText(tr, Text.literal("同步"), x + w - 30, y + 5, HubTheme.MUTED, false);

		// U11b：筛选栏（mod / 类型 / 价格 / 有货）—— 点击循环取值，与搜索词叠加
		drawFilterBar(dc, tr, x, y + 20, w, page, mouseX, mouseY);

		// U11：左侧多级目录树（面包屑可点回上级）
		if (treeWidth(page) > 0) {
			drawTree(dc, tr, x, y + 38, TREE_W, h - 38 - 26, page, mouseX, mouseY);
		}

		// 卡片区：T54 图标网格 / T69b 卡片列表（两套几何，渲染与命中同源）
		List<ShopProtocol.Entry> entries = page.entries();
		if (listMode()) {
			renderList(dc, tr, x, y, w, h, entries, selected, mouseX, mouseY);
		} else {
			renderGrid(dc, tr, x, y, w, h, page, entries, selected, mouseX, mouseY);
		}
		int gx = gridX(x, page);
		int gw = gridW(w, page);
		int startY = gridStartY(y);
		if (entries.isEmpty()) {
			boolean filtered = !ShopClientState.filter().isEmpty();
			dc.drawText(tr, Text.literal(filtered ? "没有符合筛选条件的商品" : "暂无商品"),
					gx + 6, startY + 8, HubTheme.MUTED, false);
			// ★不许出面板★：这两行以前直接画，长出边界压在边框上（U6 截图可见）
			String hint = filtered ? "点上方筛选项可循环切换 / 清除"
					: "管理员：/shop admin add <商店> <物品> <买价> <卖价>";
			dc.drawText(tr, Text.literal(trim(tr, hint, gw - 16)),
					gx + 6, startY + 22, HubTheme.HINT, false);
		}

		// 底部：分页 + 选中项操作
		int barY = y + h - 26;
		dc.fill(x, barY, x + w, barY + 22, HubTheme.BAR_SUB);
		String pg = "第 " + (page.page() + 1) + " / " + Math.max(1, page.totalPages())
				+ " 页 · 共 " + page.totalItems() + " 项";
		dc.drawText(tr, Text.literal(trim(tr, pg, w - 110)), x + 6, barY + 7, HubTheme.MUTED, false);
		// ‹ › 画在各自 16px 命中框里（-100..-84 / -64..-48），否则看着能点其实点不到
		dc.drawText(tr, Text.literal("‹"), x + w - 97, barY + 7, HubTheme.MUTED, false);
		dc.drawText(tr, Text.literal("›"), x + w - 61, barY + 7, HubTheme.MUTED, false);
		boolean hasSel = selected >= 0 && selected < entries.size();
		drawMini(dc, tr, x + w - 40, barY + 4, 18, 14, "买", hasSel, mouseX, mouseY);
		drawMini(dc, tr, x + w - 20, barY + 4, 18, 14, "卖", hasSel, mouseX, mouseY);
	}

	private static void renderGrid(DrawContext dc, TextRenderer tr, int x, int y, int w, int h,
			ShopProtocol.Page page, List<ShopProtocol.Entry> entries, int selected,
			double mouseX, double mouseY) {
		// 卡片：T54 改 6×4 图标网格（格子边长自适应，见 COLS_TARGET/ROWS_TARGET）
		int gx = gridX(x, page);
		int gw = gridW(w, page);
		int startY = gridStartY(y);
		int limitY = gridLimitY(y, h);
		int[] dims = dims(gw, limitY - startY);
		int cols = dims[0];
		int cell = dims[1];
		int rowCap = dims[2];
		int blockW = cols * cell + (cols - 1) * GAP;
		int gx0 = gx + Math.max(0, (gw - blockW) / 2);
		int totalRows = (entries.size() + cols - 1) / cols;
		scrollRow = Math.max(0, Math.min(scrollRow, Math.max(0, totalRows - rowCap)));
		// 超出部分要被裁掉：以前靠 break 不画，现在有了滚动位移，半行会压在分页条上
		dc.enableScissor(gx, startY, gx + gw, limitY);
		for (int i = 0; i < entries.size(); i++) {
			int col = i % cols;
			int row = i / cols - scrollRow;
			int cx = gx0 + col * (cell + GAP);
			int cy = startY + row * (cell + GAP);
			if (cy + cell > limitY) break;
			if (cy < startY) continue;
			drawCell(dc, tr, cx, cy, cell, entries.get(i), i == selected, mouseX, mouseY);
		}
		dc.disableScissor();
		if (totalRows > rowCap) {
			// 右侧细滚动条（与仓储页同款：1px 轨道 + 3px 滑块）
			int trackH = limitY - startY;
			int barH = Math.max(10, trackH * rowCap / totalRows);
			int barY = startY + (trackH - barH) * scrollRow / Math.max(1, totalRows - rowCap);
			dc.fill(gx + gw - 2, startY, gx + gw - 1, limitY, HubTheme.LINE);
			dc.fill(gx + gw - 2, barY, gx + gw - 1, barY + barH, HubTheme.MUTED);
		}
	}

	/**
	 * T69b：卡片列表（一行一件）。
	 *
	 * <p>图标网格胜在「一眼扫整页、认图比认字快」，列表胜在「名字/单价/库存直接可读」。
	 * 一行 {@link #LIST_ROW} 高，比 18 px 的图标格装得多（24 px 行距 vs 21 px 格距），
	 * 但一列；滚动仍按行，命中判定与网格共用 {@link #scrollRow}。
	 */
	private static void renderList(DrawContext dc, TextRenderer tr, int x, int y, int w, int h,
			List<ShopProtocol.Entry> entries, int selected, double mouseX, double mouseY) {
		ShopProtocol.Page page = ShopClientState.page();
		int gx = gridX(x, page);
		int gw = gridW(w, page);
		int startY = gridStartY(y);
		int limitY = gridLimitY(y, h);
		int rowCap = listRows(limitY - startY);
		int totalRows = entries.size();
		scrollRow = Math.max(0, Math.min(scrollRow, Math.max(0, totalRows - rowCap)));
		int rowW = gw - 6;   // 右侧 6 px 留给滚动条，别让文字压在滑块下面
		dc.enableScissor(gx, startY, gx + gw, limitY);
		for (int i = 0; i < entries.size(); i++) {
			int ry = startY + (i - scrollRow) * (LIST_ROW + LIST_GAP);
			if (ry + LIST_ROW > limitY) break;
			if (ry < startY) continue;
			drawListRow(dc, tr, gx, ry, rowW, entries.get(i), i == selected, mouseX, mouseY);
		}
		dc.disableScissor();
		if (totalRows > rowCap) {
			int trackH = limitY - startY;
			int barH = Math.max(10, trackH * rowCap / totalRows);
			int barY = startY + (trackH - barH) * scrollRow / Math.max(1, totalRows - rowCap);
			dc.fill(gx + gw - 2, startY, gx + gw - 1, limitY, HubTheme.LINE);
			dc.fill(gx + gw - 2, barY, gx + gw - 1, barY + barH, HubTheme.MUTED);
		}
	}

	/** 列表一行：图标 + 名字 + 右侧买价与库存（卖价/含税走 hover 浮层，行内塞不下）。 */
	private static void drawListRow(DrawContext dc, TextRenderer tr, int x, int y, int w,
			ShopProtocol.Entry e, boolean selected, double mouseX, double mouseY) {
		boolean hover = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + LIST_ROW;
		dc.fill(x, y, x + w, y + LIST_ROW, selected ? HubTheme.SELECT : (hover ? HubTheme.INNER_HOVER : HubTheme.CARD));
		int edge = selected ? HubTheme.GOLD : HubTheme.LINE;
		dc.fill(x, y, x + w, y + 1, edge);
		dc.fill(x, y + LIST_ROW - 1, x + w, y + LIST_ROW, edge);

		ItemStack stack = stackOf(e.itemId());
		if (!stack.isEmpty()) {
			dc.drawItem(stack, x + 3, y + 3);
		} else {
			// 图标缺失（模组没装）：占位方块 + 首字母，别留空洞
			dc.fill(x + 3, y + 4, x + 15, y + 16, HubTheme.SLOT);
			String ch = shortId(e.itemId()).substring(0, 1).toUpperCase(Locale.ROOT);
			dc.drawText(tr, Text.literal(ch), x + 9 - tr.getWidth(ch) / 2, y + 6, HubTheme.TEXT, false);
		}

		String name = stack.isEmpty() ? shortId(e.itemId()) : stack.getName().getString();
		String st = e.stock() < 0 ? "" : (e.stock() == 0 ? "缺货" : String.valueOf(e.stock()));
		int stW = st.isEmpty() ? 0 : tr.getWidth(st) + 5;
		int priceW = HubMoney.width(tr, e.buyPrice());
		int nameMax = w - 22 - priceW - stW - 10;
		dc.drawText(tr, Text.literal(trim(tr, name, Math.max(24, nameMax))), x + 22, y + 7,
				HubTheme.TEXT, false);
		HubMoney.draw(dc, tr, x + w - 4 - stW - priceW, y + 7, e.buyPrice(), priceW + 2);
		if (!st.isEmpty()) {
			dc.drawText(tr, Text.literal(st), x + w - 4 - tr.getWidth(st), y + 7,
					e.stock() == 0 ? HubTheme.DANGER : HubTheme.MUTED, false);
		}
	}

	private static void drawFilterBar(DrawContext dc, TextRenderer tr, int x, int y, int w,
			ShopProtocol.Page page, double mouseX, double mouseY) {
		com.akira.redtale.economy.net.ShopFilter f = ShopClientState.filter();
		String[] labels = {modLabel(page, f), catLabel(f), priceLabel(f), stockLabel(f),
				densityLabel(), viewLabel()};
		int[] widths = chipWidths(tr, page);
		int cx = x;
		for (int i = 0; i < labels.length; i++) {
			if (cx + widths[i] > x + w) break;
			boolean active = switch (i) {
				case 0 -> !f.modOrEmpty().isEmpty();
				case 1 -> !f.categoryOrEmpty().isEmpty();
				case 2 -> f.maxPrice() > 0;
				case 3 -> f.inStockOnly();
				default -> false;   // T69 密度档不是筛选条件，不高亮
			};
			boolean hover = mouseX >= cx && mouseX < cx + widths[i] && mouseY >= y && mouseY < y + 14;
			int bg = active ? HubTheme.PRIMARY_SOFT : (hover ? HubTheme.INNER_HOVER : HubTheme.BAR_SUB);
			dc.fill(cx, y, cx + widths[i], y + 14, bg);
			dc.fill(cx, y, cx + widths[i], y + 1, active ? HubTheme.GOLD : HubTheme.LINE);
			dc.drawText(tr, Text.literal(labels[i]), cx + 5, y + 3,
					active ? HubTheme.PRIMARY_SOFT_TEXT : HubTheme.MUTED, false);
			cx += widths[i] + 4;
		}
	}

	/**
	 * 仅 dev 夹具用：第 {@code index} 个筛选 chip 的中心（喂给真实 mouseClicked）。
	 *
	 * <p>chip 宽度随文字变（`格:6×4` 比 `格:自动` 宽），夹具自己算坐标必然点歪；
	 * 这里复用渲染同一份 {@link #chipWidths} 与「放不下就停」规则，越界返回 {@code null}。
	 */
	public static double[] chipCenter(TextRenderer tr, int x, int y, int w, int index) {
		int[] widths = chipWidths(tr, ShopClientState.page());
		if (index < 0 || index >= widths.length) return null;
		int cx = x;
		for (int i = 0; i < widths.length; i++) {
			if (cx + widths[i] > x + w) return null;
			if (i == index) return new double[]{cx + widths[i] / 2.0, y + 20 + 7};
			cx += widths[i] + 4;
		}
		return null;
	}

	/** 目录树列：面包屑（可点回上级）+ 当前层的子分类。 */
	private static void drawTree(DrawContext dc, TextRenderer tr, int x, int y, int w, int h,
			ShopProtocol.Page page, double mouseX, double mouseY) {
		String path = treePath();
		List<String> cats = page.categories();
		dc.fill(x, y, x + w, y + h, HubTheme.INNER);
		dc.fill(x + w - 1, y, x + w, y + h, HubTheme.LINE);

		// 面包屑：显示当前路径，非根时可点（点它 = 回上一级）
		boolean upHover = !path.isEmpty()
				&& mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + TREE_HEAD;
		dc.fill(x, y, x + w, y + TREE_HEAD, upHover ? HubTheme.PRIMARY_SOFT : HubTheme.BAR_SUB);
		dc.fill(x, y + TREE_HEAD - 1, x + w, y + TREE_HEAD, HubTheme.LINE);
		String crumb = path.isEmpty() ? "全部分类" : breadcrumbText(path);
		String shown = (path.isEmpty() ? "" : "‹ ") + crumb;
		dc.drawText(tr, Text.literal(trim(tr, shown, w - 8)), x + 4, y + 5,
				path.isEmpty() ? HubTheme.MUTED : HubTheme.GOLD, false);

		List<String> kids = ShopTree.children(cats, path);
		if (kids.isEmpty()) {
			dc.drawText(tr, Text.literal(path.isEmpty() ? "（无分类）" : "（无下级）"),
					x + 4, y + TREE_HEAD + 4, HubTheme.HINT, false);
			return;
		}
		for (int i = 0; i < kids.size(); i++) {
			int ry = y + TREE_HEAD + i * TREE_ROW;
			if (ry + TREE_ROW > y + h) break;
			String name = kids.get(i);
			String child = ShopTree.join(path, name);
			boolean hasKid = ShopTree.hasChildren(cats, child);
			boolean hover = mouseX >= x && mouseX < x + w && mouseY >= ry && mouseY < ry + TREE_ROW;
			if (hover) dc.fill(x + 1, ry, x + w - 2, ry + TREE_ROW, HubTheme.INNER_HOVER);
			// ★用 › 而不是 ▸★：默认字体没有 ▸，会渲染成豆腐块（u6-02 截图可见）
			String line = (hasKid ? "› " : "· ") + name;
			dc.drawText(tr, Text.literal(trim(tr, line, w - 8)), x + 4, ry + 3,
					hasKid ? HubTheme.TEXT : HubTheme.MUTED, false);
		}
	}

	/** 面包屑文字：`建材 › 木材`（整条太长时由 trim 截断，最深的段会先看不到）。 */
	private static String breadcrumbText(String path) {
		return String.join(" › ", ShopTree.crumbs(path));
	}
	/**
	 * 网格几何（T54）：返回 {@code {列数, 格子边长, 可见行数}}。
	 *
	 * <p>★渲染与命中必须都调它★：格子边长随窗口变，抄成两套数字必然点错格。
	 */
	/** T69：自动档能放几列（按最小可点格子反推，封顶 12）。 */
	private static int autoCols(int gridW) {
		return Math.max(1, Math.min(12, (gridW + GAP) / (CELL_MIN + GAP)));
	}

	/** T69：自动档能放几行（同上，封顶 8）。 */
	private static int autoRows(int availH) {
		return Math.max(1, Math.min(8, (availH + GAP) / (CELL_MIN + GAP)));
	}

	private static int colsTarget(int gridW) {
		int c = ClientUiConfig.shopCols;
		return c > 0 ? Math.min(12, c) : autoCols(gridW);
	}

	private static int rowsTarget(int availH) {
		int r = ClientUiConfig.shopRows;
		return r > 0 ? Math.min(8, r) : autoRows(availH);
	}

	/** 当前档位的显示名（渲染与点击共用）。 */
	public static String densityLabel() {
		int c = ClientUiConfig.shopCols;
		int r = ClientUiConfig.shopRows;
		return (c <= 0 || r <= 0) ? "格:自动" : "格:" + c + "×" + r;
	}

	/** T69b：当前视图模式（0 = 图标网格）。 */
	public static boolean listMode() {
		return ClientUiConfig.shopView == 1;
	}

	/** T69b：视图 chip 的显示名（渲染与点击共用）。 */
	public static String viewLabel() {
		return listMode() ? "视图:列表" : "视图:图标";
	}

	private static int[] dims(int gridW, int availH) {
		int cols = colsTarget(gridW);
		int rows = rowsTarget(availH);
		int cell = cellSize(gridW, availH, cols, rows);
		// 窄到格子不可点时先减列，保证最小格还能点
		while (cols > 1 && cell < CELL_MIN) {
			cols--;
			cell = cellSize(gridW, availH, cols, rows);
		}
		cell = Math.max(CELL_MIN, Math.min(CELL_MAX, cell));
		// 实际能塞下的行数（格子被 CELL_MIN 撑大时可能比档位少）
		int fitRows = Math.max(1, (availH + GAP) / (cell + GAP));
		rows = Math.max(1, Math.min(rows, fitRows));
		return new int[]{cols, cell, rows};
	}

	/** 给定行列能给出的最大正方形边长（宽/高取小）。 */
	private static int cellSize(int gridW, int availH, int cols, int rows) {
		int byW = (gridW - Math.max(0, cols - 1) * GAP) / Math.max(1, cols);
		int byH = (availH - Math.max(0, rows - 1) * GAP) / Math.max(1, rows);
		return Math.min(byW, byH);
	}

	/** 网格起点 y（搜索行 18 + 筛选栏 18 + 卡片区顶部 2）。 */
	private static int gridStartY(int y) {
		return y + 38;
	}

	/** 卡片区底部边界：再往下是分页条（26 高）+ 8 px 留白。 */
	private static int gridLimitY(int y, int h) {
		return y + h - 34;
	}

	/** 当前允许的最大滚动行数（0 = 不用滚）。 */
	private static int maxScrollRow(int w, int y, int h) {
		ShopProtocol.Page page = ShopClientState.page();
		int entries = page == null || page.entries() == null ? 0 : page.entries().size();
		int availH = gridLimitY(y, h) - gridStartY(y);
		if (listMode()) {
			return Math.max(0, entries - listRows(availH));
		}
		int[] d = dims(gridW(w, page), availH);
		int totalRows = (entries + d[0] - 1) / d[0];
		return Math.max(0, totalRows - d[2]);
	}

	/**
	 * 光标落在第几格（-1 = 没命中）。
	 *
	 * <p>渲染、hover 浮层、点击三处共用同一份几何 —— 以前渲染和命中各抄一套，
	 * 改卡片宽度就点错格。落在列/行的空隙里也算没命中，避免「点缝也选中」。
	 */
	private static int indexAt(ShopProtocol.Page page, double mx, double my, int gx, int gw,
			int startY, int limitY, int[] pre) {
		if (page == null || page.entries().isEmpty()) return -1;
		if (listMode()) return listIndexAt(page, mx, my, gx, gw, startY, limitY);
		int[] d = pre == null ? dims(gw, limitY - startY) : pre;
		int cols = d[0];
		int cell = d[1];
		int rowCap = d[2];
		int blockW = cols * cell + (cols - 1) * GAP;
		int gx0 = gx + Math.max(0, (gw - blockW) / 2);
		if (mx < gx0 || my < startY || my >= limitY) return -1;
		int col = (int) ((mx - gx0) / (cell + GAP));
		if (col < 0 || col >= cols) return -1;
		if (mx - (gx0 + col * (cell + GAP)) >= cell) return -1;
		int row = (int) ((my - startY) / (cell + GAP));
		if (row < 0 || row >= rowCap) return -1;
		if (my - (startY + row * (cell + GAP)) >= cell) return -1;
		int idx = (row + scrollRow) * cols + col;
		if (idx < 0 || idx >= page.entries().size()) return -1;
		return idx;
	}

	/** T69b：列表模式一屏能放几行（22+2 行距）。 */
	private static int listRows(int availH) {
		return Math.max(1, (availH + LIST_GAP) / (LIST_ROW + LIST_GAP));
	}

	/**
	 * T69b：列表模式的光标命中（-1 = 没命中）。
	 *
	 * <p>与 {@link #renderList} 同一套 {@link #listRows}/{@link #scrollRow}：
	 * 行间距 {@code LIST_ROW + LIST_GAP}，落在 2 px 空隙里算没命中（与网格口径一致）。
	 */
	private static int listIndexAt(ShopProtocol.Page page, double mx, double my, int gx, int gw,
			int startY, int limitY) {
		int rowW = gw - 6;
		if (mx < gx || mx >= gx + rowW || my < startY || my >= limitY) return -1;
		int row = (int) ((my - startY) / (LIST_ROW + LIST_GAP));
		if (row < 0 || row >= listRows(limitY - startY)) return -1;
		if (my - (startY + row * (LIST_ROW + LIST_GAP)) >= LIST_ROW) return -1;
		int idx = row + scrollRow;
		if (idx < 0 || idx >= page.entries().size()) return -1;
		return idx;
	}

	/** 滚 {@code rows} 行（正数向下），自动钳到 [0, max]。 */	public static void scrollByRows(int rows, int w, int y, int h) {
		scrollRow = Math.max(0, Math.min(maxScrollRow(w, y, h), scrollRow + rows));
	}

	/** 换页/换筛选时回到顶部，否则新一页只有 4 条却停在旧位置会看起来空。 */
	public static void resetScroll() {
		scrollRow = 0;
	}

	/** dev 夹具用：当前滚动行（写进截图日志作证据）。 */
	public static int devScrollRow() {
		return scrollRow;
	}

	/**
	 * 一个图标格（T54）：物品图标居中 + 右下角数量角标。
	 *
	 * <p>价格/含税/库存不在格子里画 —— 40 px 的格子塞四行字必然压字，
	 * 统一走 {@link #renderHoverOverlay} 的 hover 浮层。
	 */
	private static void drawCell(DrawContext dc, TextRenderer tr, int x, int y, int cell,
			ShopProtocol.Entry e, boolean selected, double mouseX, double mouseY) {
		boolean hover = mouseX >= x && mouseX < x + cell && mouseY >= y && mouseY < y + cell;
		dc.fill(x, y, x + cell, y + cell, selected ? HubTheme.SELECT : HubTheme.CARD);
		int edge = selected ? HubTheme.GOLD : (hover ? HubTheme.EDGE_HOVER : HubTheme.LINE);
		dc.fill(x, y, x + cell, y + 1, edge);
		dc.fill(x, y + cell - 1, x + cell, y + cell, edge);
		dc.fill(x, y, x + 1, y + cell, edge);
		dc.fill(x + cell - 1, y, x + cell, y + cell, edge);

		ItemStack stack = stackOf(e.itemId());
		if (!stack.isEmpty()) {
			dc.drawItem(stack, x + (cell - 16) / 2, y + (cell - 16) / 2);
		} else {
			// 图标缺失（模组没装）：画一个占位方块 + 首字母，不要留空洞
			int s = 12;
			int ix = x + (cell - s) / 2;
			int iy = y + (cell - s) / 2;
			dc.fill(ix, iy, ix + s, iy + s, HubTheme.SLOT);
			String ch = shortId(e.itemId()).substring(0, 1).toUpperCase(Locale.ROOT);
			dc.drawText(tr, Text.literal(ch), ix + (s - tr.getWidth(ch)) / 2, iy + 2,
					HubTheme.TEXT, false);
		}

		// 数量角标（库存）：无限库存不画（以前写「无限」，两个汉字在小格里放不下）
		if (e.stock() >= 0 && cell >= 24) {
			String badge = e.stock() >= 10000 ? (e.stock() / 1000) + "k" : String.valueOf(e.stock());
			@SuppressWarnings("unused")
			int bw = tr.getWidth(badge);
			int bx = x + cell - 2 - bw;
			int by = y + cell - 10;
			// 角标压在图标右下，先垫一层底再画字（否则深色图标上读不清）
			dc.fill(bx - 1, by - 1, bx + bw + 1, by + 9, 0xB0000000);
			dc.drawText(tr, Text.literal(badge), bx, by,
					e.stock() == 0 ? HubTheme.DANGER : HubTheme.TEXT, false);
		}
	}

	/**
	 * hover 浮层（T54）：名字 / 买价（含税）/ 卖价 / 库存。
	 *
	 * <p>★必须最后画★（硬规则：浮层最后画）—— 画在格子里会被相邻格和分页条盖住。
	 */
	public static void renderHoverOverlay(DrawContext dc, TextRenderer tr, double mx, double my,
			int x, int y, int w, int h) {
		ShopProtocol.Page page = ShopClientState.page();
		if (page == null || page.entries().isEmpty()) return;
		int gx = gridX(x, page);
		int gw = gridW(w, page);
		int startY = gridStartY(y);
		int limitY = gridLimitY(y, h);
		int[] d = dims(gw, limitY - startY);
		int idx = indexAt(page, mx, my, gx, gw, startY, limitY, d);
		if (idx < 0) return;
		ShopProtocol.Entry e = page.entries().get(idx);

		ItemStack stack = stackOf(e.itemId());
		String name = stack.isEmpty() ? shortId(e.itemId()) : stack.getName().getString();
		long tax = com.akira.redtale.economy.client.ShopClientState.tax()
				.buyTax(e.itemId(), e.buyPrice());
		boolean hasTax = tax > 0;

		int lines = hasTax ? 4 : 3;
		int boxW = Math.max(112, Math.min(190, tr.getWidth(name) + 16));
		int boxH = lines * 11 + 6;
		int bx = (int) mx + 12;
		int by = (int) my + 6;
		// 贴近右/下边界时翻到另一侧，别画出面板
		if (bx + boxW > x + w) bx = (int) mx - boxW - 8;
		if (bx < x) bx = x;
		if (by + boxH > y + h) by = y + h - boxH - 2;
		if (by < y) by = y;

		dc.fill(bx, by, bx + boxW, by + boxH, HubTheme.PANEL);
		dc.fill(bx, by, bx + boxW, by + 1, HubTheme.OUTLINE);
		dc.fill(bx, by + boxH - 1, bx + boxW, by + boxH, HubTheme.OUTLINE);
		dc.fill(bx, by, bx + 1, by + boxH, HubTheme.OUTLINE);
		dc.fill(bx + boxW - 1, by, bx + boxW, by + boxH, HubTheme.OUTLINE);

		int ty = by + 4;
		dc.drawText(tr, Text.literal(trim(tr, name, boxW - 10)), bx + 5, ty, HubTheme.TEXT, false);
		ty += 11;
		dc.drawText(tr, Text.literal("买"), bx + 5, ty, HubTheme.MUTED, false);
		HubMoney.draw(dc, tr, bx + 17, ty, e.buyPrice(), boxW - 22);
		ty += 11;
		if (hasTax) {
			dc.drawText(tr, Text.literal("含税"), bx + 5, ty, HubTheme.MUTED, false);
			HubMoney.draw(dc, tr, bx + 27, ty, e.buyPrice() + tax, boxW - 32);
			ty += 11;
		}
		dc.drawText(tr, Text.literal("卖"), bx + 5, ty, HubTheme.MUTED, false);
		HubMoney.draw(dc, tr, bx + 17, ty, e.sellPrice(), boxW - 22);
	}

	private static void drawMini(DrawContext dc, TextRenderer tr, int x, int y, int w, int h,
			String label, boolean enabled, double mouseX, double mouseY) {
		boolean hover = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
		dc.fill(x, y, x + w, y + h, enabled ? (hover ? HubTheme.PRIMARY_SOFT_HOVER : HubTheme.PRIMARY_SOFT) : HubTheme.DISABLED_BG);
		dc.fill(x, y, x + w, y + 1, enabled ? HubTheme.GOLD : HubTheme.LINE);
		dc.fill(x, y + h - 1, x + w, y + h, enabled ? HubTheme.GOLD : HubTheme.LINE);
		dc.drawText(tr, Text.literal(label), x + (w - tr.getWidth(label)) / 2, y + 3,
				enabled ? HubTheme.PRIMARY_SOFT_TEXT : HubTheme.DISABLED_TEXT, false);
	}

	private static ItemStack stackOf(String itemId) {
		try {
			Identifier id = new Identifier(itemId);
			Item item = Registries.ITEM.get(id);
			if (item == null || item == net.minecraft.item.Items.AIR) return ItemStack.EMPTY;
			return new ItemStack(item);
		} catch (Exception ex) {
			return ItemStack.EMPTY;
		}
	}

	private static String shortId(String itemId) {
		String s = itemId;
		int i = s.indexOf(':');
		if (i >= 0) s = s.substring(i + 1);
		return s;
	}

	private static String trim(TextRenderer tr, String s, int maxW) {
		if (tr.getWidth(s) <= maxW) return s;
		String out = s;
		while (out.length() > 1 && tr.getWidth(out + "…") > maxW) {
			out = out.substring(0, out.length() - 1);
		}
		return out + "…";
	}

	/**
	 * 点击：目录树 → 筛选栏 → 卡片 → 底部按钮。
	 * 返回动作；选中的条目下标经 {@code outIndex} 带回，
	 * 目录树的目标路径经 {@code outCat} 带回（{@code TREE_UP} 时是上一级）。
	 */
	public static Action click(TextRenderer tr, double mx, double my, int x, int y, int w, int h,
			int selected, int[] outIndex, String[] outCat) {
		ShopProtocol.Page page = ShopClientState.page();
		// 目录树在最左，先判；命中区不与卡片重叠（卡片区已右移）
		if (treeWidth(page) > 0 && mx >= x && mx < x + TREE_W
				&& my >= y + 38 && my < y + h - 26) {
			String path = treePath();
			int ty = y + 38;
			if (my < ty + TREE_HEAD) {
				if (path.isEmpty()) return Action.NONE;
				outCat[0] = ShopTree.parent(path);
				return Action.TREE_UP;
			}
			int row = (int) ((my - ty - TREE_HEAD) / TREE_ROW);
			List<String> kids = ShopTree.children(page.categories(), path);
			if (row >= 0 && row < kids.size()) {
				outCat[0] = ShopTree.join(path, kids.get(row));
				return Action.TREE_NODE;
			}
			return Action.NONE;
		}
		// 筛选栏优先（在卡片上方，命中区不重叠）
		if (my >= y + 20 && my < y + 34) {
			int[] widths = chipWidths(tr, page);
			Action[] acts = {Action.FILTER_MOD, Action.FILTER_CAT, Action.FILTER_PRICE,
					Action.FILTER_STOCK, Action.DENSITY, Action.VIEW};
			int cx = x;
			for (int i = 0; i < acts.length; i++) {
				// 与 drawFilterBar 同一条「放不下就停」规则，否则窄窗口下会点到画不出来的 chip
				if (cx + widths[i] > x + w) break;
				if (mx >= cx && mx < cx + widths[i]) return acts[i];
				cx += widths[i] + 4;
			}
			return Action.NONE;
		}
		int gx = gridX(x, page);
		int gw = gridW(w, page);
		int hit = indexAt(page, mx, my, gx, gw, gridStartY(y), gridLimitY(y, h), null);
		if (hit >= 0) {
			outIndex[0] = hit;
			return Action.NONE;
		}
		// 搜索行右端的「同步」（渲染位置 x+w-30 起）
		if (my >= y && my < y + 18 && mx >= x + w - 40) return Action.SYNC;
		int barY = y + h - 26;
		if (my >= barY && my <= barY + 22) {
			boolean hasSel = selected >= 0 && selected < page.entries().size();
			// 与 drawMini 的坐标一一对应：买 = -40..-22，卖 = -20..-2
			if (mx >= x + w - 40 && mx <= x + w - 22) return hasSel ? Action.BUY : Action.NONE;
			if (mx >= x + w - 20 && mx <= x + w - 2) return hasSel ? Action.SELL : Action.NONE;
			if (mx >= x + w - 100 && mx <= x + w - 84) return Action.PREV;
			if (mx >= x + w - 64 && mx <= x + w - 48) return Action.NEXT;
		}
		return Action.NONE;
	}

	public static String normalize(String q) {
		return q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
	}

	/**
	 * 仅 dev 夹具用：目录树第 {@code row} 行的中心坐标（喂给真实 mouseClicked）。
	 * 没有树或越界返回 {@code null}。
	 */
	public static double[] treeRowCenter(int x, int y, int h, int row) {
		ShopProtocol.Page page = ShopClientState.page();
		if (treeWidth(page) == 0) return null;
		List<String> kids = ShopTree.children(page.categories(), treePath());
		if (row < 0 || row >= kids.size()) return null;
		double cy = y + 38 + TREE_HEAD + row * TREE_ROW + TREE_ROW / 2.0;
		if (cy > y + h - 26) return null;
		return new double[]{x + TREE_W / 2.0, cy};
	}
}
