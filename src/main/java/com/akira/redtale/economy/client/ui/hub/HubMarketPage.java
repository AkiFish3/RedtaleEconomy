package com.akira.redtale.economy.client.ui.hub;

import com.akira.redtale.economy.client.market.MarketClientNetworking;
import com.akira.redtale.economy.market.MarketRules;
import com.akira.redtale.economy.net.MarketProtocol;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;

import java.util.List;

/**
 * Hub 内的市场页（R40）。
 *
 * <p>三段结构，自上而下：
 * <pre>
 * 筛选行： [全部] [我的]  ……  在售 N / 我的 a·b      同步
 * 挂出条： 手上物品图标 + 名字 + [价:x]（点击循环档位） + [挂出]
 * 列表：   图标 / 名称×数量 / 卖家 / 价格 / 剩余时间（点一行 = 选中）
 * 底栏：   第 x/y 页 ‹ › …  [买] [撤单]
 * </pre>
 *
 * <p>布局常量只在这里定义一次，render 与 click 都从同一组偏移算坐标 —— 历史上
 * 「看着能点其实点不到」全是两边各写一套坐标导致的。
 */
public final class HubMarketPage {

	private static final int ROW_H = 22;
	private static final int FILTER_H = 14;
	private static final int SELL_H = 26;

	/** 价格档位（铜，整单）。点一次往后走一档，到底回第一档。 */
	private static final long[] PRICE_STEPS = {10, 50, 100, 500, 1_000, 5_000, 10_000, 50_000, 100_000, 500_000};

	private static int priceIndex = 2;

	private HubMarketPage() {
	}

	public enum Action {
		NONE, TOGGLE_MINE, SYNC, CYCLE_PRICE, SELL, PREV, NEXT, BUY, CANCEL
	}

	public static long price() {
		return PRICE_STEPS[Math.max(0, Math.min(PRICE_STEPS.length - 1, priceIndex))];
	}

	public static void cyclePrice() {
		priceIndex = (priceIndex + 1) % PRICE_STEPS.length;
	}

	private static int listTop(int y) {
		return y + FILTER_H + 4 + SELL_H + 4;
	}

	private static int rowsVisible(int h) {
		return Math.max(1, (h - FILTER_H - SELL_H - 12 - 24) / ROW_H);
	}

	// ---------------------------------------------------------------- 渲染

