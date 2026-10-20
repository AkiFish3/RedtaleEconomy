package com.akira.redtale.economy.client.ui.hub;

import com.akira.redtale.economy.client.ShopClientState;
import com.akira.redtale.economy.net.ShopProtocol;
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
 * Hub 内的商店页（U11 第一步：卡片列表 + 搜索 + 分页）。
 *
 * <p>数据来自 {@link ShopClientState}（服务端下发的分页缓存，客户端不信任何数值）。
 * 分类树 / 多维筛选在下一版补，这里先把列表和搜索跑通。
 */
public final class HubShopPage {

	private static final int CARD_W = 96;
	private static final int CARD_H = 58;
	private static final int GAP = 4;

	private static final int C_CARD_SEL = 0xFF332B18;
	private static final int C_RED = 0xFFC0605A;

	private HubShopPage() {
	}

	public enum Action {
		NONE, SYNC, BUY, SELL, PREV, NEXT,
		/** U11b：四个筛选维度，点击循环取值（含"全部"） */
		FILTER_MOD, FILTER_CAT, FILTER_PRICE, FILTER_STOCK
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
		return "类型:" + (f.categoryOrEmpty().isEmpty() ? "全部" : f.categoryOrEmpty());
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
		dc.fill(x, y, x + w - 40, y + 18, 0xFF151515);
		dc.fill(x, y + 17, x + w - 40, y + 18, HubTheme.LINE);
		String shown = query.isEmpty() ? "搜索：id 或名称（空格分隔）" : query;
		dc.drawText(tr, Text.literal(shown), x + 4, y + 5,
				query.isEmpty() ? 0xFF7A7A7A : HubTheme.TEXT, false);
		dc.drawText(tr, Text.literal("同步"), x + w - 30, y + 5, HubTheme.MUTED, false);

		// U11b：筛选栏（mod / 类型 / 价格 / 有货）—— 点击循环取值，与搜索词叠加
		drawFilterBar(dc, tr, x, y + 20, w, page, mouseX, mouseY);

		// 卡片
		int cols = Math.max(1, (w + GAP) / (CARD_W + GAP));
		int startY = y + 38;
		List<ShopProtocol.Entry> entries = page.entries();
		for (int i = 0; i < entries.size(); i++) {
			int col = i % cols;
			int row = i / cols;
			int cx = x + col * (CARD_W + GAP);
			int cy = startY + row * (CARD_H + GAP);
			if (cy + CARD_H > y + h - 34) break;
			drawCard(dc, tr, cx, cy, entries.get(i), i == selected, mouseX, mouseY);
		}
		if (entries.isEmpty()) {
			boolean filtered = !ShopClientState.filter().isEmpty();
			dc.drawText(tr, Text.literal(filtered ? "没有符合筛选条件的商品" : "暂无商品"),
					x + 6, startY + 8, HubTheme.MUTED, false);
			if (filtered) {
				dc.drawText(tr, Text.literal("点上方筛选项可循环切换 / 清除"),
						x + 6, startY + 22, 0xFF6A6A6A, false);
			} else {
				dc.drawText(tr, Text.literal("这个商店还没有上架商品（管理员：/shop admin add <商店> <物品> <买价> <卖价>）"),
						x + 6, startY + 22, 0xFF6A6A6A, false);
			}
		}

