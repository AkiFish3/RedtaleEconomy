package com.akira.redtale.economy.client.ui.hub;

import com.akira.redtale.economy.client.title.TitlesClientNetworking;
import com.akira.redtale.economy.net.TitleProtocol;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;

/** 称号页：列出已拥有的称号，点一下装备，右键卸下。 */
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
		String cur = s.current() == null || s.current().isEmpty() ? "未装备" : s.current();
		String info = "当前：" + cur;
		dc.drawText(tr, Text.literal(info), x + w - tr.getWidth(info), y, HubTheme.MUTED, false);
		dc.fill(x, y + 12, x + w, y + 13, HubTheme.OUTLINE);

		if (!s.enabled()) {
			dc.drawText(tr, Text.literal("称号模块未启用（title.json）"), x + 2, y + 20,
					HubTheme.MUTED, false);
			return;
		}
		if (s.owned().isEmpty()) {
			dc.drawText(tr, Text.literal("还没有称号。管理员可用 /titles give 发放。"),
					x + 2, y + 20, HubTheme.MUTED, false);
			return;
		}
		int rowH = 20;
		int max = Math.min(s.owned().size(), (h - 34) / rowH);
		for (int i = 0; i < max; i++) {
			int ry = y + 18 + i * rowH;
			boolean on = s.owned().get(i).equals(s.current());
			boolean hover = mouseX >= x && mouseX < x + w && mouseY >= ry && mouseY < ry + rowH - 2;
			HubWidgets.roundRect(dc, x, ry, w, rowH - 2, 2,
					on ? HubTheme.SELECT : (hover ? HubTheme.HOVER : HubTheme.CARD));
			dc.drawText(tr, Text.literal(s.owned().get(i)), x + 6, ry + 5,
					on ? HubTheme.PRIMARY : HubTheme.TEXT, false);
			String btn = on ? "已装备" : "装备";
			dc.drawText(tr, Text.literal(btn), x + w - 12 - tr.getWidth(btn), ry + 5,
					on ? HubTheme.PRIMARY : HubTheme.MUTED, false);
		}
		String hint = "左键装备 · 右键从列表移除";
		dc.drawText(tr, Text.literal(hint), x, y + h - 10, HubTheme.MUTED, false);
	}

	public static Result click(double mx, double my, int x, int y, int w, int h, int button) {
		TitleProtocol.Snapshot s = TitlesClientNetworking.LAST;
		int rowH = 20;
		int max = Math.min(s.owned().size(), (h - 34) / rowH);
		for (int i = 0; i < max; i++) {
			int ry = y + 18 + i * rowH;
			if (mx >= x && mx < x + w && my >= ry && my < ry + rowH - 2) {
				return new Result(button == 1 ? Hit.REMOVE : Hit.SELECT, i);
			}
		}
		return new Result(Hit.NONE, -1);
	}
}
