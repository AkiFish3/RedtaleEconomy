package com.akira.redtale.economy.client.ui.hub;

import com.akira.redtale.economy.client.title.TitlesClientNetworking;
import com.akira.redtale.economy.module.impl.TitleSlots;
import com.akira.redtale.economy.module.impl.TitleStyle;
import com.akira.redtale.economy.net.TitleProtocol;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

/**
 * 称号页：槽位条 + 已拥有列表（T23 配色/后缀，T60 多槽位，T66 强制称号只读）。
 *
 * <p>★几何只有一份★：{@link #dims} 给出所有纵向坐标，渲染与命中共用；
 * 改布局只改这里，否则「点得到看不见 / 看得见点不到」。
 */
public final class HubTitlesPage {

	public enum Hit { NONE, SELECT, REMOVE, CLEAR, SLOT }

	public record Result(Hit hit, int index) {
	}

	/** 布局：一切纵向坐标都从这里出。 */
	public record Dims(int styleY, int previewY, int slotsY, int rowsY, int rowH, int maxRows,
			boolean hasPreview, int chipH) {
	}

	private static final int ROW_H = 20;
	private static final int CHIP_H = 14;
	private static final int HINT_H = 10;

	private HubTitlesPage() {
	}

	/** 该快照下应该显示的槽位数（协议可能给 0/超限，这里兜底）。 */
	private static int slotCount(TitleProtocol.Snapshot s) {
		int n = s.slots();
		if (n <= 0) n = 1;
		return Math.min(n, TitleSlots.MAX_SLOTS);
	}

	public static Dims dims(TitleProtocol.Snapshot s, int y, int h) {
		int styleY = y + 18;
		boolean preview = !s.equipped().isEmpty() || !s.forcedTexts().isEmpty();
		int slotsY = preview ? styleY + 24 : styleY + 12;
		int rowsY = slotsY + CHIP_H + 6;
		int maxRows = Math.max(0, (y + h - HINT_H - rowsY) / ROW_H);
		return new Dims(styleY, styleY + 12, slotsY, rowsY, ROW_H, maxRows, preview, CHIP_H);
	}

	/** 第 {@code i} 个槽位条的左边界与宽度（居中均分）。 */
	public static int slotLeft(int x, int w, int i, int n) {
		int gap = 4;
		int cw = (w - gap * (n - 1)) / Math.max(1, n);
		return x + i * (cw + gap);
	}

	public static int slotWidth(int w, int n) {
		int gap = 4;
		return (w - gap * (n - 1)) / Math.max(1, n);
	}

