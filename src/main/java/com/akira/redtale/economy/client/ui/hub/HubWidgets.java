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

	/**
	 * 圆角描边的全部像素段：每 4 个 int 一段 {@code (x1, y1, x2, y2)}，均为 1px 宽。
	 *
	 * <p>★真缺陷（第 N+73 轮）★：原来只画四条直边，四角（半径 r 的阶梯）一个像素都不画
	 * ⇒ 每个角缺一个 <b>L 形缺口</b>，描边断成四段。灰描边在暗底上不明显，
	 * 但 {@code Variant.OUTLINE} 的金色描边一眼就是「半截高亮」，被误读成悬停/聚焦态。
	 *
	 * <p>四角必须沿 {@link #roundRect} <b>同一条轮廓</b>补 1px 阶梯：第 i 行（顶/底对称）
	 * 的最外像素是 {@code x + (r-1-i)} / {@code x + w - r + i}，补完才闭合成一整圈。
	 * 几何抽成纯函数（不碰 DrawContext），{@code HubWidgetsEdgeTest} 直接验闭合性。
	 */
	public static int[] outlineSegments(int x, int y, int w, int h, int r) {
		if (w <= 0 || h <= 0) return new int[0];
		r = Math.max(0, Math.min(r, Math.min(w, h) / 2));
		int n = 4 + (r > 0 ? 4 * r : 0);
		int[] seg = new int[n * 4];
		int k = 0;
		// 四条直边：r=0 时它们自己就把四角包住（重叠绘制无害）
		seg[k++] = x + r; seg[k++] = y; seg[k++] = x + w - r; seg[k++] = y + 1;
		seg[k++] = x + r; seg[k++] = y + h - 1; seg[k++] = x + w - r; seg[k++] = y + h;
		seg[k++] = x; seg[k++] = y + r; seg[k++] = x + 1; seg[k++] = y + h - r;
		seg[k++] = x + w - 1; seg[k++] = y + r; seg[k++] = x + w; seg[k++] = y + h - r;
		// 四个圆角：逐行 1px，与 roundRect 的阶梯轮廓完全重合
		for (int i = 0; i < r; i++) {
			int lx = x + (r - 1 - i);
			int rx = x + w - r + i;
			seg[k++] = lx; seg[k++] = y + i; seg[k++] = lx + 1; seg[k++] = y + i + 1;
			seg[k++] = rx; seg[k++] = y + i; seg[k++] = rx + 1; seg[k++] = y + i + 1;
			seg[k++] = lx; seg[k++] = y + h - 1 - i; seg[k++] = lx + 1; seg[k++] = y + h - i;
			seg[k++] = rx; seg[k++] = y + h - 1 - i; seg[k++] = rx + 1; seg[k++] = y + h - i;
		}
		return seg;
	}

	/** 圆角描边（闭合的一整圈 1px）。 */
	public static void outline(DrawContext dc, int x, int y, int w, int h, int r, int color) {
		int[] seg = outlineSegments(x, y, w, h, r);
		for (int i = 0; i < seg.length; i += 4) {
			dc.fill(seg[i], seg[i + 1], seg[i + 2], seg[i + 3], color);
		}
	}

	/**
	 * 按钮边框色（{@code 0} = 不画边）。
	 *
	 * <p>★语义唯一化（第 N+73 轮）★：金色 <b>只属于交互态</b> —— 常态描边一律中性灰，
	 * 悬停才转金。原来 {@code OUTLINE} 变体常态就用 {@code PRIMARY} 画边，
	 * 与悬停色 {@code PRIMARY_HOVER} 几乎分不出 ⇒ 用户看到的金框究竟是「描边样式」
	 * 还是「鼠标停在这里」在视觉上无法区分（正是这次要消掉的歧义）。
	 */
	static int edgeColor(Variant variant, boolean hover, boolean enabled) {
		if (!enabled) return HubTheme.OUTLINE;
		return switch (variant) {
			case OUTLINE -> hover ? HubTheme.PRIMARY : HubTheme.EDGE_IDLE;
			case SECONDARY -> hover ? HubTheme.EDGE_HOVER : HubTheme.OUTLINE;
			default -> 0;
		};
	}

	/** 按钮（含变体配色 + 悬停/按下/禁用）。 */
	public static void button(DrawContext dc, TextRenderer tr, int x, int y, int w, int h,
			String label, Variant variant, boolean hover, boolean pressed, boolean enabled) {
		int bg;
		int fg;
		if (!enabled) {
			bg = HubTheme.CARD;
			fg = HubTheme.MUTED;
		} else {
			switch (variant) {
				case PRIMARY -> {
					bg = hover ? HubTheme.PRIMARY_HOVER : HubTheme.PRIMARY;
					fg = HubTheme.ON_PRIMARY;
				}
				case DESTRUCTIVE -> {
					bg = hover ? HubTheme.DANGER_HOVER : HubTheme.DANGER;
					fg = 0xFFFFFFFF;
				}
				case OUTLINE -> {
					bg = hover ? HubTheme.HOVER : HubTheme.PANEL;
					fg = HubTheme.TEXT;
				}
				case GHOST -> {
					bg = hover ? HubTheme.HOVER : 0;
					fg = HubTheme.MUTED;
				}
				case TOGGLE -> {
					bg = hover ? HubTheme.PRIMARY_HOVER : HubTheme.PRIMARY;
					fg = HubTheme.ON_PRIMARY;
				}
				default -> { // SECONDARY
					bg = hover ? HubTheme.HOVER : HubTheme.CARD;
					fg = HubTheme.TEXT;
				}
			}
		}
		int edge = edgeColor(variant, hover, enabled);
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
