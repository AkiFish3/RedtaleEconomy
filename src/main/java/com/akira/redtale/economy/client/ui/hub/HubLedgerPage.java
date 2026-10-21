package com.akira.redtale.economy.client.ui.hub;

import com.akira.redtale.economy.net.LedgerProtocol;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;

import java.util.List;

/**
 * Hub 内的交易流水页（T16）。
 *
 * <p>只读：没有任何写动作，点击只会改筛选条件（然后重新请求快照）。
 * 数据来自 {@link LedgerClientNetworking#LAST}，筛选条件也在这里存 —— 服务端快照回显的
 * 下标以服务端为准（可能被降级），渲染前先接受服务端的值。
 *
 * <p>几何只定义一次，render / click / scroll 共用，避免「看着能点其实点不到」。
 */
public final class HubLedgerPage {

	private static final int HEAD_H = 16;
	/** 三排 chip（时间 / 方向 / 类型）每排高度。 */
	private static final int CHIP_H = 13;
	private static final int ROW_H = 13;
	private static final int CHIP_PAD = 5;

	private static final String[] DAY_LABELS = {"今天", "7 天", "30 天"};
	private static final String[] DIR_LABELS = {"全部", "收入", "支出"};
	private static final String[] TYPE_LABELS = {"全部类型", "商店", "市场", "任务", "其它"};

	private static int daysIdx;
	private static int dirIdx;
	private static int typeIdx;
	private static boolean allPlayers;
	private static int scroll;

	private HubLedgerPage() {
	}

	// ------------------------------------------------------------ 状态（接收器要读）

	public static int daysIdx() {
		return daysIdx;
	}

	public static int dirIdx() {
		return dirIdx;
	}

	public static int typeIdx() {
		return typeIdx;
	}

	public static boolean allPlayers() {
		return allPlayers;
	}

	public static void reset() {
		scroll = 0;
	}

	/** 探针：截图轮用它断言「版面不是空的 + 筛选真的生效」。 */
	public static String devProbe() {
		LedgerProtocol.Snapshot s = LedgerClientNetworking.LAST;
		return "rows=" + s.rows().size() + " total=" + s.total() + " admin=" + s.admin()
				+ " days=" + s.daysIdx() + " dir=" + s.dir() + " type=" + s.typeIdx()
				+ " all=" + s.all() + " scroll=" + scroll;
	}

	private static int rowsVisible(int h) {
		return Math.max(1, (h - HEAD_H - CHIP_H * 3 - 14) / ROW_H);
	}

	private static int maxScroll(int size, int h) {
		return Math.max(0, size - rowsVisible(h));
	}

	public static void scrollByRows(int delta, int h) {
		List<LedgerProtocol.Row> rows = LedgerClientNetworking.LAST.rows();
		scroll = Math.max(0, Math.min(maxScroll(rows.size(), h), scroll + delta));
	}

	// ------------------------------------------------------------ 渲染

	public static void render(DrawContext dc, TextRenderer tr, int x, int y, int w, int h,
			double mouseX, double mouseY) {
		LedgerProtocol.Snapshot snap = LedgerClientNetworking.LAST;
		// 服务端可能降级（非管理员 all=true → 只看自己），以回显为准
		daysIdx = snap.daysIdx();
		dirIdx = snap.dir();
		typeIdx = snap.typeIdx();
		allPlayers = snap.all();

		dc.drawText(tr, Text.literal("交易流水"), x, y, HubTheme.TITLE, false);
		String head = snap.total() <= 0 ? "—" : ("共 " + snap.total() + " 笔");
		dc.drawText(tr, Text.literal(head), x + w - tr.getWidth(head), y, HubTheme.MUTED, false);

		int cy = y + HEAD_H;
		drawChips(dc, tr, x, cy, DAY_LABELS, daysIdx, mouseX, mouseY, 0);
		cy += CHIP_H;
		drawChips(dc, tr, x, cy, DIR_LABELS, dirIdx, mouseX, mouseY, 1);
		cy += CHIP_H;
		drawChips(dc, tr, x, cy, TYPE_LABELS, typeIdx, mouseX, mouseY, 2);
		cy += CHIP_H;

		// 表头（列位置与下面 drawRow 同一套常量）
		dc.drawText(tr, Text.literal("时间"), x + 2, cy, HubTheme.MUTED, false);
		dc.drawText(tr, Text.literal("类型"), x + 72, cy, HubTheme.MUTED, false);
		dc.drawText(tr, Text.literal("金额"), x + 132, cy, HubTheme.MUTED, false);
		dc.drawText(tr, Text.literal("余额"), x + w - 46, cy, HubTheme.MUTED, false);
		cy += 12;
		dc.fill(x, cy - 1, x + w, cy, HubTheme.LINE);

		List<LedgerProtocol.Row> rows = snap.rows();
		int limit = y + h;
		if (rows.isEmpty()) {
			dc.drawText(tr, Text.literal("这段时间没有流水"), x + 2, cy + 4, HubTheme.MUTED, false);
			dc.drawText(tr, Text.literal(trim(tr, "换个时间档/类型，或先做一笔交易", w - 8)), x + 2, cy + 17,
					HubTheme.HINT, false);
			return;
		}
		int visible = rowsVisible(h);
		scroll = Math.max(0, Math.min(maxScroll(rows.size(), h), scroll));
		for (int i = 0; i < visible; i++) {
			int idx = scroll + i;
			if (idx >= rows.size()) break;
			int ry = cy + i * ROW_H;
			if (ry + ROW_H > limit) break;
			drawRow(dc, tr, x, ry, w, rows.get(idx));
		}
		int max = maxScroll(rows.size(), h);
		if (max > 0) {
			int barX = x + w - 1;
			int trackH = limit - cy;
			dc.fill(barX, cy, barX + 1, limit, HubTheme.TRACK);
			int knobH = Math.max(8, trackH * visible / rows.size());
			int knobY = cy + (trackH - knobH) * scroll / max;
			dc.fill(barX, knobY, barX + 1, knobY + knobH, HubTheme.LINE);
		}
	}

