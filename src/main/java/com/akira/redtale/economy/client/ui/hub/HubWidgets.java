package com.akira.redtale.economy.client.ui.hub;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;

/**
 * 轻量控件库（借鉴 l1ght_economy 的 Material 风格，但用立即模式实现）。
 *
 * <p>l1ght 用的是 ModernUI（Fragment + Drawable + RippleDrawable），依赖太重不能照搬，
 * 这里只借它的**设计语言**：
 * <ul>
 *   <li>圆角卡片（4 种按钮变体：主/次/描边/幽灵/危险/开关）</li>
 *   <li>悬停变亮、按下变暗</li>
 *   <li>点击涟漪（ripple，时间驱动的扩散圆）</li>
 * </ul>
 */
public final class HubWidgets {

	public enum Variant { PRIMARY, SECONDARY, OUTLINE, GHOST, DESTRUCTIVE, TOGGLE }

	private HubWidgets() {
	}

	/** 圆角矩形（半径 1~4px，用四角像素补）。 */
	public static void roundRect(DrawContext dc, int x, int y, int w, int h, int r, int color) {
		if (w <= 0 || h <= 0) return;
		r = Math.max(0, Math.min(r, Math.min(w, h) / 2));
		if (r == 0) {
			dc.fill(x, y, x + w, y + h, color);
			return;
		}
		dc.fill(x + r, y, x + w - r, y + h, color);
		dc.fill(x, y + r, x + r, y + h - r, color);
		dc.fill(x + w - r, y + r, x + w, y + h - r, color);
		// 四角按半径逐行内缩
		for (int i = 0; i < r; i++) {
			int inset = r - i - 1;
			dc.fill(x + inset, y + i, x + r, y + i + 1, color);
			dc.fill(x + w - r, y + i, x + w - inset, y + i + 1, color);
			dc.fill(x + inset, y + h - i - 1, x + r, y + h - i, color);
			dc.fill(x + w - r, y + h - i - 1, x + w - inset, y + h - i, color);
		}
	}

	/** 卡片：圆角 + 细边。 */
	public static void card(DrawContext dc, int x, int y, int w, int h) {
		roundRect(dc, x, y, w, h, 3, HubTheme.CARD);
		outline(dc, x, y, w, h, 3, HubTheme.OUTLINE);
	}

	public static void outline(DrawContext dc, int x, int y, int w, int h, int r, int color) {
		dc.fill(x + r, y, x + w - r, y + 1, color);
		dc.fill(x + r, y + h - 1, x + w - r, y + h, color);
		dc.fill(x, y + r, x + 1, y + h - r, color);
		dc.fill(x + w - 1, y + r, x + w, y + h - r, color);
	}

	/** 按钮（含变体配色 + 悬停/按下/禁用）。 */
	public static void button(DrawContext dc, TextRenderer tr, int x, int y, int w, int h,
			String label, Variant variant, boolean hover, boolean pressed, boolean enabled) {
		int bg;
		int fg;
		int edge;
		if (!enabled) {
			bg = HubTheme.CARD;
			fg = HubTheme.MUTED;
			edge = HubTheme.OUTLINE;
		} else {
			switch (variant) {
				case PRIMARY -> {
					bg = hover ? HubTheme.PRIMARY_HOVER : HubTheme.PRIMARY;
					fg = HubTheme.ON_PRIMARY;
					edge = 0;
				}
				case DESTRUCTIVE -> {
					bg = hover ? HubTheme.DANGER_HOVER : HubTheme.DANGER;
					fg = 0xFFFFFFFF;
					edge = 0;
				}
				case OUTLINE -> {
					bg = hover ? HubTheme.HOVER : HubTheme.PANEL;
					fg = HubTheme.TEXT;
					edge = HubTheme.PRIMARY;
				}
				case GHOST -> {
					bg = hover ? HubTheme.HOVER : 0;
					fg = HubTheme.MUTED;
					edge = 0;
				}
				case TOGGLE -> {
					bg = hover ? HubTheme.PRIMARY_HOVER : HubTheme.PRIMARY;
					fg = HubTheme.ON_PRIMARY;
					edge = 0;
				}
				default -> { // SECONDARY
					bg = hover ? HubTheme.HOVER : HubTheme.CARD;
					fg = HubTheme.TEXT;
					edge = HubTheme.OUTLINE;
				}
			}
		}
		if (bg != 0) roundRect(dc, x, y, w, h, 3, bg);
		if (edge != 0) outline(dc, x, y, w, h, 3, edge);
		int ty = y + (h - 8) / 2;
		dc.drawText(tr, Text.literal(label), x + (w - tr.getWidth(label)) / 2, ty, fg, false);
	}

	/** 点击涟漪：progress 0→1，扩散并淡出。 */
	public static void ripple(DrawContext dc, int x, int y, int w, int h, double cx, double cy,
			float progress, int baseColor) {
		if (progress < 0 || progress > 1) return;
		int r = (int) (Math.max(w, h) * (0.15 + 0.85 * progress));
		int alpha = (int) (0x55 * (1 - progress));
		if (alpha <= 0) return;
		int color = (alpha << 24) | (baseColor & 0xFFFFFF);
		int cxi = (int) cx;
		int cyi = (int) cy;
		for (int dy = -r; dy <= r; dy++) {
			int span = (int) Math.sqrt(Math.max(0, r * r - dy * dy));
			int y1 = cyi + dy;
			if (y1 < y || y1 >= y + h) continue;
			int x1 = Math.max(x, cxi - span);
			int x2 = Math.min(x + w, cxi + span);
			if (x2 > x1) dc.fill(x1, y1, x2, y1 + 1, color);
		}
	}

	/** 强调色小条（列表选中标记）。 */
	public static void indicator(DrawContext dc, int x, int y, int h) {
		roundRect(dc, x, y + 2, 2, h - 4, 1, HubTheme.PRIMARY);
	}
}