		// 底部：分页 + 选中项操作
		int barY = y + h - 26;
		dc.fill(x, barY, x + w, barY + 22, 0xFF232323);
		String pg = "第 " + (page.page() + 1) + " / " + Math.max(1, page.totalPages())
				+ " 页 · 共 " + page.totalItems() + " 项";
		dc.drawText(tr, Text.literal(pg), x + 6, barY + 7, HubTheme.MUTED, false);
		dc.drawText(tr, Text.literal("‹"), x + w - 96, barY + 7, HubTheme.MUTED, false);
		dc.drawText(tr, Text.literal("›"), x + w - 60, barY + 7, HubTheme.MUTED, false);
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
			int bg = active ? 0xFF3A3020 : (hover ? 0xFF2E2E2E : 0xFF232323);
			dc.fill(cx, y, cx + widths[i], y + 14, bg);
			dc.fill(cx, y, cx + widths[i], y + 1, active ? HubTheme.GOLD : HubTheme.LINE);
			dc.drawText(tr, Text.literal(labels[i]), cx + 5, y + 3,
					active ? 0xFFFFE9A8 : HubTheme.MUTED, false);
			cx += widths[i] + 4;
		}
	}

	private static void drawCard(DrawContext dc, TextRenderer tr, int x, int y,
			ShopProtocol.Entry e, boolean selected, double mouseX, double mouseY) {
		boolean hover = mouseX >= x && mouseX < x + CARD_W && mouseY >= y && mouseY < y + CARD_H;
		dc.fill(x, y, x + CARD_W, y + CARD_H, selected ? C_CARD_SEL : HubTheme.CARD);
		int edge = selected ? HubTheme.GOLD : (hover ? 0xFF5A5A5A : HubTheme.LINE);
		dc.fill(x, y, x + CARD_W, y + 1, edge);
		dc.fill(x, y + CARD_H - 1, x + CARD_W, y + CARD_H, edge);
		dc.fill(x, y, x + 1, y + CARD_H, edge);
		dc.fill(x + CARD_W - 1, y, x + CARD_W, y + CARD_H, edge);

		ItemStack stack = stackOf(e.itemId());
		if (!stack.isEmpty()) {
			dc.drawItem(stack, x + 4, y + 4);
		} else {
			dc.fill(x + 4, y + 4, x + 20, y + 20, 0xFF444444);
		}
		String name = stack.isEmpty() ? shortId(e.itemId()) : stack.getName().getString();
		dc.drawText(tr, Text.literal(trim(tr, name, CARD_W - 26)), x + 22, y + 6, HubTheme.TEXT, false);
		dc.drawText(tr, Text.literal(e.buyPrice() + " 铜"), x + 4, y + 26, HubTheme.GOLD, false);

		String stock = e.stock() < 0 ? "无限" : ("库存 " + e.stock());
		int color = e.stock() == 0 ? C_RED : HubTheme.MUTED;
		dc.drawText(tr, Text.literal(trim(tr, stock, CARD_W - 8)), x + 4, y + 40, color, false);
	}

	private static void drawMini(DrawContext dc, TextRenderer tr, int x, int y, int w, int h,
			String label, boolean enabled, double mouseX, double mouseY) {
		boolean hover = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
		dc.fill(x, y, x + w, y + h, enabled ? (hover ? 0xFF4A3F22 : 0xFF3A3020) : 0xFF2A2A2A);
		dc.fill(x, y, x + w, y + 1, enabled ? HubTheme.GOLD : HubTheme.LINE);
		dc.fill(x, y + h - 1, x + w, y + h, enabled ? HubTheme.GOLD : HubTheme.LINE);
		dc.drawText(tr, Text.literal(label), x + (w - tr.getWidth(label)) / 2, y + 3,
				enabled ? 0xFFFFE9A8 : 0xFF6A6A6A, false);
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

	/** 点击：先判卡片，再判底部按钮。返回动作 + 通过 {@code out} 带回选中的条目下标。 */
	public static Action click(TextRenderer tr, double mx, double my, int x, int y, int w, int h,
			int selected, int[] outIndex) {
		ShopProtocol.Page page = ShopClientState.page();
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
		int cols = Math.max(1, (w + GAP) / (CARD_W + GAP));
		int startY = y + 38;
		List<ShopProtocol.Entry> entries = page.entries();
		for (int i = 0; i < entries.size(); i++) {
			int cx = x + (i % cols) * (CARD_W + GAP);
			int cy = startY + (i / cols) * (CARD_H + GAP);
			if (cy + CARD_H > y + h - 34) break;
			if (mx >= cx && mx < cx + CARD_W && my >= cy && my < cy + CARD_H) {
				outIndex[0] = i;
				return Action.NONE;
			}
		}
		int barY = y + h - 26;
		if (my >= barY && my <= barY + 22) {
			if (mx >= x + w - 34 && mx <= x + w - 20) return Action.SYNC;
			if (mx >= x + w - 20 && mx <= x + w - 2) {
				return selected >= 0 && selected < entries.size() ? Action.BUY : Action.NONE;
			}
			if (mx >= x + w - 100 && mx <= x + w - 84) return Action.PREV;
			if (mx >= x + w - 64 && mx <= x + w - 48) return Action.NEXT;
		}
		return Action.NONE;
	}

	public static String normalize(String q) {
		return q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
	}
}
