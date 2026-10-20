package com.akira.redtale.economy.client.ui.hub;

import com.akira.redtale.economy.client.pass.PassClientNetworking;
import com.akira.redtale.economy.net.PassProtocol;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;

import java.util.List;

/**
 * Hub 内的战令页（U4/R20）：上下双轨 + 滚轮横滑 + 领取。
 *
 * <p>数据来自 {@link PassClientNetworking#LAST}（服务端 `/pass` 下发）。
 * 两轨共用同一个横向偏移，等级始终对齐。
 */
public final class HubPassPage {

	private static final int CELL_W = 46;
	private static final int CELL_H = 52;
	private static final int GAP = 4;

	private static final int C_CELL_OK = 0xFF332B18;
	private static final int C_TRACK = 0xFF151515;
	private static final int C_DONE = 0xFF2A2A2A;

	private HubPassPage() {
	}

	/** 领取动作。 */
	public record Claim(String track, int level) {
	}

	public static void render(DrawContext dc, TextRenderer tr, int x, int y, int w, int h,
			int scroll, double mouseX, double mouseY) {
		PassProtocol.Snapshot s = PassClientNetworking.LAST;

		dc.drawText(tr, Text.literal(s.season().isEmpty() ? "战令" : s.season()), x, y, 0xFFF0E6D2, false);
		String lv = "等级 " + s.level();
		dc.drawText(tr, Text.literal(lv), x + w - tr.getWidth(lv), y, HubTheme.GOLD, false);

		// 经验条
		int barY = y + 14;
		dc.fill(x, barY, x + w, barY + 6, C_TRACK);
		long need = Math.max(1, s.xpNext());
		int fill = (int) (w * Math.min(1.0, (double) s.xp() / need));
		dc.fill(x, barY, x + fill, barY + 6, HubTheme.GOLD);
		String xpTxt = s.xp() + " / " + need + " 经验";
		dc.drawText(tr, Text.literal(xpTxt), x, barY + 10, HubTheme.MUTED, false);

		// 免费轨
		int row1 = y + 44;
		dc.drawText(tr, Text.literal("免费"), x, row1 - 12, HubTheme.TEXT, false);
		drawTrack(dc, tr, x, row1, w, s.free(), s.claimedFree(), scroll, s.level(), true, true,
				mouseX, mouseY);

		// 付费轨
		int row2 = row1 + CELL_H + 24;
		dc.drawText(tr, Text.literal("付费"), x, row2 - 12, HubTheme.GOLD, false);
		drawTrack(dc, tr, x, row2, w, s.prem(), s.claimedPrem(), scroll, s.level(), s.premium(), false,
				mouseX, mouseY);

		// 未解锁时整条付费轨已经画暗，不再额外写文字（Aki 要求）
	}

	private static void drawTrack(DrawContext dc, TextRenderer tr, int x, int y, int w,
			List<PassProtocol.Reward> rewards, List<Integer> claimed, int scroll, int level,
			boolean unlocked, boolean freeTrack, double mouseX, double mouseY) {
		if (rewards.isEmpty()) {
			dc.fill(x, y, x + w, y + CELL_H, HubTheme.CARD);
			dc.drawText(tr, Text.literal("奖励未配置"), x + 6, y + 8, HubTheme.MUTED, false);
			return;
		}
		int visible = Math.max(1, (w + GAP) / (CELL_W + GAP));
		int start = Math.max(0, Math.min(scroll, Math.max(0, rewards.size() - visible)));
		for (int i = start; i < Math.min(rewards.size(), start + visible); i++) {
			PassProtocol.Reward r = rewards.get(i);
			int cx = x + (i - start) * (CELL_W + GAP);
			boolean done = r.level() <= level;
			boolean got = claimed.contains(r.level());
			boolean can = unlocked && done && !got;
			boolean hover = mouseX >= cx && mouseX < cx + CELL_W && mouseY >= y && mouseY < y + CELL_H;

			int bg = got ? C_DONE : (can ? C_CELL_OK : (unlocked ? HubTheme.CARD : 0xFF1C1C1C));
			dc.fill(cx, y, cx + CELL_W, y + CELL_H, bg);
			int edge = can ? HubTheme.GOLD : (hover ? 0xFF5A5A5A : HubTheme.LINE);
			dc.fill(cx, y, cx + CELL_W, y + 1, edge);
			dc.fill(cx, y + CELL_H - 1, cx + CELL_W, y + CELL_H, edge);

			String lvl = String.valueOf(r.level());
			dc.drawText(tr, Text.literal(lvl), cx + 4, y + 4, done ? HubTheme.TEXT : HubTheme.MUTED, false);
			// 奖励：钱优先显示，其次物品数量
			String reward = r.money() > 0 ? ("+" + shortNum(r.money())) : (r.items().isEmpty() ? "—" : "×" + r.items().get(0).count());
			dc.drawText(tr, Text.literal(reward), cx + 4, y + 20, HubTheme.GOLD, false);
			String state = got ? "已领" : (done ? (unlocked ? "可领" : "锁定") : "未达");
			dc.drawText(tr, Text.literal(state), cx + 4, y + 36, can ? 0xFFFFE9A8 : HubTheme.MUTED, false);
			if (!freeTrack && !unlocked) {
				dc.fill(cx + CELL_W - 10, y + 4, cx + CELL_W - 4, y + 10, 0xFF8A7A5A);
			}
		}
	}

	private static String shortNum(long v) {
		if (v >= 10000) return (v / 1000) + "k";
		return String.valueOf(v);
	}

	/** 点击：返回要领取的 (track, level)，没点到返回 null。 */
	public static Claim click(double mx, double my, int x, int y, int w, int h, int scroll) {
		PassProtocol.Snapshot s = PassClientNetworking.LAST;
		int visible = Math.max(1, (w + GAP) / (CELL_W + GAP));
		int row1 = y + 34;
		int row2 = row1 + CELL_H + 22;

		int idx = hitCell(mx, my, x, row1, w, visible, s.free().size(), scroll);
		if (idx >= 0 && idx < s.free().size()) {
			return new Claim("free", s.free().get(idx).level());
		}
		idx = hitCell(mx, my, x, row2, w, visible, s.prem().size(), scroll);
		if (idx >= 0 && idx < s.prem().size()) {
			return new Claim("premium", s.prem().get(idx).level());
		}
		return null;
	}

	private static int hitCell(double mx, double my, int x, int rowY, int w, int visible, int size, int scroll) {
		if (my < rowY || my >= rowY + CELL_H) return -1;
		int start = Math.max(0, Math.min(scroll, Math.max(0, size - visible)));
		for (int i = start; i < Math.min(size, start + visible); i++) {
			int cx = x + (i - start) * (CELL_W + GAP);
			if (mx >= cx && mx < cx + CELL_W) return i;
		}
		return -1;
	}

	public static int maxScroll(int w) {
		int visible = Math.max(1, (w + GAP) / (CELL_W + GAP));
		int size = Math.max(PassClientNetworking.LAST.free().size(), PassClientNetworking.LAST.prem().size());
		return Math.max(0, size - visible);
	}
}
