package com.akira.redtale.economy.client.ui.hub;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;

/** 转账页：输入玩家名 + 金额，点转账。字段聚焦由 HubScreen 管。 */
public final class HubPayPage {

	public enum Hit { NONE, FIELD_NAME, FIELD_AMOUNT, SEND, CLEAR }

	public record Result(Hit hit) {
	}

	private HubPayPage() {
	}

	public static int nameX(int x) {
		return x;
	}

	public static int amountX(int x, int w) {
		return x + w / 2 + 4;
	}

	public static final int FIELD_Y = 26;
	public static final int FIELD_H = 18;

	public static void render(DrawContext dc, TextRenderer tr, int x, int y, int w, int h,
			String name, String amount, int focus, double mouseX, double mouseY) {
		dc.drawText(tr, Text.literal("转账"), x, y, HubTheme.TITLE, false);
		// T55：余额走图标 + 三段（右对齐；放不下就紧贴标题右侧，绝不与标题压字）
		long bal = com.akira.redtale.economy.client.balance.BalanceClientNetworking.RAW;
		String lbl = "余额";
		int bw = bal < 0 ? tr.getWidth("…") : HubMoney.fullWidth(tr, bal);
		int bx = Math.max(x + 80, x + w - bw);
		int lx = Math.max(x + 44, bx - tr.getWidth(lbl) - 4);
		dc.drawText(tr, Text.literal(lbl), lx, y, HubTheme.MUTED, false);
		if (bal < 0) {
			dc.drawText(tr, Text.literal("…"), bx, y, HubTheme.MUTED, false);
		} else {
			HubMoney.drawFull(dc, tr, bx, y, bal, x + w - bx);
		}
		dc.fill(x, y + 12, x + w, y + 13, HubTheme.OUTLINE);

		dc.drawText(tr, Text.literal("玩家名"), x, y + FIELD_Y - 10, HubTheme.MUTED, false);
		dc.drawText(tr, Text.literal("金额（铜）"), amountX(x, w), y + FIELD_Y - 10, HubTheme.MUTED, false);

		field(dc, tr, x, y + FIELD_Y, w / 2 - 4, FIELD_H, name, "输入玩家名…", focus == 1, mouseX, mouseY);
		field(dc, tr, amountX(x, w), y + FIELD_Y, w / 2 - 4, FIELD_H, amount, "0", focus == 2, mouseX, mouseY);

		int by = y + FIELD_Y + FIELD_H + 10;
		HubWidgets.button(dc, tr, x, by, 90, 20, "确认转账", HubWidgets.Variant.PRIMARY,
				hover(mouseX, mouseY, x, by, 90, 20), false, true);
		HubWidgets.button(dc, tr, x + 98, by, 60, 20, "清空", HubWidgets.Variant.OUTLINE,
				hover(mouseX, mouseY, x + 98, by, 60, 20), false, true);

		dc.drawText(tr, Text.literal("支持离线玩家（对方上线后自动到账）；金额单位是铜。"), x, by + 28,
				HubTheme.MUTED, false);
	}

	private static void field(DrawContext dc, TextRenderer tr, int x, int y, int w, int h,
			String value, String placeholder, boolean focused, double mouseX, double mouseY) {
		boolean hov = hover(mouseX, mouseY, x, y, w, h);
		dc.fill(x, y, x + w, y + h, focused ? 0xFF000000 : (hov ? 0xCC000000 : 0x99000000));
		dc.fill(x, y, x + w, y + 1, focused ? HubTheme.PRIMARY : HubTheme.OUTLINE);
		dc.fill(x, y + h - 1, x + w, y + h, HubTheme.OUTLINE);
		String txt = value.isEmpty() && !focused ? placeholder : value;
		int color = value.isEmpty() && !focused ? HubTheme.MUTED : HubTheme.TEXT;
		dc.drawText(tr, Text.literal(txt), x + 4, y + (h - 8) / 2, color, false);
		if (focused && (System.currentTimeMillis() / 500) % 2 == 0) {
			int cx = x + 4 + tr.getWidth(value);
			dc.fill(cx, y + 3, cx + 1, y + h - 3, HubTheme.PRIMARY);
		}
	}

	private static boolean hover(double mx, double my, int x, int y, int w, int h) {
		return mx >= x && mx < x + w && my >= y && my < y + h;
	}

	public static Result click(double mx, double my, int x, int y, int w, int h) {
		int fy = y + FIELD_Y;
		if (hover(mx, my, x, fy, w / 2 - 4, FIELD_H)) return new Result(Hit.FIELD_NAME);
		if (hover(mx, my, amountX(x, w), fy, w / 2 - 4, FIELD_H)) return new Result(Hit.FIELD_AMOUNT);
		int by = fy + FIELD_H + 10;
		if (hover(mx, my, x, by, 90, 20)) return new Result(Hit.SEND);
		if (hover(mx, my, x + 98, by, 60, 20)) return new Result(Hit.CLEAR);
		return new Result(Hit.NONE);
	}
}
