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

	/** 卡片高 / 卡间距 / 标题行高 / 底部预留（分页或提示带）。 */
	private static final int CARD_H = 44;
	private static final int CARD_GAP = 4;
	private static final int HEAD_H = 16;
	private static final int BOTTOM_RESERVE = 26;

	private HubBountyPage() {
	}

	public enum Action {
		NONE, CLAIM_ALL, CLAIM
	}

	/** 滚动行号（数据下标 = scroll + 屏内行）。 */
	private static int scroll;

	/**
	 * 卡片顶边（渲染与命中共用）。{@code row} 是<b>屏内行</b>不是数据下标 ——
	 * 滚过之后两者不同，混用就会「看着点第 3 张、实际领第 1 张」。
	 *
	 * <p>★曾经这里是两份算术★：render 用 {@code cy = y+16, cardH = 44} 并且会
	 * {@code break} 掉超出可视区的卡，而点击侧直接在 `HubPassBountyClicks` 里
	 * 硬算 {@code (mouseY - (cy+16)) / 48} —— 既不判 x、也不判「这张卡画出来没有」，
	 * 于是点标题行（y..y+16）会因为整数向零截断算出 **idx = 0**，直接把第一条悬赏领了。
	 */
	static int cardTop(int y, int row) {
		return y + HEAD_H + row * (CARD_H + CARD_GAP);
	}

	/** 一屏能完整放下几张卡（底边留 BOTTOM_RESERVE 给提示带）。 */
	static int rowsVisible(int h) {
		int usable = h - BOTTOM_RESERVE - HEAD_H;
		if (usable < CARD_H) return 0;
		return 1 + (usable - CARD_H) / (CARD_H + CARD_GAP);
	}

	/**
	 * 滚动上限（张）。
	 *
	 * <p>★悬赏位是配置项，最多 9 个★（{@code perks.maxBountySlots}，协会等级还会加），
	 * 427×240 下内容区只有 160 高、一屏 2 张 ⇒ 没有滚动时第 3~9 条既看不见也领不到
	 * （与市场页 T45「一页 18 条只画 5 行」同族）。
	 */
	static int maxScroll(int h, int size) {
		return Math.max(0, size - rowsVisible(h));
	}

	public static void resetScroll() {
		scroll = 0;
	}

	/** 仅探针/夹具：当前滚动行。 */
	public static int scrollRow() {
		return scroll;
	}

	public static void scrollByRows(int delta, int h) {
		scroll = Math.max(0, Math.min(maxScroll(h, BountyClientNetworking.LAST.entries().size()),
				scroll + delta));
	}

	/** 鼠标 → 数据下标；-1 = 没点在卡上（标题行 / 卡间距 / 内容区外 / 没画出来的卡）。 */
	static int indexAt(double mx, double my, int x, int y, int w, int h, int size) {
		return indexAt(mx, my, x, y, w, h, size, scroll);
	}

	/** 带显式滚动值的版本（单测用，避免为了测几何去初始化 BountyClientNetworking）。 */
	static int indexAt(double mx, double my, int x, int y, int w, int h, int size, int scrollRow) {
		if (mx < x || mx >= x + w) return -1;
		if (my < y + HEAD_H || my >= y + h - BOTTOM_RESERVE) return -1;
		int row = (int) ((my - (y + HEAD_H)) / (CARD_H + CARD_GAP));
		if (row < 0 || row >= rowsVisible(h)) return -1;
		int top = cardTop(y, row);
		if (my >= top + CARD_H) return -1;
		int i = scrollRow + row;
		return (i >= 0 && i < size) ? i : -1;
	}

	/** 渲染；返回被点击的卡片下标（-1 = 没点到卡）。 */
	public static int render(DrawContext dc, TextRenderer tr, int x, int y, int w, int h,
			double mouseX, double mouseY, int[] hovered) {
		BountyProtocol.Snapshot snap = BountyClientNetworking.LAST;
		List<BountyProtocol.Entry> list = snap.entries();

		dc.drawText(tr, Text.literal("每日悬赏"), x, y, HubTheme.TITLE, false);
		dc.drawText(tr, Text.literal("完成后点领取"), x + w - tr.getWidth("完成后点领取"), y, HubTheme.MUTED, false);

		int cy = y + HEAD_H;
		if (list.isEmpty()) {
			dc.fill(x, cy, x + w, cy + 40, HubTheme.CARD);
			dc.drawText(tr, Text.literal("还没有悬赏数据"), x + 8, cy + 8, HubTheme.MUTED, false);
			dc.drawText(tr, Text.literal("打开一次 /bounty 或点右下角刷新"), x + 8, cy + 22, HubTheme.MUTED, false);
			return -1;
		}

		int per = rowsVisible(h);
		scroll = Math.max(0, Math.min(maxScroll(h, list.size()), scroll));
		int idx = -1;
		for (int row = 0; row < per; row++) {
			int i = scroll + row;
			if (i >= list.size()) break;
			BountyProtocol.Entry e = list.get(i);
			int top = cardTop(y, row);
			boolean hover = mouseX >= x && mouseX < x + w && mouseY >= top && mouseY < top + CARD_H;
			if (hover) idx = i;

			dc.fill(x, top, x + w, top + CARD_H, HubTheme.CARD);
			dc.fill(x, top, x + w, top + 1, e.claimed() ? HubTheme.LINE : (e.done() ? HubTheme.GOLD : HubTheme.LINE));

			dc.drawText(tr, Text.literal(pretty(e.id())), x + 6, top + 6, HubTheme.TEXT, false);
			String prog = Math.min(e.progress(), e.need()) + " / " + e.need();
			dc.drawText(tr, Text.literal(prog), x + w - 6 - tr.getWidth(prog), top + 6, HubTheme.MUTED, false);

			// 进度条
			int barW = w - 12 - 60;
			int barY = top + 20;
			dc.fill(x + 6, barY, x + 6 + barW, barY + 6, HubTheme.TRACK);
			int fill = e.need() <= 0 ? 0 : (int) (barW * Math.min(1.0, (double) e.progress() / e.need()));
			dc.fill(x + 6, barY, x + 6 + fill, barY + 6, e.done() ? HubTheme.OK : HubTheme.GOLD);

			// T55：奖励金额走图标 + 数字
			dc.drawText(tr, Text.literal("奖励"), x + 6, top + 31, HubTheme.MUTED, false);
			HubMoney.draw(dc, tr, x + 32, top + 31, e.reward(), w - 44);

			String btn = e.claimed() ? "已领取" : (e.done() ? "领取" : "进行中");
			boolean can = !e.claimed() && e.done();
			int bw = tr.getWidth(btn) + 14;
			int bx = x + w - 6 - bw;
			dc.fill(bx, top + 26, bx + bw, top + 40, can ? HubTheme.PRIMARY_SOFT : HubTheme.DISABLED_BG);
			dc.fill(bx, top + 26, bx + bw, top + 27, can ? HubTheme.GOLD : HubTheme.LINE);
			dc.fill(bx, top + 39, bx + bw, top + 40, can ? HubTheme.GOLD : HubTheme.LINE);
			dc.drawText(tr, Text.literal(btn), bx + 7, top + 29, can ? HubTheme.PRIMARY_SOFT_TEXT : HubTheme.DISABLED_TEXT, false);
		}
		// 溢出时画 1px 滚动条：没有它玩家根本不知道下面还有悬赏（卡边全是齐的）
		int max = maxScroll(h, list.size());
		if (max > 0) {
			int barX = x + w - 1;
			int trackY = y + HEAD_H;
			int trackH = h - BOTTOM_RESERVE - HEAD_H;
			dc.fill(barX, trackY, barX + 1, trackY + trackH, HubTheme.TRACK);
			int knobH = Math.max(8, trackH * per / list.size());
			int knobY = trackY + (trackH - knobH) * scroll / max;
			dc.fill(barX, knobY, barX + 1, knobY + knobH, HubTheme.LINE);
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
