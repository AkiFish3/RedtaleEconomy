package com.akira.redtale.economy.client.ui.hub;

import com.akira.redtale.economy.net.CheckinProtocol;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;

import java.time.LocalDate;
import java.util.List;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;

/**
 * Hub 内的签到日历页（U3，按 spec §48.3 / §49.1）。
 *
 * <p>月历 7 列、周一开头；已签日由 {@code lastDate + streak} 反推（无需协议扩展），
 * 档位日（7/14/21/28）画礼盒角标，今天金色描边。
 *
 * <p>纯静态绘制 + 命中测试，不持有状态；月份偏移与快照由 {@link HubScreen} 保存。
 */
public final class HubCheckinPage {

	private static final int CELL_W = 42;
	/** 单元格高度上限；实际值按内容区高度收缩（GUI Scale 2 时只有 160px 可用）。 */
	private static final int CELL_H_MAX = 30;
	private static final int CELL_H_MIN = 12;
	private static final int ROW_GAP = 1;


	private HubCheckinPage() {
	}

	/** 命中结果。 */
	public enum Action {
		NONE, PREV_MONTH, NEXT_MONTH, CHECKIN, MAKEUP
	}

	private static LocalDate lastSignedDate(CheckinProtocol.Snapshot s) {
		if (s == null || s.lastDate() == null || s.lastDate().isEmpty()) return null;
		try {
			return LocalDate.parse(s.lastDate());
		} catch (DateTimeParseException ex) {
			return null;
		}
	}

	private static boolean isSigned(CheckinProtocol.Snapshot s, LocalDate d) {
		LocalDate last = lastSignedDate(s);
		if (last == null || s.streak() <= 0) return false;
		LocalDate from = last.minusDays(s.streak() - 1L);
		return !d.isBefore(from) && !d.isAfter(last);
	}

	public static void render(DrawContext dc, TextRenderer tr, int x, int y, int w, int h,
			CheckinProtocol.Snapshot snap, int monthOffset, double mouseX, double mouseY) {
		YearMonth ym = YearMonth.now().plusMonths(monthOffset);
		LocalDate today = LocalDate.now();

		// 标题行
		dc.drawText(tr, Text.literal(ym.getYear() + " 年 " + ym.getMonthValue() + " 月"),
				x + 18, y + 2, HubTheme.TEXT, false);
		// 连登里程碑进度条（7 / 14 / 28 天），替代原来错误的「7 的倍数」黄点
		int streak = snap == null ? 0 : snap.streak();
		List<Integer> miles = snap == null || snap.milestones().isEmpty()
				? List.of(7, 14, 28) : snap.milestones();
		int next = miles.get(miles.size() - 1);
		for (int m : miles) {
			if (streak < m) {
				next = m;
				break;
			}
		}
		int barW = 64;
		int barX = x + w - barW - 34;
		int barY = y + 4;
		dc.fill(barX, barY, barX + barW, barY + 4, HubTheme.OUTLINE);
		int fillW = (int) (barW * Math.min(1.0, streak / (double) Math.max(1, next)));
		dc.fill(barX, barY, barX + fillW, barY + 4, HubTheme.PRIMARY);
		// 里程碑刻点
		for (int m : miles) {
			int mx = barX + (int) (barW * Math.min(1.0, m / (double) Math.max(1, next)));
			dc.fill(mx - 1, barY - 2, mx + 1, barY + 6, streak >= m ? HubTheme.PRIMARY : HubTheme.OUTLINE);
		}
		String label = streak + "/" + next;
		dc.drawText(tr, Text.literal(label), barX + barW + 4, y + 1, HubTheme.GOLD, false);
		// 左右箭头
		dc.drawText(tr, Text.literal("◀"), x + 2, y + 2, HubTheme.MUTED, false);
		dc.drawText(tr, Text.literal("▶"), x + w - 10, y + 2, HubTheme.MUTED, false);

		// 星期
		String[] wd = {"一", "二", "三", "四", "五", "六", "日"};
		for (int i = 0; i < 7; i++) {
			int wx = x + i * (CELL_W + ROW_GAP) + (CELL_W - tr.getWidth(wd[i])) / 2;
			dc.drawText(tr, Text.literal(wd[i]), wx, y + 16, HubTheme.MUTED, false);
		}

		// 布局预算：标题 14 + 星期 12 + 网格 + 底部 34（奖励行 12 + 按钮 20）。
		// ★以前写死 CELL_H=30，GUI Scale 2（逻辑高 240）时 6 行日历直接顶出面板，
		// 后半截日历画在按钮和面板外——按可用高度收缩才是对的。
		LocalDate first = ym.atDay(1);
		int lead = first.getDayOfWeek().getValue() - 1; // 周一=0
		int days = ym.lengthOfMonth();
		int rows = (lead + days + 6) / 7;
		int gridTop = y + 28;
		int gridAvail = Math.max(rows * (CELL_H_MIN + ROW_GAP), h - 28 - 46);
		int cellH = Math.max(CELL_H_MIN,
				Math.min(CELL_H_MAX, gridAvail / Math.max(1, rows) - ROW_GAP));

		// 网格
		int gridY = gridTop;
		for (int day = 1; day <= days; day++) {
			int idx = lead + day - 1;
			int col = idx % 7;
			int row = idx / 7;
			int cx = x + col * (CELL_W + ROW_GAP);
			int cy = gridY + row * (cellH + ROW_GAP);
			LocalDate d = ym.atDay(day);
			boolean signed = isSigned(snap, d);
			boolean isToday = d.equals(today);
			boolean future = d.isAfter(today);

			// 原版主题：未来日浅灰、已签白底+绿罩；其它主题保持深色
			int bg = signed ? HubTheme.CARD_OK : (future ? HubTheme.INNER : HubTheme.CARD);
			dc.fill(cx, cy, cx + CELL_W, cy + cellH, bg);
			int edge = HubTheme.LINE;
			dc.fill(cx, cy, cx + CELL_W, cy + 1, edge);
			dc.fill(cx, cy + cellH - 1, cx + CELL_W, cy + cellH, edge);
			if (signed) {
				// 绿色遮罩（半透明），保留下面的白底
				dc.fill(cx + 1, cy + 1, cx + CELL_W - 1, cy + cellH - 1, 0x3350C878);
			}

			int textColor = future ? HubTheme.MUTED
					: (signed ? (HubTheme.vanilla ? HubTheme.OK : HubTheme.TEXT) : HubTheme.TEXT);
			dc.drawText(tr, Text.literal(String.valueOf(day)), cx + 4,
					cy + Math.max(1, (cellH - 8) / 2), textColor, false);

			if (signed) {
				if (cellH >= 16) {
				dc.drawText(tr, Text.literal("✓"), cx + CELL_W - 12,
						cy + Math.max(1, (cellH - 8) / 2), HubTheme.OK, false);
			}
			}
			if (isToday) {
				// 金色描边
				dc.fill(cx, cy, cx + CELL_W, cy + 1, HubTheme.GOLD);
				dc.fill(cx, cy + cellH - 1, cx + CELL_W, cy + cellH, HubTheme.GOLD);
				dc.fill(cx, cy, cx + 1, cy + cellH, HubTheme.GOLD);
				dc.fill(cx + CELL_W - 1, cy, cx + CELL_W, cy + cellH, HubTheme.GOLD);
			}
			// §48.3 里程碑角标（7/14/28）：右下角小方块。
			// ★画方块而不是塞「🎁」这类字形★：默认字体没有礼盒 emoji，会渲染成豆腐块。
			if (miles.contains(day) && cellH >= 14) {
				int bx2 = cx + CELL_W - 6;
				int by2 = cy + cellH - 6;
				dc.fill(bx2, by2, bx2 + 4, by2 + 4, streak >= day ? HubTheme.OK : HubTheme.GOLD);
			}
		}

		// 底部：今日奖励 + 按钮（贴着内容区底边，不随日历行数漂移）
		int btnH = 20;
		int btnY = y + h - btnH;
		int infoY = btnY - 22;
		dc.fill(x, infoY, x + w, infoY + 20, HubTheme.CARD);
		long todayMoney = 0;
		if (snap != null && !snap.points().isEmpty()) {
			for (CheckinProtocol.Point p : snap.points()) {
				if (p.current()) {
					todayMoney = p.money();
					break;
				}
			}
		}
		dc.drawText(tr, Text.literal("今日奖励"), x + 6, infoY + 2, HubTheme.MUTED, false);
		dc.drawText(tr, Text.literal(todayMoney > 0 ? "+" + todayMoney + " 铜" : "—"),
				x + 6 + tr.getWidth("今日奖励") + 8, infoY + 2, HubTheme.GOLD, false);
		drawButton(dc, tr, x, btnY, 96, btnH, "签到", true, mouseX, mouseY,
				HubWidgets.Variant.PRIMARY);
		int makeupLeft = snap == null ? 0 : snap.makeupLeft();
		drawButton(dc, tr, x + 104, btnY, 110, btnH,
				"补签（剩 " + makeupLeft + " 次）", makeupLeft > 0, mouseX, mouseY,
				HubWidgets.Variant.SECONDARY);
	}

