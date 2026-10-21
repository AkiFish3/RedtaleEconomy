package com.akira.redtale.economy.client.ui.hub;

import com.akira.redtale.economy.net.MailProtocol;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.List;

/**
 * T62：Hub 邮件页——<b>左列表 + 右详情</b>。
 *
 * <p>以前这里是三行说明文字加两个按钮：玩家看不到自己到底有几笔待领取，
 * 只能点「领取货币」碰运气。现在是列表（每条一条待领取）+ 详情（附件明细 + 领取按钮）。
 *
 * <p>★数据只来自服务端快照★（{@link MailClientNetworking#LAST}）：
 * 金额/物品都在服务端队列里，客户端不做任何本地推算，否则会出现「界面显示可领、服务端拒绝」。
 *
 * <p>★信件本体不在这里渲染★：Contact 信箱里的信仍由 Contact 自己的界面显示（底部有直达按钮），
 * 两套界面各读各的会出现「这边说未读、那边说已取」。
 */
public final class HubMailPage {

	private static final int ROW_H = 18;
	private static final int GAP = 2;
	/** 详情区最小宽度；左侧列表宽度按占比算，放不下时退化成对半。 */
	private static final int DETAIL_MIN_W = 96;

	/** 当前选中条目（快照刷新后按下标保留，越界时钳位）。 */
	private static int selected;
	private static int scrollRow;

	private HubMailPage() {
	}

	public enum Action { NONE, OPEN_MAIL, CLAIM, SELECT }

	/** 点击结果：{@code index} 对 SELECT 是列表下标，对 CLAIM 是物品队列下标。 */
	public record Result(Action action, int index) {
	}

	/** 左侧列表宽度（渲染与命中必须共用，否则点错行）。 */
	private static int listWidth(int w) {
		int listW = Math.max(DETAIL_MIN_W, Math.min(180, (int) (w / 2.6)));
		if (w - listW - 8 < DETAIL_MIN_W) listW = Math.max(70, w / 2 - 4);
		return listW;
	}

	public static void render(DrawContext dc, TextRenderer tr, int x, int y, int w, int h,
			double mouseX, double mouseY) {
		MailProtocol.Snapshot snap = MailClientNetworking.LAST;
		List<MailProtocol.Entry> entries = snap.entries();

		// ---------------------------------------------------------------- 顶栏
		dc.drawText(tr, Text.literal("邮件"), x, y, HubTheme.TITLE, false);
		String head = "contact".equals(snap.provider()) ? "Contact 信箱" : "待领取队列";
		String stat = head + (snap.mailboxFull() && "contact".equals(snap.provider()) ? "（已满）" : "")
				+ " · " + entries.size() + " 条";
		stat = HubText.trim(tr, stat, Math.max(40, w - 40));
		dc.drawText(tr, Text.literal(stat), x + w - tr.getWidth(stat), y + 1, HubTheme.MUTED, false);
		dc.fill(x, y + 14, x + w, y + 15, HubTheme.OUTLINE);

		// ---------------------------------------------------------------- 分区几何
		int listW = listWidth(w);
		int bodyTop = y + 19;
		int bodyBottom = y + h - 24;

		// ---------------------------------------------------------------- 左列表
		if (entries.isEmpty()) {
			dc.drawText(tr, Text.literal("没有待领取"), x + 2, bodyTop + 4, HubTheme.MUTED, false);
		} else {
			int fit = Math.max(1, (bodyBottom - bodyTop + GAP) / (ROW_H + GAP));
			int maxRow = Math.max(0, entries.size() - fit);
			scrollRow = Math.max(0, Math.min(scrollRow, maxRow));
			selected = Math.max(0, Math.min(selected, entries.size() - 1));
			dc.enableScissor(x, bodyTop, x + listW, bodyBottom);
			for (int i = scrollRow; i < entries.size(); i++) {
				int ry = bodyTop + (i - scrollRow) * (ROW_H + GAP);
				if (ry + ROW_H > bodyBottom) break;
				drawRow(dc, tr, x, ry, listW, entries.get(i), i == selected, mouseX, mouseY);
			}
			dc.disableScissor();
			if (maxRow > 0) {
				int trackH = bodyBottom - bodyTop;
				int barH = Math.max(8, trackH * fit / entries.size());
				int barY = bodyTop + (trackH - barH) * scrollRow / maxRow;
				dc.fill(x + listW - 2, bodyTop, x + listW - 1, bodyBottom, HubTheme.LINE);
				dc.fill(x + listW - 2, barY, x + listW - 1, barY + barH, HubTheme.MUTED);
			}
		}

		// ---------------------------------------------------------------- 右详情
		int dx = x + listW + 8;
		int dw = x + w - dx;
		dc.fill(dx, bodyTop, dx + dw, bodyBottom, HubTheme.CARD);
		MailProtocol.Entry sel = entries.isEmpty() ? null
				: entries.get(Math.max(0, Math.min(selected, entries.size() - 1)));
		if (sel == null) {
			dc.drawText(tr, Text.literal("选中左侧一条查看详情"), dx + 6, bodyTop + 6,
					HubTheme.HINT, false);
		} else {
			drawDetail(dc, tr, dx, bodyTop, dw, bodyBottom, sel, mouseX, mouseY);
		}

		// ---------------------------------------------------------------- 底栏
		int by = y + h - 20;
		HubWidgets.button(dc, tr, x, by, 96, 20, "打开信箱", HubWidgets.Variant.SECONDARY,
				hover(mouseX, mouseY, x, by, 96, 20), false, true);
		String foot = snap.pendingMoney() > 0
				? "货币 " + HubMoney.text(snap.pendingMoney()) + " 待领取"
				: "没有待领取货币";
		dc.drawText(tr, Text.literal(HubText.trim(tr, foot, w - 104)), x + 104, by + 6,
				HubTheme.MUTED, false);
	}

