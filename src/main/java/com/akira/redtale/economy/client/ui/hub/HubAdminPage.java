package com.akira.redtale.economy.client.ui.hub;

import com.akira.redtale.economy.net.AdminProtocol;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;

import java.util.List;

/**
 * T28 管理员控制台页（Hub 内）。
 *
 * <p>只读：整页没有任何写动作，点击只有「刷新」（重新请求快照）。
 * 真正的管理动作（改价、编辑配置、重载、备份）仍然走各自命令 —— 这里不重复实现，
 * 免得两套入口对同一份数据用不同的校验。
 *
 * <p>协议是「指标列表」（见 {@link AdminProtocol}），所以这一页不认识任何具体指标：
 * 服务端加一项，客户端自动多一行。这也是它不需要随服务端改版的原因。
 *
 * <p>几何只算一次（{@link #cardAt}/{@link #cardX}/{@link #cardY}），render 与 click 共用，
 * 避免「看着能点其实点不到」。
 */
public final class HubAdminPage {

	private static final int HEAD_H = 16;
	private static final int CARD_H = 26;
	private static final int CARD_GAP = 4;
	/** 两列的最小宽度：再窄就单列（427×240 下内容区还有 ~250px，仍是两列）。 */
	private static final int MIN_TWO_COL = 180;
	private static final int REFRESH_W = 30;

	private static int scroll;
	/** 上一次渲染用的列数：{@link #maxScroll} 没有 w 参数，靠它保持一致（点/滚都在 render 之后）。 */
	private static int lastCols = 2;

	/** 刷新按钮的命中区（与 render 同一套坐标）。 */
	private static int refreshX;
	private static int refreshY;
	private static boolean refreshHot;

	private HubAdminPage() {
	}

	public static void reset() {
		scroll = 0;
	}

	public static int maxScroll(int h) {
		return Math.max(0, rowsTotal(h) - rowsVisible(h));
	}

	public static void scrollByRows(int delta, int h) {
		scroll = Math.max(0, Math.min(maxScroll(h), scroll + delta));
	}

	/** 探针：截图轮断言「版面有数据 + 刷新真的过服务端」。 */
	public static String devProbe() {
		AdminProtocol.Snapshot s = AdminClientNetworking.LAST;
		return "metrics=" + s.metrics().size() + " admin=" + s.admin()
				+ " at=" + s.generatedAt() + " scroll=" + scroll + " recv=" + AdminClientNetworking.RECEIVED_AT;
	}

	// ------------------------------------------------------------ 几何

	private static int columns(int w) {
		return w >= MIN_TWO_COL ? 2 : 1;
	}

	private static int cardW(int w) {
		int c = columns(w);
		return (w - CARD_GAP * (c - 1)) / c;
	}

	private static int cardX(int x, int w, int i) {
		return x + (i % columns(w)) * (cardW(w) + CARD_GAP);
	}

	private static int cardY(int y, int i, int w) {
		return y + HEAD_H + (i / columns(w)) * (CARD_H + CARD_GAP);
	}

	private static int rowsTotal(int h) {
		int n = AdminClientNetworking.LAST.metrics().size();
		return (n + lastCols - 1) / lastCols;
	}

	private static int rowsVisible(int h) {
		return Math.max(1, (h - HEAD_H) / (CARD_H + CARD_GAP));
	}

	// ------------------------------------------------------------ 渲染

