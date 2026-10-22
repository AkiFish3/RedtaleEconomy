package com.akira.redtale.economy.client.ui.hub;

import com.akira.redtale.economy.client.guild.GuildClientNetworking;
import com.akira.redtale.economy.net.GuildProtocol;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;

import java.util.List;

/** Hub 内的协会页：档位阶梯 + 声望进度。 */
public final class HubGuildPage {

	/** 档位行高 / 阶梯起点（相对内容区顶）/ 底部预留。 */
	private static final int ROW_H = 20;
	private static final int LIST_TOP = 34;
	private static final int BOTTOM_RESERVE = 24;

	/** 滚动行号（档位表来自配置，长度不固定）。 */
	private static int scroll;

	private HubGuildPage() {
	}

	public static void resetScroll() {
		scroll = 0;
	}

	/** 一屏能放下几行档位。 */
	static int rowsVisible(int h) {
		int usable = h - LIST_TOP - BOTTOM_RESERVE;
		if (usable < 0) return 0;
		return 1 + usable / ROW_H;
	}

	static int maxScroll(int h, int size) {
		return Math.max(0, size - rowsVisible(h));
	}

	/** 滚轮：默认配置 7 档，427×240 只放得下 6 行 ⇒ 最高档会整行看不见。 */
	public static void scrollByRows(int delta, int h) {
		int size = GuildClientNetworking.LAST.ranks().size();
		scroll = Math.max(0, Math.min(maxScroll(h, size), scroll + delta));
	}

	/** 仅探针：当前滚动行。 */
	public static int scrollRow() {
		return scroll;
	}

	public static void render(DrawContext dc, TextRenderer tr, int x, int y, int w, int h,
			double mouseX, double mouseY) {
		GuildProtocol.Snapshot s = GuildClientNetworking.LAST;

		String cur = s.currentName().isEmpty() ? "无档位" : s.currentName();
		dc.drawText(tr, Text.literal(cur), x, y, HubTheme.TITLE, false);
		// T59/T64：声望可为负 —— 负值染红并标注，避免和「进度条没动」混为一谈
		String rep = s.rep() + " 声望" + (s.rep() < 0 ? "（负）" : "");
		dc.drawText(tr, Text.literal(rep), x + w - tr.getWidth(rep), y, HubTheme.GOLD, false);

		// 到下一档的进度
		List<GuildProtocol.Rank> ranks = s.ranks();
		long nextRep = -1;
		String nextName = "";
		if (s.currentIndex() + 1 < ranks.size()) {
			GuildProtocol.Rank nx = ranks.get(s.currentIndex() + 1);
			nextRep = nx.rep();
			nextName = nx.name();
		}
		int barY = y + 16;
		dc.fill(x, barY, x + w, barY + 6, HubTheme.TRACK);
		if (nextRep > 0) {
			long base = ranks.get(s.currentIndex()).rep();
			// 负声望时 (rep-base) 为负 ⇒ 必须夹到 [0,1]，否则 fill 会画出负宽度
			double p = Math.max(0.0, Math.min(1.0, (double) (s.rep() - base) / Math.max(1, nextRep - base)));
			dc.fill(x, barY, x + (int) (w * p), barY + 6, HubTheme.GOLD);
			dc.drawText(tr, Text.literal("距 " + nextName + " 还差 " + Math.max(0, nextRep - s.rep())),
					x, barY + 9, HubTheme.MUTED, false);
		} else {
			dc.drawText(tr, Text.literal("已达最高档"), x, barY + 9, HubTheme.MUTED, false);
		}

		// 档位阶梯（滚过之后 屏内行 ≠ 数据下标）
		int per = rowsVisible(h);
		int max = maxScroll(h, ranks.size());
		scroll = Math.max(0, Math.min(max, scroll));
		for (int row = 0; row < per; row++) {
			int i = scroll + row;
			if (i >= ranks.size()) break;
			int ry = y + LIST_TOP + row * ROW_H;
			boolean isCur = i == s.currentIndex();
			dc.fill(x, ry, x + w, ry + 18, isCur ? HubTheme.SELECT : HubTheme.CARD);
			if (isCur) dc.fill(x, ry, x + 3, ry + 18, HubTheme.GOLD);
			dc.drawText(tr, Text.literal(ranks.get(i).name()), x + 8, ry + 5,
					isCur ? HubTheme.PRIMARY_SOFT_TEXT : HubTheme.TEXT, false);
			String need = ranks.get(i).rep() + "";
			dc.drawText(tr, Text.literal(need), x + w - 6 - tr.getWidth(need), ry + 5,
					isCur ? HubTheme.GOLD : HubTheme.MUTED, false);
		}
		// 溢出时画 1px 滚动条（不画的话最后一档被切掉时看不出还有内容）
		if (max > 0) {
			int barX = x + w - 1;
			int trackY = y + LIST_TOP;
			int trackH = Math.max(1, h - LIST_TOP - BOTTOM_RESERVE);
			dc.fill(barX, trackY, barX + 1, trackY + trackH, HubTheme.TRACK);
			int knobH = Math.max(8, trackH * Math.max(1, per) / ranks.size());
			int knobY = trackY + (trackH - knobH) * scroll / max;
			dc.fill(barX, knobY, barX + 1, knobY + knobH, HubTheme.LINE);
		}
	}
}
