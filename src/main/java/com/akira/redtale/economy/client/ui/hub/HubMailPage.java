package com.akira.redtale.economy.client.ui.hub;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;

/**
 * Hub 内的邮件页。
 *
 * <p>信件由 Contact 自己渲染（我们共用同一个背包容器），所以这里只给入口 +
 * 待领取货币的领取按钮，不做第二套信件界面。
 */
public final class HubMailPage {


	private HubMailPage() {
	}

	public enum Action { NONE, OPEN_MAIL, CLAIM }

	public static void render(DrawContext dc, TextRenderer tr, int x, int y, int w, int h,
			double mouseX, double mouseY) {
		dc.drawText(tr, Text.literal("邮件"), x, y, HubTheme.TITLE, false);

		dc.fill(x, y + 16, x + w, y + 62, HubTheme.CARD);
		dc.drawText(tr, Text.literal("信件由 Contact 提供"), x + 8, y + 24, HubTheme.TEXT, false);
		dc.drawText(tr, Text.literal("打开后与箱子界面完全一致，可拖拽 / Shift 取信"), x + 8, y + 38, HubTheme.MUTED, false);
		dc.drawText(tr, Text.literal("我们与它共用同一个背包容器，取走即同步"), x + 8, y + 50, HubTheme.MUTED, false);

		drawButton(dc, tr, x, y + 72, 110, 22, "打开信箱", mouseX, mouseY);
		drawButton(dc, tr, x + 118, y + 72, 110, 22, "领取货币", mouseX, mouseY);
	}

	private static void drawButton(DrawContext dc, TextRenderer tr, int x, int y, int w, int h,
			String label, double mouseX, double mouseY) {
		boolean hover = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
		dc.fill(x, y, x + w, y + h, hover ? HubTheme.PRIMARY_SOFT_HOVER : HubTheme.PRIMARY_SOFT);
		dc.fill(x, y, x + w, y + 1, HubTheme.GOLD);
		dc.fill(x, y + h - 1, x + w, y + h, HubTheme.GOLD);
		dc.drawText(tr, Text.literal(label), x + (w - tr.getWidth(label)) / 2, y + 7, HubTheme.PRIMARY_SOFT_TEXT, false);
	}

	public static Action click(double mx, double my, int x, int y, int w, int h) {
		if (my >= y + 72 && my <= y + 94) {
			if (mx >= x && mx <= x + 110) return Action.OPEN_MAIL;
			if (mx >= x + 118 && mx <= x + 228) return Action.CLAIM;
		}
		return Action.NONE;
	}
}