	private static void drawRow(DrawContext dc, TextRenderer tr, int x, int ry, int w,
			LedgerProtocol.Row r) {
		String time = shortTime(r.ts());
		dc.drawText(tr, Text.literal(time), x + 2, ry + 2, HubTheme.MUTED, false);
		dc.drawText(tr, Text.literal(trim(tr, r.type(), 56)), x + 72, ry + 2, HubTheme.TEXT, false);
		String money = (r.delta() > 0 ? "+" : "") + r.delta();
		int color = r.delta() > 0 ? HubTheme.OK : (r.delta() < 0 ? HubTheme.DANGER : HubTheme.MUTED);
		dc.drawText(tr, Text.literal(money), x + 132, ry + 2, color, false);
		String after = String.valueOf(r.after());
		dc.drawText(tr, Text.literal(after), x + w - 2 - tr.getWidth(after), ry + 2, HubTheme.HINT, false);
	}

	/** ISO {@code 2026-10-21T17:38:05} → {@code 10-21 17:38}（定长，不够就原样）。 */
	private static String shortTime(String ts) {
		if (ts == null || ts.length() < 16) return ts == null ? "" : ts;
		return ts.substring(5, 10) + " " + ts.substring(11, 16);
	}

	/** 一排 chip；{@code row} 只用于计算命中区，与 render 顺序一致。 */
	private static void drawChips(DrawContext dc, TextRenderer tr, int x, int cy, String[] labels,
			int selected, double mouseX, double mouseY, int row) {
		int cx = x;
		for (int i = 0; i < labels.length; i++) {
			int cw = tr.getWidth(labels[i]) + CHIP_PAD * 2;
			boolean hover = mouseX >= cx && mouseX < cx + cw && mouseY >= cy && mouseY < cy + CHIP_H - 1;
			int bg = i == selected ? HubTheme.SELECT : (hover ? HubTheme.HOVER : HubTheme.CARD);
			int fg = i == selected ? HubTheme.GOLD : HubTheme.TEXT;
			dc.fill(cx, cy, cx + cw, cy + CHIP_H - 1, bg);
			dc.drawText(tr, Text.literal(labels[i]), cx + CHIP_PAD, cy + 2, fg, false);
			cx += cw + 3;
		}
	}

	// ------------------------------------------------------------ 点击

	/** 返回值：命中的 chip 需要重新请求快照时为 true。 */
	public static boolean click(TextRenderer tr, double mouseX, double mouseY, int x, int y, int w, int h) {
		int cy = y + HEAD_H;
		int hit = chipAt(tr, mouseX, mouseY, x, cy, DAY_LABELS);
		if (hit >= 0) {
			daysIdx = hit;
			reset();
			return true;
		}
		cy += CHIP_H;
		hit = chipAt(tr, mouseX, mouseY, x, cy, DIR_LABELS);
		if (hit >= 0) {
			dirIdx = hit;
			reset();
			return true;
		}
		cy += CHIP_H;
		hit = chipAt(tr, mouseX, mouseY, x, cy, TYPE_LABELS);
		if (hit >= 0) {
			typeIdx = hit;
			reset();
			return true;
		}
		return false;
	}

	private static int chipAt(TextRenderer tr, double mouseX, double mouseY, int x, int cy, String[] labels) {
		int cx = x;
		for (int i = 0; i < labels.length; i++) {
			int cw = tr.getWidth(labels[i]) + CHIP_PAD * 2;
			if (mouseX >= cx && mouseX < cx + cw && mouseY >= cy && mouseY < cy + CHIP_H - 1) return i;
			cx += cw + 3;
		}
		return -1;
	}

	private static String trim(TextRenderer tr, String s, int maxW) {
		if (s == null) return "";
		if (tr.getWidth(s) <= maxW) return s;
		StringBuilder b = new StringBuilder();
		for (char c : s.toCharArray()) {
			if (tr.getWidth(b.toString() + c) > maxW - 6) break;
			b.append(c);
		}
		return b + "…";
	}
}
