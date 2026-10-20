package com.akira.redtale.economy.client.ui.hub;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.Identifier;

/**
 * 原版风格素材（theme = vanilla）。
 *
 * <p>接口用原版「灰底 + 立体边框」的做法，而不是拉伸容器贴图——
 * 拉伸 generic_54 会在底部露出物品栏花纹，还会错位。
 */
public final class HubVanilla {

	public static final Identifier WIDGETS =
			new Identifier("minecraft", "textures/gui/widgets.png");
	public static final Identifier CONTAINER =
			new Identifier("minecraft", "textures/gui/container/generic_54.png");

	private HubVanilla() {
	}

	/** 屏幕背景：黑色半透明（Aki 要求，不要泥土纹理）。 */
	public static void background(DrawContext dc, int screenW, int screenH) {
		dc.fill(0, 0, screenW, screenH, 0xB0000000);
	}

	/** 原版窗口：灰底 + 左上亮边 + 右下暗边（和箱子/选项界面一致）。 */
	public static void panel(DrawContext dc, int x, int y, int w, int h) {
		dc.fill(x, y, x + w, y + h, 0xFFC6C6C6);
		dc.fill(x, y, x + w, y + 2, 0xFFFFFFFF);            // 顶部高光
		dc.fill(x, y, x + 2, y + h, 0xFFFFFFFF);            // 左侧高光
		dc.fill(x, y + h - 2, x + w, y + h, 0xFF555555);    // 底部阴影
		dc.fill(x + w - 2, y, x + w, y + h, 0xFF555555);    // 右侧阴影
		// 内层再压一道浅灰，做出厚度
		dc.fill(x + 2, y + 2, x + w - 2, y + h - 2, 0xFFC6C6C6);
	}

	/** 原版按钮（widgets.png 三态）。 */
	public static void button(DrawContext dc, int x, int y, int w, int h, boolean hover, boolean disabled) {
		int v = disabled ? 46 : (hover ? 86 : 66);
		dc.drawTexture(WIDGETS, x, y, 0, v, 2, 20, 256, 256);
		int fill = x + 2;
		while (fill < x + w - 2) {
			int seg = Math.min(196, x + w - 2 - fill);
			dc.drawTexture(WIDGETS, fill, y, 2, v, seg, 20, 256, 256);
			fill += seg;
		}
		dc.drawTexture(WIDGETS, x + w - 2, y, 198, v, 2, 20, 256, 256);
	}

	/** 原版输入框：深色底 + 内凹边（比手绘描边更像原版）。 */
	public static void field(DrawContext dc, int x, int y, int w, int h, boolean focused) {
		dc.fill(x, y, x + w, y + h, focused ? 0xFF000000 : 0xFF1A1A1A);
		dc.fill(x, y, x + w, y + 1, 0xFF555555);
		dc.fill(x, y, x + 1, y + h, 0xFF555555);
		dc.fill(x, y + h - 1, x + w, y + h, 0xFFFFFFFF);
		dc.fill(x + w - 1, y, x + w, y + h, 0xFFFFFFFF);
	}

	/** 原版槽位凹槽（18×18）。 */
	public static void slot(DrawContext dc, int x, int y) {
		dc.drawTexture(CONTAINER, x, y, 7, 17, 18, 18, 256, 256);
	}
}