	private static void drawRow(DrawContext dc, TextRenderer tr, int x, int ry, int w,
			MailProtocol.Entry e, boolean sel, double mouseX, double mouseY) {
		boolean hover = mouseX >= x && mouseX < x + w && mouseY >= ry && mouseY < ry + ROW_H;
		int bg = sel ? HubTheme.SELECT : (hover ? HubTheme.ROW_HOVER : HubTheme.INNER);
		dc.fill(x, ry, x + w, ry + ROW_H, bg);
		if (sel) dc.fill(x, ry, x + 2, ry + ROW_H, HubTheme.GOLD);

		// 左侧标记：货币画硬币、物品画物品图标（都走位图/几何，不用字形）
		if (e.kind() == MailProtocol.KIND_MONEY) {
			HubMoney.draw(dc, tr, x + 6, ry + 5, e.money(), w - 20);
		} else {
			ItemStack st = stackOf(e.parts());
			if (!st.isEmpty()) dc.drawItem(st, x + 4, ry + 1);
			else dc.fill(x + 4, ry + 1, x + 20, ry + 17, HubTheme.SLOT);
			String name = HubText.trim(tr, e.title(), Math.max(8, w - 30));
			dc.drawText(tr, Text.literal(name), x + 24, ry + 5, HubTheme.TEXT, false);
		}
	}