	public static void render(DrawContext dc, TextRenderer tr, int x, int y, int w, int h,
			double mouseX, double mouseY) {
		TitleProtocol.Snapshot s = TitlesClientNetworking.LAST;
		dc.drawText(tr, Text.literal("称号"), x, y, HubTheme.TITLE, false);
		String joined = TitleSlots.join(s.equipped(), "·");
		String info = "当前：" + (joined.isEmpty() ? "未装备" : joined);
		dc.drawText(tr, Text.literal(info), x + w - tr.getWidth(info), y, HubTheme.MUTED, false);
		dc.fill(x, y + 12, x + w, y + 13, HubTheme.OUTLINE);

		if (!s.enabled()) {
			dc.drawText(tr, Text.literal("称号模块未启用（title.json）"), x + 2, y + 20,
					HubTheme.MUTED, false);
			return;
		}

		Dims d = dims(s, y, h);

		// 配色 + 后缀
		String palette = s.gradient()
				? TitleStyle.hex(s.color1()) + " → " + TitleStyle.hex(s.color2()) + "（渐变）"
				: TitleStyle.hex(s.color1()) + "（纯色）";
		dc.drawText(tr, Text.literal("配色 " + palette), x + 2, d.styleY(), HubTheme.MUTED, false);
		String suffix = s.suffix() == null || s.suffix().isEmpty() ? "无" : "[" + s.suffix() + "]";
		String suffixLine = "后缀 " + suffix;
		dc.drawText(tr, Text.literal(suffixLine), x + w - tr.getWidth(suffixLine), d.styleY(),
				HubTheme.MUTED, false);

		// 排行榜预览：强制称号在前，自选槽在后（与服务端 effectiveParts 同一顺序）
		if (d.hasPreview()) {
			int cx = x + 2;
			List<String> parts = new ArrayList<>(s.forcedTexts());
			parts.addAll(s.equipped());
			for (int i = 0; i < parts.size(); i++) {
				if (i > 0) {
					String sep = "·";
					dc.drawText(tr, Text.literal(sep), cx, d.previewY(), HubTheme.MUTED, false);
					cx += tr.getWidth(sep);
				}
				cx = drawStyled(dc, tr, parts.get(i), cx, d.previewY(),
						s.gradient(), s.color1(), s.color2());
			}
		}

		// 槽位条
		int n = slotCount(s);
		for (int i = 0; i < n; i++) {
			List<String> eq = s.equipped();
			String t = i < eq.size() ? eq.get(i) : "";
			int sx = slotLeft(x, w, i, n);
			int sw = slotWidth(w, n);
			boolean hover = mouseX >= sx && mouseX < sx + sw
					&& mouseY >= d.slotsY() && mouseY < d.slotsY() + CHIP_H;
			HubWidgets.roundRect(dc, sx, d.slotsY(), sw, CHIP_H, 2,
					t.isEmpty() ? HubTheme.CARD : (hover ? HubTheme.HOVER : HubTheme.SELECT));
			String label = t.isEmpty() ? "槽 " + (i + 1) + " · 空" : t;
			String shown = tr.getWidth(label) > sw - 6 ? tr.trimToWidth(label, sw - 6) + "…" : label;
			int tx = sx + (sw - tr.getWidth(shown)) / 2;
			if (t.isEmpty()) {
				dc.drawText(tr, Text.literal(shown), tx, d.slotsY() + 3, HubTheme.MUTED, false);
			} else {
				drawStyled(dc, tr, shown, tx, d.slotsY() + 3, s.gradient(), s.color1(), s.color2());
			}
			if (hover) {
				dc.drawText(tr, Text.literal("×"), sx + sw - 7, d.slotsY() + 3, HubTheme.PRIMARY, false);
			}
		}

		// 已拥有列表
		if (s.owned().isEmpty()) {
			dc.drawText(tr, Text.literal("还没有称号。管理员可用 /titles give 发放。"),
					x + 2, d.rowsY() + 2, HubTheme.MUTED, false);
			return;
		}
		int max = Math.min(s.owned().size(), d.maxRows());
		for (int i = 0; i < max; i++) {
			int ry = d.rowsY() + i * ROW_H;
			String t = s.owned().get(i);
			boolean on = s.equipped().contains(t);
			boolean hover = mouseX >= x && mouseX < x + w && mouseY >= ry && mouseY < ry + ROW_H - 2;
			HubWidgets.roundRect(dc, x, ry, w, ROW_H - 2, 2,
					on ? HubTheme.SELECT : (hover ? HubTheme.HOVER : HubTheme.CARD));
			drawStyled(dc, tr, t, x + 6, ry + 5, s.gradient(), s.color1(), s.color2());
			String btn = on ? "已装备" : "装备";
			dc.drawText(tr, Text.literal(btn), x + w - 12 - tr.getWidth(btn), ry + 5,
					on ? HubTheme.PRIMARY : HubTheme.MUTED, false);
		}
		String hint = "左键装备（自动选空槽）· 点槽位条卸下 · 右键从列表移除";
		dc.drawText(tr, Text.literal(hint), x, y + h - HINT_H, HubTheme.MUTED, false);
	}

	/** 逐字着色（与 TitleStyle 同一套插值，保证界面与聊天色一致）；返回下一个绘制 x。 */
	private static int drawStyled(DrawContext dc, TextRenderer tr, String text, int x, int y,
			boolean gradient, int c1, int c2) {
		int len = text == null ? 0 : text.length();
		int cx = x;
		for (int i = 0; i < len; i++) {
			String ch = String.valueOf(text.charAt(i));
			int rgb = TitleStyle.at(gradient, c1, c2, i, len);
			dc.drawText(tr, Text.literal(ch), cx, y, 0xFF000000 | rgb, false);
			cx += tr.getWidth(ch);
		}
		return cx;
	}

	public static Result click(double mx, double my, int x, int y, int w, int h, int button) {
		TitleProtocol.Snapshot s = TitlesClientNetworking.LAST;
		if (!s.enabled()) return new Result(Hit.NONE, -1);
		Dims d = dims(s, y, h);

		// 槽位条：左键 = 卸下该槽
		if (button == 0) {
			int n = slotCount(s);
			for (int i = 0; i < n; i++) {
				int sx = slotLeft(x, w, i, n);
				int sw = slotWidth(w, n);
				if (mx >= sx && mx < sx + sw && my >= d.slotsY() && my < d.slotsY() + CHIP_H) {
					return new Result(Hit.SLOT, i);
				}
			}
		}

		int max = Math.min(s.owned().size(), d.maxRows());
		for (int i = 0; i < max; i++) {
			int ry = d.rowsY() + i * ROW_H;
			if (mx >= x && mx < x + w && my >= ry && my < ry + ROW_H - 2) {
				return new Result(button == 1 ? Hit.REMOVE : Hit.SELECT, i);
			}
		}
		return new Result(Hit.NONE, -1);
	}
}