	public static void render(DrawContext dc, TextRenderer tr, int x, int y, int w, int h,
			double mouseX, double mouseY) {
		AdminProtocol.Snapshot snap = AdminClientNetworking.LAST;

		dc.drawText(tr, Text.literal("管理员控制台"), x, y, HubTheme.TITLE, false);

		// 右上：数据新鲜度 + 刷新
		refreshX = x + w - REFRESH_W;
		refreshY = y;
		refreshHot = mouseX >= refreshX && mouseX < refreshX + REFRESH_W
				&& mouseY >= refreshY && mouseY < refreshY + HEAD_H - 4;
		dc.fill(refreshX, refreshY, refreshX + REFRESH_W, refreshY + HEAD_H - 4,
				refreshHot ? HubTheme.HOVER : HubTheme.CARD);
		dc.drawText(tr, Text.literal("刷新"), refreshX + 5, refreshY + 3,
				refreshHot ? HubTheme.GOLD : HubTheme.TEXT, false);
		String when = snap.generatedAt() <= 0 ? "—"
				: com.akira.redtale.economy.storage.AdminStats.age(
						System.currentTimeMillis() - snap.generatedAt());
		dc.drawText(tr, Text.literal(when), refreshX - 4 - tr.getWidth(when), refreshY + 3,
				HubTheme.MUTED, false);

		if (!snap.admin()) {
			dc.drawText(tr, Text.literal("需要管理员权限"), x + 2, y + HEAD_H + 6, HubTheme.DANGER, false);
			dc.drawText(tr, Text.literal(trim(tr, "这一页只对 /rt 权限 admin 的玩家下发数据", w - 8)),
					x + 2, y + HEAD_H + 20, HubTheme.HINT, false);
			return;
		}

		List<AdminProtocol.Metric> metrics = snap.metrics();
		if (metrics.isEmpty()) {
			dc.drawText(tr, Text.literal("暂无数据"), x + 2, y + HEAD_H + 4, HubTheme.MUTED, false);
			dc.drawText(tr, Text.literal(trim(tr, "点右上「刷新」重新采集", w - 8)), x + 2, y + HEAD_H + 17,
					HubTheme.HINT, false);
			return;
		}

		int limit = y + h;
		lastCols = columns(w);
		scroll = Math.max(0, Math.min(maxScroll(h), scroll));
		int start = scroll * lastCols;
		int cw = cardW(w);
		int hoverIdx = -1;
		for (int i = start; i < metrics.size(); i++) {
			int rx = cardX(x, w, i);
			int ry = cardY(y, i, w);
			if (!(ry + CARD_H <= limit)) break;
			dc.fill(rx, ry, rx + cw, ry + CARD_H - 2, HubTheme.CARD);
			AdminProtocol.Metric m = metrics.get(i);
			boolean hover = mouseX >= rx && mouseX < rx + cw && mouseY >= ry && mouseY < ry + CARD_H - 2;
			dc.drawText(tr, Text.literal(trim(tr, m.label(), cw - 8)), rx + 5, ry + 4,
					hover ? HubTheme.TITLE : HubTheme.MUTED, false);
			dc.drawText(tr, Text.literal(trim(tr, m.value(), cw - 8)), rx + 5, ry + 14,
					HubTheme.TEXT, false);
			if (hover) hoverIdx = i;
		}
		// ★悬停说明最后画★：画在循环里会被后一张卡盖掉（硬坑：浮层滞后于卡片）
		if (hoverIdx >= 0) {
			AdminProtocol.Metric m = metrics.get(hoverIdx);
			if (m.hint() != null && !m.hint().isEmpty()) {
				int rx = cardX(x, w, hoverIdx);
				int ry = cardY(y, hoverIdx, w) + CARD_H - 2;
				int ty = Math.min(ry, limit - 11);
				dc.fill(rx, ty, rx + cw, ty + 11, HubTheme.SELECT);
				dc.drawText(tr, Text.literal(trim(tr, m.hint(), cw - 8)), rx + 5, ty + 2,
						HubTheme.HINT, false);
			}
		}
		int max = maxScroll(h);
		if (max > 0) {
			int barX = x + w - 1;
			int trackH = limit - (y + HEAD_H);
			dc.fill(barX, y + HEAD_H, barX + 1, limit, HubTheme.TRACK);
			int knobH = Math.max(8, trackH * rowsVisible(h) / rowsTotal(h));
			int knobY = y + HEAD_H + (trackH - knobH) * scroll / max;
			dc.fill(barX, knobY, barX + 1, knobY + knobH, HubTheme.LINE);
		}
	}

	// ------------------------------------------------------------ 点击

	/** @return true = 需要重新请求快照（点了刷新） */
	public static boolean click(double mouseX, double mouseY, int w) {
		if (mouseX >= refreshX && mouseX < refreshX + REFRESH_W
				&& mouseY >= refreshY && mouseY < refreshY + HEAD_H - 4) {
			AdminClientNetworking.request();
			return true;
		}
		return false;
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