	public static void render(DrawContext dc, TextRenderer tr, int x, int y, int w, int h,
			double mouseX, double mouseY, int selected) {
		MarketProtocol.Snapshot snap = MarketClientNetworking.LAST;
		List<MarketProtocol.Offer> offers = snap.offers();

		// 筛选行
		int cx = x;
		cx += chip(dc, tr, cx, y, "全部", !snap.mineOnly(), mouseX, mouseY);
		cx += 4;
		cx += chip(dc, tr, cx, y, "我的", snap.mineOnly(), mouseX, mouseY);
		String stat = "在售 " + snap.totalItems() + " · 我的 " + snap.myOpen() + "/" + snap.maxOpen()
				+ " · 佣金 " + trimNum(snap.commissionPercent()) + "%";
		// 统计贴在筛选 chips 右边；放不下就整句省略，不许压到「同步」上
		int statX = cx + 6;
		int statMax = (x + w - 34) - statX;
		if (statMax > 24) {
			dc.drawText(tr, Text.literal(trim(tr, stat, statMax)), statX, y + 3, HubTheme.MUTED, false);
		}
		dc.drawText(tr, Text.literal("同步"), x + w - 26, y + 3, HubTheme.MUTED, false);

		// 挂出条
		int sy = y + FILTER_H + 4;
		dc.fill(x, sy, x + w, sy + SELL_H, HubTheme.CARD);
		dc.fill(x, sy, x + w, sy + 1, HubTheme.LINE);
		var mc = MinecraftClient.getInstance();
		ItemStack hand = mc.player == null ? ItemStack.EMPTY : mc.player.getMainHandStack();
		if (!hand.isEmpty()) dc.drawItem(hand, x + 4, sy + 5);
		else dc.fill(x + 4, sy + 5, x + 20, sy + 21, 0xFF3A3A3A);
		String handName = hand.isEmpty() ? "手上没有物品" : hand.getName().getString() + " ×" + hand.getCount();
		dc.drawText(tr, Text.literal(trim(tr, handName, w - 120)), x + 24, sy + 6, HubTheme.TEXT, false);
		if (!hand.isEmpty()) {
			dc.drawText(tr, Text.literal(trim(tr, "/market sell <价格> 等价通道", w - 120)),
					x + 24, sy + 16, 0xFF6E6E6E, false);
		}
		// 价格 chip + 挂出按钮
		String priceLabel = "价:" + price();
		int pw = tr.getWidth(priceLabel) + 10;
		int px = x + w - 62 - pw;
		dc.fill(px, sy + 6, px + pw, sy + 20, 0xFF232323);
		dc.fill(px, sy + 6, px + pw, sy + 7, HubTheme.GOLD);
		dc.drawText(tr, Text.literal(priceLabel), px + 5, sy + 9, 0xFFFFE9A8, false);
		boolean canSell = !hand.isEmpty();
		mini(dc, tr, x + w - 58, sy + 6, 52, 14, "挂出", canSell, mouseX, mouseY, HubTheme.GOLD);

		// 列表
		int top = listTop(y);
		int rows = rowsVisible(h);
		int bottomLimit = y + h - 26;
		for (int i = 0; i < offers.size() && i < rows; i++) {
			int ry = top + i * ROW_H;
			if (ry + ROW_H > bottomLimit) break;
			drawRow(dc, tr, x, ry, w, offers.get(i), i == selected, mouseX, mouseY);
		}
		if (offers.isEmpty()) {
			dc.drawText(tr, Text.literal(snap.mineOnly() ? "你还没有挂单" : "市场里暂时没有货"),
					x + 6, top + 6, HubTheme.MUTED, false);
			dc.drawText(tr, Text.literal(trim(tr, "拿着物品 → 填价格 → 点「挂出」；也可以 /market sell <价格>",
					w - 12)), x + 6, top + 20, 0xFF6A6A6A, false);
		}

		// 底栏
		int barY = y + h - 24;
		dc.fill(x, barY, x + w, barY + 20, 0xFF232323);
		String pg = "第 " + (snap.totalPages() <= 0 ? 0 : snap.page() + 1) + " / " + Math.max(0, snap.totalPages())
				+ " 页";
		dc.drawText(tr, Text.literal(trim(tr, pg, w - 120)), x + 5, barY + 6, HubTheme.MUTED, false);
		dc.drawText(tr, Text.literal("‹"), x + w - 101, barY + 6, HubTheme.MUTED, false);
		dc.drawText(tr, Text.literal("›"), x + w - 65, barY + 6, HubTheme.MUTED, false);
		boolean hasSel = selected >= 0 && selected < offers.size();
		boolean mine = hasSel && offers.get(selected).mine();
		mini(dc, tr, x + w - 44, barY + 3, 18, 14, "买", hasSel && !mine, mouseX, mouseY, HubTheme.GOLD);
		mini(dc, tr, x + w - 24, barY + 3, 20, 14, "撤", mine, mouseX, mouseY, HubTheme.DANGER);
		// 交易按钮的可用性判断必须和 click() 里完全一致
	}

	private static void drawRow(DrawContext dc, TextRenderer tr, int x, int ry, int w,
			MarketProtocol.Offer o, boolean selected, double mouseX, double mouseY) {
		boolean hover = mouseX >= x && mouseX < x + w && mouseY >= ry && mouseY < ry + ROW_H - 2;
		dc.fill(x, ry, x + w, ry + ROW_H - 2, selected ? HubTheme.SELECT : (hover ? HubTheme.HOVER : HubTheme.CARD));
		ItemStack st = MarketClientNetworking.stackOf(o);
		if (!st.isEmpty()) {
			dc.drawItem(st, x + 3, ry + 2);
		} else {
			dc.fill(x + 3, ry + 2, x + 19, ry + 18, 0xFF444444);
		}
		String name = st.isEmpty() ? o.itemId() : st.getName().getString();
		dc.drawText(tr, Text.literal(trim(tr, name + " ×" + o.count(), w - 190)), x + 23, ry + 2, HubTheme.TEXT, false);
		dc.drawText(tr, Text.literal(trim(tr, o.sellerName(), 60)), x + 23, ry + 12, HubTheme.MUTED, false);
		String price = o.price() + " 铜";
		dc.drawText(tr, Text.literal(price), x + w - 92, ry + 2, HubTheme.GOLD, false);
		String remain = o.remainingMs() < 0 ? "永久" : MarketRules.remainingText(o.remainingMs()) + "后下架";
		dc.drawText(tr, Text.literal(trim(tr, remain, 84)), x + w - 92, ry + 12, 0xFF6E6E6E, false);
		if (o.mine()) {
			dc.drawText(tr, Text.literal("我的"), x + w - 22, ry + 6, HubTheme.PRIMARY, false);
		}
	}

	// ---------------------------------------------------------------- 点击

