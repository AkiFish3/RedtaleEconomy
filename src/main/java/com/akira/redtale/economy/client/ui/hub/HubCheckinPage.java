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

	private static final int CELL_W_MAX = 42;
	private static final int CELL_W_MIN = 16;
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

		// 列宽按内容区宽度收缩（427×240 紧凑模式下 contentW=299 < 7×43=301，
		// 写死 42 会让第 7 列压在面板边框外 1px）。
		int cellW = Math.max(CELL_W_MIN,
				Math.min(CELL_W_MAX, (w - 6 * ROW_GAP) / 7));

		// 标题行
		dc.drawText(tr, Text.literal(ym.getYear() + " 年 " + ym.getMonthValue() + " 月"),
				x + 18, y + 2, HubTheme.TEXT, false);

		// T57 连登里程碑进度条（7 / 14 / 28 天）
		List<CheckinProtocol.Milestone> miles = milestonesOf(snap);
		int streak = snap == null ? 0 : snap.streak();
		CheckinProtocol.Milestone next = nextMilestone(miles, streak);
		int goal = next == null ? Math.max(1, streak) : next.day();
		int barW = Math.max(36, Math.min(84, w - 190));
		int barX = x + w - 24 - 28 - barW;
		int barY = y + 5;
		dc.fill(barX, barY, barX + barW, barY + 4, HubTheme.TRACK);
		dc.fill(barX, barY, barX + barW, barY + 1, HubTheme.OUTLINE);
		int fillW = (int) (barW * Math.min(1.0, streak / (double) Math.max(1, goal)));
		dc.fill(barX, barY, barX + fillW, barY + 4, HubTheme.PRIMARY);
		// 里程碑刻点（已达成的描成 OK 色，未达成用 OUTLINE）
		int prevDay = 0;
		for (CheckinProtocol.Milestone m : miles) {
			if (m.day() <= prevDay || m.day() > goal) continue;
			prevDay = m.day();
			int mx = barX + (int) (barW * Math.min(1.0, m.day() / (double) Math.max(1, goal)));
			dc.fill(mx - 1, barY - 2, mx + 1, barY + 6,
					streak >= m.day() ? HubTheme.OK : HubTheme.OUTLINE);
		}
		String label = streak + "/" + goal;
		dc.drawText(tr, Text.literal(label), barX + barW + 4, y + 1, HubTheme.GOLD, false);
		// 左右箭头
		dc.drawText(tr, Text.literal("◀"), x + 2, y + 2, HubTheme.MUTED, false);
		dc.drawText(tr, Text.literal("▶"), x + w - 10, y + 2, HubTheme.MUTED, false);

		// 星期
		String[] wd = {"一", "二", "三", "四", "五", "六", "日"};
		for (int i = 0; i < 7; i++) {
			int wx = x + i * (cellW + ROW_GAP) + (cellW - tr.getWidth(wd[i])) / 2;
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
			int cx = x + col * (cellW + ROW_GAP);
			int cy = gridY + row * (cellH + ROW_GAP);
			LocalDate d = ym.atDay(day);
			boolean signed = isSigned(snap, d);
			boolean isToday = d.equals(today);
			boolean future = d.isAfter(today);

			// 原版主题：未来日浅灰、已签白底+绿罩；其它主题保持深色
			int bg = signed ? HubTheme.CARD_OK : (future ? HubTheme.INNER : HubTheme.CARD);
			dc.fill(cx, cy, cx + cellW, cy + cellH, bg);
			int edge = HubTheme.LINE;
			dc.fill(cx, cy, cx + cellW, cy + 1, edge);
			dc.fill(cx, cy + cellH - 1, cx + cellW, cy + cellH, edge);
			if (signed) {
				// 绿色遮罩（半透明），保留下面的白底
				dc.fill(cx + 1, cy + 1, cx + cellW - 1, cy + cellH - 1, 0x3350C878);
			}

			int textColor = future ? HubTheme.MUTED
					: (signed ? (HubTheme.vanilla ? HubTheme.OK : HubTheme.TEXT) : HubTheme.TEXT);
			dc.drawText(tr, Text.literal(String.valueOf(day)), cx + 3,
					cy + Math.max(1, (cellH - 8) / 2), textColor, false);

			if (signed && cellW >= 26) {
				if (cellH >= 16) {
				dc.drawText(tr, Text.literal("✓"), cx + cellW - 11,
						cy + Math.max(1, (cellH - 8) / 2), HubTheme.OK, false);
			}
			}
			if (isToday) {
				// 金色描边
				dc.fill(cx, cy, cx + cellW, cy + 1, HubTheme.GOLD);
				dc.fill(cx, cy + cellH - 1, cx + cellW, cy + cellH, HubTheme.GOLD);
				dc.fill(cx, cy, cx + 1, cy + cellH, HubTheme.GOLD);
				dc.fill(cx + cellW - 1, cy, cx + cellW, cy + cellH, HubTheme.GOLD);
			}
			// §48.3 里程碑角标（7/14/28）：右下角小方块。
			// ★画方块而不是塞「🎁」这类字形★：默认字体没有礼盒 emoji，会渲染成豆腐块。
			if (isMilestoneDay(miles, day) && cellH >= 14) {
				int bx2 = cx + cellW - 6;
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
		int moneyX = x + 6 + tr.getWidth("今日奖励") + 8;
		int moneyEnd = moneyX;
		if (todayMoney > 0) {
			// T55：图标 + 数字
			dc.drawText(tr, Text.literal("+"), moneyX, infoY + 2, HubTheme.OK, false);
			HubMoney.draw(dc, tr, moneyX + 5, infoY + 2, todayMoney, 80);
			moneyEnd = moneyX + 5 + HubMoney.width(tr, todayMoney);
		} else {
			dc.drawText(tr, Text.literal("—"), moneyX, infoY + 2, HubTheme.MUTED, false);
			moneyEnd = moneyX + 4;
		}

		// T57 里程碑提示：右侧一行，写清「还差几天 + 到达给多少」。
		// ★以前只有刻点没有文字★：玩家看得到 7/14/28 的点，但不知道到达能拿什么。
		String hint = milestoneHint(next, streak);
		if (!hint.isEmpty()) {
			int avail = x + w - 6 - (moneyEnd + 8);
			int tw = tr.getWidth(hint);
			if (avail > 24) {
				String shown = HubText.trim(tr, hint, avail);
				int hx = x + w - 6 - tr.getWidth(shown);
				dc.drawText(tr, Text.literal(shown), hx, infoY + 6,
						next == null ? HubTheme.OK : HubTheme.PRIMARY_SOFT_TEXT, false);
			}
		}
		drawButton(dc, tr, x, btnY, 96, btnH, "签到", true, mouseX, mouseY,
				HubWidgets.Variant.PRIMARY);
		int makeupLeft = snap == null ? 0 : snap.makeupLeft();
		drawButton(dc, tr, x + 104, btnY, 110, btnH,
				"补签（剩 " + makeupLeft + " 次）", makeupLeft > 0, mouseX, mouseY,
				HubWidgets.Variant.SECONDARY);
	}

	private static List<CheckinProtocol.Milestone> milestonesOf(CheckinProtocol.Snapshot snap) {
		if (snap == null || snap.milestones().isEmpty()) {
			return List.of(new CheckinProtocol.Milestone(7, 0, false),
					new CheckinProtocol.Milestone(14, 0, false),
					new CheckinProtocol.Milestone(28, 0, false));
		}
		return snap.milestones();
	}

	/** 下一个未达成的里程碑（全都达成返回 null）。 */
	private static CheckinProtocol.Milestone nextMilestone(List<CheckinProtocol.Milestone> miles,
			int streak) {
		CheckinProtocol.Milestone best = null;
		for (CheckinProtocol.Milestone m : miles) {
			if (streak >= m.day()) continue;
			if (best == null || m.day() < best.day()) best = m;
		}
		return best;
	}

	private static boolean isMilestoneDay(List<CheckinProtocol.Milestone> miles, int day) {
		for (CheckinProtocol.Milestone m : miles) if (m.day() == day) return true;
		return false;
	}

	/** 里程碑提示文案；今天是最后一天前一日时改成「明天达成」。 */
	private static String milestoneHint(CheckinProtocol.Milestone next, int streak) {
		if (next == null) return "已达成全部里程碑";
		int left = next.day() - streak;
		String reward = next.money() > 0 ? " · +" + HubMoney.text(next.money()) : "";
		if (left == 1) return "明天达成 " + next.day() + " 天里程碑" + reward;
		return "距 " + next.day() + " 天里程碑还差 " + left + " 天" + reward;
	}

	private static void drawButton(DrawContext dc, TextRenderer tr, int x, int y, int w, int h,
			String label, boolean enabled, double mouseX, double mouseY,
			HubWidgets.Variant variant) {
		boolean hover = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
		HubWidgets.button(dc, tr, x, y, w, h, label, variant, hover, false, enabled);
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
