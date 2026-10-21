package com.akira.redtale.economy.client.ui.hub;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;

/**
 * T55：货币显示的<b>唯一出口</b>（图标优先 + 三段分解）。
 *
 * <p>以前每个页面各写各的：{@code "12 铜"}、{@code Currency.format(raw)}、
 * 侧栏自己算金/银/铜 —— 同一个数字在界面上有三种长相。现在统一走这里：
 * <ul>
 *   <li>{@link #draw}：图标 + 数字，逐段画（金/银/铜）。<b>零值段不画</b>，用于行内单价</li>
 *   <li>{@link #drawFull}：同上但<b>三段都画</b>，用于余额（Aki 口径：余额不许隐藏零值段）</li>
 *   <li>{@link #text}：纯文字兜底，只给状态行/聊天这类「只能塞字符串」的地方</li>
 * </ul>
 *
 * <p>★图标一律几何绘制★：不依赖字形（默认字体没有 ▸/∞ 这类符号，会渲染成豆腐块），
 * 也不依赖配套模组的物品贴图（没装 Numismatic 时同样是空的）。
 * 7×7 圆片 + 1px 描边，纵向 y+1 与 9px 字形基线对齐。
 */
public final class HubMoney {

	/** 硬币图标边长（含描边）。 */
	public static final int ICON = 7;
	/** 图标与数字之间的间距。 */
	private static final int ICON_TEXT = 2;
	/** 两个币种之间的间距。 */
	private static final int SEG_GAP = 5;

	private HubMoney() {
	}

	/** 拆成 {金, 银, 铜}（负数按 0 处理，与 {@code Currency.format} 同口径）。 */
	public static long[] split(long raw) {
		long copper = Math.max(0, raw);
		long gc = Math.max(1, com.akira.redtale.economy.economy.Currency.goldInCopper());
		long sc = Math.max(1, com.akira.redtale.economy.economy.Currency.silverInCopper());
		long gold = copper / gc;
		long rest = copper % gc;
		return new long[]{gold, rest / sc, rest % sc};
	}

	/** 零值段隐藏的紧凑文字：{@code 1金20银3铜}（余额为 0 时给「0铜」）。 */
	public static String text(long raw) {
		long[] p = split(raw);
		StringBuilder sb = new StringBuilder();
		if (p[0] > 0) sb.append(p[0]).append('金');
		if (p[1] > 0) sb.append(p[1]).append('银');
		if (p[2] > 0 || sb.length() == 0) sb.append(p[2]).append('铜');
		return sb.toString();
	}

	/** 三段全显的文字（余额口径）：{@code 0金0银5铜}。 */
	public static String fullText(long raw) {
		long[] p = split(raw);
		return p[0] + "金" + p[1] + "银" + p[2] + "铜";
	}

	/** 三段全显的宽（供右侧对齐/预算判断）。 */
	public static int fullWidth(TextRenderer tr, long raw) {
		long[] p = split(raw);
		int w = ICON + ICON_TEXT + tr.getWidth(Long.toString(p[0]));
		w += SEG_GAP + ICON + ICON_TEXT + tr.getWidth(Long.toString(p[1]));
		w += SEG_GAP + ICON + ICON_TEXT + tr.getWidth(Long.toString(p[2]));
		return w;
	}

	/** 零值隐藏的宽。 */
	public static int width(TextRenderer tr, long raw) {
		long[] p = split(raw);
		int w = 0;
		int segs = 0;
		for (int i = 0; i < 3; i++) {
			boolean show = p[i] > 0 || (i == 2 && p[0] == 0 && p[1] == 0);
			if (!show) continue;
			if (segs++ > 0) w += SEG_GAP;
			w += ICON + ICON_TEXT + tr.getWidth(Long.toString(p[i]));
		}
		return w;
	}

	/**
	 * 画「图标 + 数字」三段（零值段不画）。
	 *
	 * @param maxW 可用宽度（≤0 表示不限）；放不下的段直接跳过，不会溢出压到别的东西
	 * @return 实际画出的总宽
	 */
	public static int draw(DrawContext dc, TextRenderer tr, int x, int y, long raw, int maxW) {
		return drawInternal(dc, tr, x, y, raw, maxW, false);
	}

	/** 画三段全显版本（余额用）：零值段也画，读起来是「0金 100银 0铜」。 */
	public static int drawFull(DrawContext dc, TextRenderer tr, int x, int y, long raw, int maxW) {
		return drawInternal(dc, tr, x, y, raw, maxW, true);
	}

	private static int drawInternal(DrawContext dc, TextRenderer tr, int x, int y, long raw,
			int maxW, boolean full) {
		if (raw < 0) {
			dc.drawText(tr, Text.literal("…"), x, y, HubTheme.MUTED, false);
			return tr.getWidth("…");
		}
		long[] p = split(raw);
		int cx = x;
		int drawn = 0;
		for (int i = 0; i < 3; i++) {
			boolean show = full || p[i] > 0 || (i == 2 && p[0] == 0 && p[1] == 0);
			if (!show) continue;
			String n = Long.toString(p[i]);
			int need = ICON + ICON_TEXT + tr.getWidth(n);
			if (drawn > 0) need += SEG_GAP;
			if (maxW > 0 && cx - x + need > maxW) break;
			if (drawn > 0) cx += SEG_GAP;
			coin(dc, cx, y + 1, i);
			dc.drawText(tr, Text.literal(n), cx + ICON + ICON_TEXT, y,
					i == 0 ? HubTheme.GOLD : HubTheme.TEXT, false);
			cx += ICON + ICON_TEXT + tr.getWidth(n);
			drawn++;
		}
		return cx - x;
	}

	/** 几何硬币：1px 描边 + 实心面，7×7，四角切掉（圆形近似）。 */
	private static void coin(DrawContext dc, int x, int y, int kind) {
		int face = switch (kind) {
			case 0 -> HubTheme.COIN_GOLD;
			case 1 -> HubTheme.COIN_SILVER;
			default -> HubTheme.COIN_COPPER;
		};
		int edge = switch (kind) {
			case 0 -> HubTheme.COIN_GOLD_EDGE;
			case 1 -> HubTheme.COIN_SILVER_EDGE;
			default -> HubTheme.COIN_COPPER_EDGE;
		};
		// 外层（描边）
		dc.fill(x + 1, y, x + ICON - 1, y + ICON, edge);
		dc.fill(x, y + 1, x + ICON, y + ICON - 1, edge);
		// 内层（面）
		dc.fill(x + 2, y + 1, x + ICON - 2, y + ICON - 1, face);
		dc.fill(x + 1, y + 2, x + ICON - 1, y + ICON - 2, face);
	}
}
