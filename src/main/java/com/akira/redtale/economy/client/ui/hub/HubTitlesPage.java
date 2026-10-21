package com.akira.redtale.economy.client.ui.hub;

import com.akira.redtale.economy.client.title.TitlesClientNetworking;
import com.akira.redtale.economy.module.impl.TitleStyle;
import com.akira.redtale.economy.net.TitleProtocol;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;

/** 称号页：列出已拥有的称号，点一下装备，右键卸下；顶部显示当前前缀/后缀与配色（T23）。 */
public final class HubTitlesPage {

	public enum Hit { NONE, SELECT, REMOVE, CLEAR }

	public record Result(Hit hit, int index) {
	}

	private HubTitlesPage() {
	}

	public static void render(DrawContext dc, TextRenderer tr, int x, int y, int w, int h,
			double mouseX, double mouseY) {
		TitleProtocol.Snapshot s = TitlesClientNetworking.LAST;
		dc.drawText(tr, Text.literal("称号"), x, y, HubTheme.TITLE, false);
		String cur = s.prefix() == null || s.prefix().isEmpty() ? "未装备" : s.prefix();
		String info = "当前：" + cur;
		dc.drawText(tr, Text.literal(info), x + w - tr.getWidth(info), y, HubTheme.MUTED, false);
		dc.fill(x, y + 12, x + w, y + 13, HubTheme.OUTLINE);

		if (!s.enabled()) {
			dc.drawText(tr, Text.literal("称号模块未启用（title.json）"), x + 2, y + 20,
					HubTheme.MUTED, false);
			return;
		}

		// T23：配色 + 后缀预览（渐变在这里是真渐变，聊天侧由 StyledChat 承担）。
		int chrome = 34;
		int styleY = y + 18;
		String palette = s.gradient()
				? TitleStyle.hex(s.color1()) + " → " + TitleStyle.hex(s.color2()) + "（渐变）"
				: TitleStyle.hex(s.color1()) + "（纯色）";
		dc.drawText(tr, Text.literal("配色 " + palette), x + 2, styleY, HubTheme.MUTED, false);
		String suffix = s.suffix() == null || s.suffix().isEmpty() ? "无" : "[" + s.suffix() + "]";
		String suffixLine = "后缀 " + suffix;
		dc.drawText(tr, Text.literal(suffixLine), x + w - tr.getWidth(suffixLine), styleY,
				HubTheme.MUTED, false);
		if (!s.prefix().isEmpty()) {
			drawStyled(dc, tr, cur, x + 2, styleY + 12, s.gradient(), s.color1(), s.color2());
			chrome = 48;
		}

		if (s.owned().isEmpty()) {
			dc.drawText(tr, Text.literal("还没有称号。管理员可用 /titles give 发放。"),
					x + 2, y + chrome + 2, HubTheme.MUTED, false);
			return;
		}
		int rowH = 20;
		int max = Math.min(s.owned().size(), (h - chrome) / rowH);
		for (int i = 0; i < max; i++) {
			int ry = y + chrome + i * rowH;
			boolean on = s.owned().get(i).equals(s.prefix());
			boolean hover = mouseX >= x && mouseX < x + w && mouseY >= ry && mouseY < ry + rowH - 2;
			HubWidgets.roundRect(dc, x, ry, w, rowH - 2, 2,
					on ? HubTheme.SELECT : (hover ? HubTheme.HOVER : HubTheme.CARD));
			drawStyled(dc, tr, s.owned().get(i), x + 6, ry + 5,
					s.gradient(), s.color1(), s.color2());
			String btn = on ? "已装备" : "装备";
			dc.drawText(tr, Text.literal(btn), x + w - 12 - tr.getWidth(btn), ry + 5,
					on ? HubTheme.PRIMARY : HubTheme.MUTED, false);
		}
		String hint = "左键装备 · 右键从列表移除";
		dc.drawText(tr, Text.literal(hint), x, y + h - 10, HubTheme.MUTED, false);
	}

	/** 逐字着色（与 TitleStyle 同一套插值，保证界面与聊天色一致）。 */
	private static void drawStyled(DrawContext dc, TextRenderer tr, String text, int x, int y,
			boolean gradient, int c1, int c2) {
		int len = text == null ? 0 : text.length();
		int cx = x;
		for (int i = 0; i < len; i++) {
			String ch = String.valueOf(text.charAt(i));
			int rgb = TitleStyle.at(gradient, c1, c2, i, len);
			dc.drawText(tr, Text.literal(ch), cx, y, 0xFF000000 | rgb, false);
			cx += tr.getWidth(ch);
		}
	}

	public static Result click(double mx, double my, int x, int y, int w, int h, int button) {
		TitleProtocol.Snapshot s = TitlesClientNetworking.LAST;
		int chrome = s.prefix() == null || s.prefix().isEmpty() ? 34 : 48;
		int rowH = 20;
		int max = Math.min(s.owned().size(), (h - chrome) / rowH);
		for (int i = 0; i < max; i++) {
			int ry = y + chrome + i * rowH;
			if (mx >= x && mx < x + w && my >= ry && my < ry + rowH - 2) {
				return new Result(button == 1 ? Hit.REMOVE : Hit.SELECT, i);
			}
		}
		return new Result(Hit.NONE, -1);
	}
}
