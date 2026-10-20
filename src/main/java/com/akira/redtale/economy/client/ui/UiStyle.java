package com.akira.redtale.economy.client.ui;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.DrawContext;

/**
 * 统一界面风格（U6 / spec §28 的「更现代的原版风」落点）。
 *
 * <p>比原来的一层纯色面板多了：外阴影、1px 高光边（顶部偏亮、底部偏暗）、卡片层级、
 * 悬停态与物品格凹槽。全部用 {@code fill} 叠加实现，<b>不依赖任何贴图</b>，
 * 所以资源包/分辨率变化都不会让界面变形。
 *
 * <p>颜色集中在这里，换皮只改这一个文件（或以 {@code ui/theme.json5} 覆盖 {@link UiTheme}）。
 */
@Environment(EnvType.CLIENT)
public final class UiStyle {

	// 面板
	public static final int SHADOW = 0x66000000;
	public static final int PANEL_BG = 0xF21B1B22;
	public static final int PANEL_INNER = 0xFF26262F;
	public static final int BORDER = 0xFF4A4A57;
	public static final int BORDER_LIGHT = 0xFF6E6E7A;

	// 卡片
	public static final int CARD = 0xFF2E2E38;
	public static final int CARD_HOVER = 0xFF3A3A46;

	// 文字
	public static final int TEXT = 0xFFE8E8EE;
	public static final int TEXT_DIM = 0xFF9A9AA6;
	public static final int TEXT_FAINT = 0xFF6E6E7A;

	// 语义色
	public static final int ACCENT = 0xFFE9C46A;
	public static final int GOOD = 0xFF7BC67E;
	public static final int BAD = 0xFFE05A47;

	// 物品格
	public static final int SLOT = 0xFF8B8B8B;
	public static final int SLOT_INNER = 0xFF37373F;

	private UiStyle() {
	}

	/** 标准面板：阴影 + 底 + 边框 + 顶部高光。 */
	public static void panel(DrawContext ctx, int x, int y, int w, int h) {
		ctx.fill(x - 4, y - 4, x + w + 4, y + h + 4, SHADOW);
		ctx.fill(x, y, x + w, y + h, PANEL_BG);
		ctx.fill(x + 1, y + 1, x + w - 1, y + h - 1, PANEL_INNER);
		ctx.drawBorder(x, y, w, h, BORDER);
		ctx.fill(x + 1, y + 1, x + w - 1, y + 2, BORDER_LIGHT);
	}

	/** 卡片：比面板亮一层，用于任务卡/商品行。 */
	public static void card(DrawContext ctx, int x, int y, int w, int h, boolean hover) {
		ctx.fill(x, y, x + w, y + h, hover ? CARD_HOVER : CARD);
		ctx.drawBorder(x, y, w, h, hover ? BORDER_LIGHT : BORDER);
	}

	/** 原版风格的物品格凹槽。 */
	public static void slot(DrawContext ctx, int x, int y, int size) {
		ctx.fill(x, y, x + size, y + size, SLOT);
		ctx.fill(x + 1, y + 1, x + size - 1, y + size - 1, SLOT_INNER);
	}

	/** 进度条（ratio 0~1，自动钳制）。 */
	public static void progress(DrawContext ctx, int x, int y, int w, int h, double ratio) {
		double r = Math.max(0, Math.min(1, ratio));
		ctx.fill(x, y, x + w, y + h, SLOT_INNER);
		ctx.fill(x, y, x + (int) Math.round(w * r), y + h, ACCENT);
		ctx.drawBorder(x, y, w, h, BORDER);
	}

	/** 分隔线。 */
	public static void divider(DrawContext ctx, int x, int y, int w) {
		ctx.fill(x, y, x + w, y + 1, BORDER);
		ctx.fill(x, y + 1, x + w, y + 2, 0x22000000);
	}
}