	/** 命中检测；命中行返回 {@link Action#NONE} 并通过 {@code outIndex} 带回下标。 */
	public static Action click(TextRenderer tr, double mx, double my, int x, int y, int w, int h,
			int selected, int[] outIndex) {
		MarketProtocol.Snapshot snap = MarketClientNetworking.LAST;

		// 筛选行
		if (my >= y && my < y + FILTER_H) {
			int cw0 = tr.getWidth("全部") + 10;
			int cw1 = tr.getWidth("我的") + 10;
			if (mx >= x && mx < x + cw0) return Action.TOGGLE_MINE;
			if (mx >= x + cw0 + 4 && mx < x + cw0 + 4 + cw1) return Action.TOGGLE_MINE;
			if (mx >= x + w - 32) return Action.SYNC;
			return Action.NONE;
		}

		// 挂出条
		int sy = y + FILTER_H + 4;
		if (my >= sy && my < sy + SELL_H) {
			String priceLabel = "价:" + price();
			int pw = tr.getWidth(priceLabel) + 10;
			int px = x + w - 62 - pw;
			if (mx >= px && mx < px + pw) return Action.CYCLE_PRICE;
			if (mx >= x + w - 58 && mx < x + w - 6) {
				var mc = MinecraftClient.getInstance();
				boolean has = mc.player != null && !mc.player.getMainHandStack().isEmpty();
				return has ? Action.SELL : Action.NONE;
			}
			return Action.NONE;
		}

		// 列表
		int top = listTop(y);
		int rows = rowsVisible(h);
		List<MarketProtocol.Offer> offers = snap.offers();
		for (int i = 0; i < offers.size() && i < rows; i++) {
			int ry = top + i * ROW_H;
			if (ry + ROW_H > y + h - 26) break;
			if (mx >= x && mx < x + w && my >= ry && my < ry + ROW_H - 2) {
				outIndex[0] = i;
				return Action.NONE;
			}
		}

		// 底栏
		int barY = y + h - 24;
		if (my >= barY && my <= barY + 20) {
			boolean hasSel = selected >= 0 && selected < offers.size();
			boolean mine = hasSel && offers.get(selected).mine();
			if (mx >= x + w - 44 && mx <= x + w - 26) return hasSel && !mine ? Action.BUY : Action.NONE;
			if (mx >= x + w - 24 && mx <= x + w - 4) return mine ? Action.CANCEL : Action.NONE;
			if (mx >= x + w - 104 && mx <= x + w - 88) return Action.PREV;
			if (mx >= x + w - 68 && mx <= x + w - 52) return Action.NEXT;
		}
		return Action.NONE;
	}

	// ---------------------------------------------------------------- 小工具

	private static int chip(DrawContext dc, TextRenderer tr, int x, int y, String label, boolean active,
			double mouseX, double mouseY) {
		int w = tr.getWidth(label) + 10;
		boolean hover = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + FILTER_H;
		dc.fill(x, y, x + w, y + FILTER_H, active ? 0xFF3A3020 : (hover ? 0xFF2E2E2E : 0xFF232323));
		dc.fill(x, y, x + w, y + 1, active ? HubTheme.GOLD : HubTheme.LINE);
		dc.drawText(tr, Text.literal(label), x + 5, y + 3, active ? 0xFFFFE9A8 : HubTheme.MUTED, false);
		return w;
	}

	private static void mini(DrawContext dc, TextRenderer tr, int x, int y, int w, int h, String label,
			boolean enabled, double mouseX, double mouseY, int accent) {
		boolean hover = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
		dc.fill(x, y, x + w, y + h, enabled ? (hover ? 0xFF4A3F22 : 0xFF3A3020) : 0xFF2A2A2A);
		dc.fill(x, y, x + w, y + 1, enabled ? accent : HubTheme.LINE);
		dc.fill(x, y + h - 1, x + w, y + h, enabled ? accent : HubTheme.LINE);
		dc.drawText(tr, Text.literal(label), x + (w - tr.getWidth(label)) / 2, y + 3,
				enabled ? 0xFFFFE9A8 : 0xFF6A6A6A, false);
	}

	private static String trim(TextRenderer tr, String s, int maxW) {
		if (s == null) return "";
		if (maxW <= 0) return "";
		if (tr.getWidth(s) <= maxW) return s;
		String out = s;
		while (out.length() > 1 && tr.getWidth(out + "…") > maxW) {
			out = out.substring(0, out.length() - 1);
		}
		return out + "…";
	}

	private static String trimNum(double v) {
		if (Math.abs(v - Math.rint(v)) < 1e-6) return String.valueOf((long) v);
		return String.format(java.util.Locale.ROOT, "%.1f", v);
	}
}
