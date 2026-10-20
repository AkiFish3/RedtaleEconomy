package com.akira.redtale.economy.client.ui.hub;

import com.akira.redtale.economy.client.bounty.BountyClientNetworking;
import com.akira.redtale.economy.net.BountyProtocol;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;

import java.util.List;

/**
 * Hub 内的悬赏页（U7 / R31）。
 *
 * <p>数据来自 {@link BountyClientNetworking#LAST}（服务端 `/bounty list` 下发），
 * 每条一张卡：名称 + 进度条 + 奖励 + 领取按钮。
 */
public final class HubBountyPage {

	private static final int C_GREEN = 0xFF6BD46B;
	private static final int C_TRACK = 0xFF151515;

	private HubBountyPage() {
	}

	public enum Action {
		NONE, CLAIM_ALL, CLAIM
	}

	/** 渲染；返回被点击的卡片下标（-1 = 没点到卡）。 */
	public static int render(DrawContext dc, TextRenderer tr, int x, int y, int w, int h,
			double mouseX, double mouseY, int[] hovered) {
		BountyProtocol.Snapshot snap = BountyClientNetworking.LAST;
		List<BountyProtocol.Entry> list = snap.entries();

		dc.drawText(tr, Text.literal("每日悬赏"), x, y, 0xFFF0E6D2, false);
		dc.drawText(tr, Text.literal("完成后点领取"), x + w - tr.getWidth("完成后点领取"), y, HubTheme.MUTED, false);

		int cy = y + 16;
		int cardH = 44;
		if (list.isEmpty()) {
			dc.fill(x, cy, x + w, cy + 40, HubTheme.CARD);
			dc.drawText(tr, Text.literal("还没有悬赏数据"), x + 8, cy + 8, HubTheme.MUTED, false);
			dc.drawText(tr, Text.literal("打开一次 /bounty 或点右下角刷新"), x + 8, cy + 22, HubTheme.MUTED, false);
			return -1;
		}

		int idx = -1;
		for (int i = 0; i < list.size(); i++) {
			BountyProtocol.Entry e = list.get(i);
			int top = cy + i * (cardH + 4);
			if (top + cardH > y + h - 26) break;
			boolean hover = mouseX >= x && mouseX < x + w && mouseY >= top && mouseY < top + cardH;
			if (hover) idx = i;

			dc.fill(x, top, x + w, top + cardH, HubTheme.CARD);
			dc.fill(x, top, x + w, top + 1, e.claimed() ? HubTheme.LINE : (e.done() ? HubTheme.GOLD : HubTheme.LINE));

			dc.drawText(tr, Text.literal(pretty(e.id())), x + 6, top + 6, HubTheme.TEXT, false);
			String prog = Math.min(e.progress(), e.need()) + " / " + e.need();
			dc.drawText(tr, Text.literal(prog), x + w - 6 - tr.getWidth(prog), top + 6, HubTheme.MUTED, false);

			// 进度条
			int barW = w - 12 - 60;
			int barY = top + 20;
			dc.fill(x + 6, barY, x + 6 + barW, barY + 6, C_TRACK);
			int fill = e.need() <= 0 ? 0 : (int) (barW * Math.min(1.0, (double) e.progress() / e.need()));
			dc.fill(x + 6, barY, x + 6 + fill, barY + 6, e.done() ? C_GREEN : HubTheme.GOLD);

			dc.drawText(tr, Text.literal("+" + e.reward() + " 铜"), x + 6, top + 31, HubTheme.GOLD, false);

			String btn = e.claimed() ? "已领取" : (e.done() ? "领取" : "进行中");
			boolean can = !e.claimed() && e.done();
			int bw = tr.getWidth(btn) + 14;
			int bx = x + w - 6 - bw;
			dc.fill(bx, top + 26, bx + bw, top + 40, can ? 0xFF3A3020 : 0xFF242424);
			dc.fill(bx, top + 26, bx + bw, top + 27, can ? HubTheme.GOLD : HubTheme.LINE);
			dc.fill(bx, top + 39, bx + bw, top + 40, can ? HubTheme.GOLD : HubTheme.LINE);
			dc.drawText(tr, Text.literal(btn), bx + 7, top + 29, can ? 0xFFFFE9A8 : 0xFF6A6A6A, false);
		}
		hovered[0] = idx;
		return idx;
	}

	/** 名称美化：`zombie20` → `Zombie 20`（没有本地化表前先这样）。 */
	private static String pretty(String id) {
		if (id == null || id.isEmpty()) return "悬赏";
		return Character.toUpperCase(id.charAt(0)) + id.substring(1);
	}
}