	private static void drawButton(DrawContext dc, TextRenderer tr, int x, int y, int w, int h,
			String label, boolean enabled, double mouseX, double mouseY,
			HubWidgets.Variant variant) {
		boolean hover = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
		HubWidgets.button(dc, tr, x, y, w, h, label, variant, hover, false, enabled);
	}

	private static void drawButtonOld(DrawContext dc, TextRenderer tr, int x, int y, int w, int h,
			String label, boolean enabled, double mouseX, double mouseY) {
		boolean hover = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
		int bg = enabled ? (hover ? HubTheme.PRIMARY_SOFT_HOVER : HubTheme.PRIMARY_SOFT) : HubTheme.DISABLED_BG;
		int edge = enabled ? HubTheme.GOLD : HubTheme.LINE;
		dc.fill(x, y, x + w, y + h, bg);
		dc.fill(x, y, x + w, y + 1, edge);
		dc.fill(x, y + h - 1, x + w, y + h, edge);
		dc.fill(x, y, x + 1, y + h, edge);
		dc.fill(x + w - 1, y, x + w, y + h, edge);
		int color = enabled ? HubTheme.PRIMARY_SOFT_TEXT : HubTheme.DISABLED_TEXT;
		dc.drawText(tr, Text.literal(label), x + (w - tr.getWidth(label)) / 2, y + 6, color, false);
	}

	public static Action click(double mouseX, double mouseY, int x, int y, int w, int h,
			CheckinProtocol.Snapshot snap, int monthOffset) {
		// 月份箭头
		if (mouseY >= y && mouseY <= y + 14) {
			if (mouseX >= x && mouseX <= x + 14) return Action.PREV_MONTH;
			if (mouseX >= x + w - 14 && mouseX <= x + w) return Action.NEXT_MONTH;
		}
		int btnY = y + h - 20;
		if (mouseY >= btnY && mouseY <= btnY + 20) {
			if (mouseX >= x && mouseX <= x + 96) return Action.CHECKIN;
			if (mouseX >= x + 104 && mouseX <= x + 214) return Action.MAKEUP;
		}
		return Action.NONE;
	}
}