	private static void drawDetail(DrawContext dc, TextRenderer tr, int x, int y, int w, int bottom,
			MailProtocol.Entry e, double mouseX, double mouseY) {
		dc.drawText(tr, Text.literal(HubText.trim(tr, e.title(), w - 12)), x + 6, y + 5,
				HubTheme.TITLE, false);
		String from = "来自 " + (e.sender() == null || e.sender().isEmpty() ? "系统" : e.sender());
		dc.drawText(tr, Text.literal(HubText.trim(tr, from, w - 12)), x + 6, y + 18,
				HubTheme.MUTED, false);
		dc.fill(x + 6, y + 30, x + w - 6, y + 31, HubTheme.LINE);

		if (e.kind() == MailProtocol.KIND_MONEY) {
			dc.drawText(tr, Text.literal("金额"), x + 6, y + 36, HubTheme.MUTED, false);
			HubMoney.drawFull(dc, tr, x + 6, y + 48, e.money(), w - 12);
		} else {
			dc.drawText(tr, Text.literal("附件"), x + 6, y + 36, HubTheme.MUTED, false);
			int ay = y + 48;
			for (MailProtocol.Part p : e.parts()) {
				if (ay + 18 > bottom - 26) break;
				ItemStack st = stackOf(p.itemId());
				if (!st.isEmpty()) dc.drawItem(st, x + 6, ay);
				else dc.fill(x + 6, ay, x + 22, ay + 16, HubTheme.SLOT);
				String nm = p.name() == null || p.name().isBlank() ? p.itemId() : p.name();
				dc.drawText(tr, Text.literal(HubText.trim(tr, nm, Math.max(20, w - 52))), x + 26,
						ay + 4, HubTheme.TEXT, false);
				String cnt = "×" + p.count();
				dc.drawText(tr, Text.literal(cnt), x + w - 8 - tr.getWidth(cnt), ay + 4,
						HubTheme.MUTED, false);
				ay += 20;
			}
		}

		// 领取按钮贴详情区底边
		int bw = Math.max(48, Math.min(96, w - 12));
		int bx = x + 6;
		int by = Math.max(y + 40, bottom - 24);
		HubWidgets.button(dc, tr, bx, by, bw, 18, "领取", HubWidgets.Variant.PRIMARY,
				hover(mouseX, mouseY, bx, by, bw, 18), false, true);
	}

	public static Result click(double mx, double my, int x, int y, int w, int h) {
		MailProtocol.Snapshot snap = MailClientNetworking.LAST;
		List<MailProtocol.Entry> entries = snap.entries();

		int listW = listWidth(w);
		int bodyTop = y + 19;
		int bodyBottom = y + h - 24;

		// 底栏「打开信箱」
		int by = y + h - 20;
		if (mx >= x && mx <= x + 96 && my >= by && my <= by + 20) {
			return new Result(Action.OPEN_MAIL, -1);
		}

		// 详情区「领取」
		if (!entries.isEmpty() && mx > x + listW + 8 && my >= bodyTop && my < bodyBottom) {
			int dx = x + listW + 8;
			int dw = x + w - dx;
			int bw = Math.max(48, Math.min(96, dw - 12));
			int bx = dx + 6;
			int cby = Math.max(bodyTop + 40, bodyBottom - 24);
			if (mx >= bx && mx <= bx + bw && my >= cby && my <= cby + 18) {
				MailProtocol.Entry e = entries.get(Math.max(0, Math.min(selected, entries.size() - 1)));
				return new Result(Action.CLAIM, e.index());
			}
		}

		// 左列表选中
		if (mx >= x && mx <= x + listW && my >= bodyTop && my < bodyBottom) {
			int fit = Math.max(1, (bodyBottom - bodyTop + GAP) / (ROW_H + GAP));
			int idx = (int) ((my - bodyTop) / (ROW_H + GAP)) + scrollRow;
			int row = (int) ((my - bodyTop) % (ROW_H + GAP));
			if (row < ROW_H && idx >= 0 && idx < entries.size() && idx < scrollRow + fit) {
				selected = idx;
				return new Result(Action.SELECT, idx);
			}
		}
		return new Result(Action.NONE, -1);
	}

	public static void scrollByRows(int rows) {
		scrollRow = Math.max(0, scrollRow + rows);
	}

	/** 换页/回到顶部时重置（选中项保留，避免领完上一条后选中跳走）。 */
	public static void resetScroll() {
		scrollRow = 0;
	}

	/** 选中下标（给 dev 探针/测试用）。 */
	public static int selectedIndex() {
		return selected;
	}

	private static ItemStack stackOf(List<MailProtocol.Part> parts) {
		return parts.isEmpty() ? ItemStack.EMPTY : stackOf(parts.get(0).itemId());
	}

	private static ItemStack stackOf(String itemId) {
		try {
			Item item = Registries.ITEM.get(new Identifier(itemId));
			if (item == null || item == net.minecraft.item.Items.AIR) return ItemStack.EMPTY;
			return new ItemStack(item);
		} catch (Exception e) {
			return ItemStack.EMPTY;
		}
	}

	private static boolean hover(double mx, double my, int x, int y, int w, int h) {
		return mx >= x && mx < x + w && my >= y && my < y + h;
	}
}
