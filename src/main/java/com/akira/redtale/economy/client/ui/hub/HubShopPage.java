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

	private static final int CARD_W = 96;
	private static final int CARD_H = 58;
	private static final int GAP = 4;
	/** 目录树列宽；没有分类时整列不画，卡片区自动占满。 */
	private static final int TREE_W = 96;
	private static final int TREE_HEAD = 17;
	private static final int TREE_ROW = 14;

		
	private HubShopPage() {
	}

	public enum Action {
		NONE, SYNC, BUY, SELL, PREV, NEXT,
		/** U11b：四个筛选维度，点击循环取值（含"全部"） */
		FILTER_MOD, FILTER_CAT, FILTER_PRICE, FILTER_STOCK,
		/** U11：点目录树节点（下钻）/ 点面包屑回上一级 */
		TREE_NODE, TREE_UP
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

	/** 价格档位上限（铜）；0 = 不限。点击依次循环。 */
	private static final long[] PRICE_STEPS = {0, 10, 100, 1_000, 10_000, 100_000};

	/** 四个筛选 chip 的布局（宽随文字变，但两处必须算得一样）。 */
	private static int[] chipWidths(TextRenderer tr, ShopProtocol.Page page) {
		com.akira.redtale.economy.net.ShopFilter f = ShopClientState.filter();
		return new int[]{
				tr.getWidth(modLabel(page, f)) + 10,
				tr.getWidth(catLabel(f)) + 10,
				tr.getWidth(priceLabel(f)) + 10,
				tr.getWidth(stockLabel(f)) + 10,
		};
	}

	private static String modLabel(ShopProtocol.Page page, com.akira.redtale.economy.net.ShopFilter f) {
		return "mod:" + (f.modOrEmpty().isEmpty() ? "全部" : f.modOrEmpty());
	}

	private static String catLabel(com.akira.redtale.economy.net.ShopFilter f) {
		String path = f.categoryOrEmpty();
		// 树里选的是路径（木材/台阶），chip 上只显示末段，否则宽度吃掉整条筛选栏
		return "类型:" + (path.isEmpty() ? "全部" : ShopTree.label(path));
	}

	private static String priceLabel(com.akira.redtale.economy.net.ShopFilter f) {
		return f.maxPrice() <= 0 ? "价格:不限" : "价格:≤" + f.maxPrice();
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

		// 卡片：列数按最小宽算，再把每张卡等比拉宽铺满整行（§37.2 对齐网格）
		int gx = gridX(x, page);
		int gw = gridW(w, page);
		int cols = Math.max(1, (gw + GAP) / (CARD_W + GAP));
		int cw = cardW(gw);
		int startY = y + 38;
		List<ShopProtocol.Entry> entries = page.entries();
		for (int i = 0; i < entries.size(); i++) {
			int col = i % cols;
			int row = i / cols;
			int cx = gx + col * (cw + GAP);
			int cy = startY + row * (CARD_H + GAP);
			if (cy + CARD_H > y + h - 34) break;
			drawCard(dc, tr, cx, cy, cw, entries.get(i), i == selected, mouseX, mouseY);
		}
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

	private static void drawFilterBar(DrawContext dc, TextRenderer tr, int x, int y, int w,
			ShopProtocol.Page page, double mouseX, double mouseY) {
		com.akira.redtale.economy.net.ShopFilter f = ShopClientState.filter();
		String[] labels = {modLabel(page, f), catLabel(f), priceLabel(f), stockLabel(f)};
		int[] widths = chipWidths(tr, page);
		int cx = x;
		for (int i = 0; i < labels.length; i++) {
			if (cx + widths[i] > x + w) break;
			boolean active = switch (i) {
				case 0 -> !f.modOrEmpty().isEmpty();
				case 1 -> !f.categoryOrEmpty().isEmpty();
				case 2 -> f.maxPrice() > 0;
				default -> f.inStockOnly();
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
	/** 卡片实际宽度：列数固定后平均分配，保证最右一张顶到内容区右边界。 */
	private static int cardW(int w) {
		int cols = Math.max(1, (w + GAP) / (CARD_W + GAP));
		return (w - (cols - 1) * GAP) / cols;
	}

	private static void drawCard(DrawContext dc, TextRenderer tr, int x, int y, int cw,
			ShopProtocol.Entry e, boolean selected, double mouseX, double mouseY) {
		boolean hover = mouseX >= x && mouseX < x + cw && mouseY >= y && mouseY < y + CARD_H;
		dc.fill(x, y, x + cw, y + CARD_H, selected ? HubTheme.SELECT : HubTheme.CARD);
		int edge = selected ? HubTheme.GOLD : (hover ? HubTheme.EDGE_HOVER : HubTheme.LINE);
		dc.fill(x, y, x + cw, y + 1, edge);
		dc.fill(x, y + CARD_H - 1, x + cw, y + CARD_H, edge);
		dc.fill(x, y, x + 1, y + CARD_H, edge);
		dc.fill(x + cw - 1, y, x + cw, y + CARD_H, edge);

		ItemStack stack = stackOf(e.itemId());
		if (!stack.isEmpty()) {
			dc.drawItem(stack, x + 4, y + 4);
		} else {
			dc.fill(x + 4, y + 4, x + 20, y + 20, HubTheme.SLOT);
		}
		String name = stack.isEmpty() ? shortId(e.itemId()) : stack.getName().getString();
		dc.drawText(tr, Text.literal(trim(tr, name, cw - 26)), x + 22, y + 6, HubTheme.TEXT, false);
		dc.drawText(tr, Text.literal(e.buyPrice() + " 铜"), x + 4, y + 26, HubTheme.GOLD, false);

		String stock = e.stock() < 0 ? "无限" : ("库存 " + e.stock());
		int color = e.stock() == 0 ? HubTheme.DANGER : HubTheme.MUTED;
		dc.drawText(tr, Text.literal(trim(tr, stock, cw - 8)), x + 4, y + 40, color, false);
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
			Action[] acts = {Action.FILTER_MOD, Action.FILTER_CAT, Action.FILTER_PRICE, Action.FILTER_STOCK};
			int cx = x;
			for (int i = 0; i < 4; i++) {
				if (mx >= cx && mx < cx + widths[i]) return acts[i];
				cx += widths[i] + 4;
				if (cx > x + w) break;
			}
			return Action.NONE;
		}
		int gx = gridX(x, page);
		int gw = gridW(w, page);
		int cols = Math.max(1, (gw + GAP) / (CARD_W + GAP));
		int cw = cardW(gw);
		int startY = y + 38;
		List<ShopProtocol.Entry> entries = page.entries();
		for (int i = 0; i < entries.size(); i++) {
			int cx = gx + (i % cols) * (cw + GAP);
			int cy = startY + (i / cols) * (CARD_H + GAP);
			if (cy + CARD_H > y + h - 34) break;
			if (mx >= cx && mx < cx + cw && my >= cy && my < cy + CARD_H) {
				outIndex[0] = i;
				return Action.NONE;
			}
		}
		// 搜索行右端的「同步」（渲染位置 x+w-30 起）
		if (my >= y && my < y + 18 && mx >= x + w - 40) return Action.SYNC;
		int barY = y + h - 26;
		if (my >= barY && my <= barY + 22) {
			boolean hasSel = selected >= 0 && selected < entries.size();
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
