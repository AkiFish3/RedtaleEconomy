package com.akira.redtale.economy.client.ui.hub;

import com.akira.redtale.economy.client.guild.GuildClientNetworking;
import com.akira.redtale.economy.net.GuildProtocol;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;

import java.util.List;

/** Hub 内的协会页：档位阶梯 + 声望进度。 */
public final class HubGuildPage {

	private static final int C_TRACK = 0xFF151515;

	private HubGuildPage() {
	}

	public static void render(DrawContext dc, TextRenderer tr, int x, int y, int w, int h,
			double mouseX, double mouseY) {
		GuildProtocol.Snapshot s = GuildClientNetworking.LAST;

		String cur = s.currentName().isEmpty() ? "无档位" : s.currentName();
		dc.drawText(tr, Text.literal(cur), x, y, 0xFFF0E6D2, false);
		String rep = s.rep() + " 声望";
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
		dc.fill(x, barY, x + w, barY + 6, C_TRACK);
		if (nextRep > 0) {
			long base = ranks.get(s.currentIndex()).rep();
			double p = Math.min(1.0, (double) (s.rep() - base) / Math.max(1, nextRep - base));
			dc.fill(x, barY, x + (int) (w * p), barY + 6, HubTheme.GOLD);
			dc.drawText(tr, Text.literal("距 " + nextName + " 还差 " + Math.max(0, nextRep - s.rep())),
					x, barY + 9, HubTheme.MUTED, false);
		} else {
			dc.drawText(tr, Text.literal("已达最高档"), x, barY + 9, HubTheme.MUTED, false);
		}

		// 档位阶梯
		int ly = y + 34;
		for (int i = 0; i < ranks.size(); i++) {
			int ry = ly + i * 20;
			if (ry > y + h - 24) break;
			boolean isCur = i == s.currentIndex();
			dc.fill(x, ry, x + w, ry + 18, isCur ? HubTheme.SELECT : HubTheme.CARD);
			if (isCur) dc.fill(x, ry, x + 3, ry + 18, HubTheme.GOLD);
			dc.drawText(tr, Text.literal(ranks.get(i).name()), x + 8, ry + 5,
					isCur ? 0xFFFFE9A8 : HubTheme.TEXT, false);
			String need = ranks.get(i).rep() + "";
			dc.drawText(tr, Text.literal(need), x + w - 6 - tr.getWidth(need), ry + 5,
					isCur ? HubTheme.GOLD : HubTheme.MUTED, false);
		}
	}
}
