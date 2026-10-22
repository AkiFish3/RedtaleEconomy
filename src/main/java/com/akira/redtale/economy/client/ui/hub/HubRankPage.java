package com.akira.redtale.economy.client.ui.hub;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;

/**
 * Hub 内的排行榜页（U9b 数据已接入）。
 *
 * <p>数据来自 {@code S2C_RANK}（服务端每 60 秒算一次缓存，只发前 10 + 自己那一行）。
 * 没有数据时显示占位，不再是"尚未接入"。
 */
public final class HubRankPage {

	public static final String[] BOARDS = {"财富", "收入", "悬赏", "声望", "战令"};

	/** 榜单行高 / 列表距内容区顶的偏移（下拉 18 + 勾选行 18 + 6）。 */
	private static final int ROW_H = 14;
	private static final int LIST_TOP = 42;

	/**
	 * 「只看 30 天内活跃」的整行命中宽度（勾选框 + 文案）。
	 *
	 * <p>★原来写 {@code x < x + 10 + 60 = x+70}★：文案从 x+14 画到 x+88（中文字宽 9），
	 * 末尾约 18px 看得见点不动 —— 与「按钮禁用态 / 溢出截断」同族：画出来的范围必须等于能点的范围。
	 */
	private static final int ACTIVE_HIT_W = 96;

	/**
	 * 滚动行号。
	 *
	 * <p>★服务端发的是前 10 名★，427×240 内容区从 y+42 到 y+h-46 只有 72px ⇒ 5 行，
	 * 第 6~10 名原来直接 {@code break} 掉、既看不见也翻不到（与市场/悬赏同族）。
	 */
	private static int scroll;


	/** 通知 HubScreen：本帧需要在下拉位置最后再画一遍（避免被底栏遮住）。 */
	private static boolean pendingDropdown;

	/** 「同步」还有多少秒冷却（0 = 可点）。由 HubScreen 每帧写入，只影响绘制。 */
	private static int syncLeft;

	private HubRankPage() {
	}

	/** HubScreen 在 render 前调用（§43.6：60 秒冷却，冷却中置灰并显示剩余秒数）。 */
	public static void setSyncCooldownSeconds(int seconds) {
		syncLeft = Math.max(0, seconds);
	}

	public static boolean needsDropdownOverlay() {
		return pendingDropdown;
	}

	public static void resetScroll() {
		scroll = 0;
	}

	/** 一屏能放下几行（与 render 的 listBottom 同一口径）。 */
	static int rowsVisible(int h) {
		int usable = (h - 46) - LIST_TOP;
		if (usable < ROW_H) return 0;
		return 1 + (usable - ROW_H) / ROW_H;
	}

	static int maxScroll(int h, int size) {
		return Math.max(0, size - rowsVisible(h));
	}

	public static void scrollByRows(int delta, int h) {
		var snap = com.akira.redtale.economy.client.rank.RankClientNetworking.LAST;
		int size = snap == null ? 0 : snap.top().size();
		scroll = Math.max(0, Math.min(maxScroll(h, size), scroll + delta));
	}

	/** 仅探针：当前滚动行。 */
	public static int scrollRow() {
		return scroll;
	}

	/** 在所有内容与底栏画完之后调用，保证下拉在最上层。 */
	public static void drawDropdown(DrawContext dc, TextRenderer tr, int x, int y, int board,
			int panelTop, int panelBottom, double mouseX, double mouseY) {
		int dw = 86;
		int count = BOARDS.length;
		int listH = count * 16;
		int iy = dropdownTop(y, panelTop, panelBottom, listH);
		dc.fill(x, iy, x + dw, iy + listH, HubTheme.INNER);
		dc.fill(x, iy, x + dw, iy + 1, HubTheme.LINE);
		dc.fill(x, iy + listH - 1, x + dw, iy + listH, HubTheme.LINE);
		for (int i = 0; i < count; i++) {
			boolean on = i == board;
			boolean hv = mouseX >= x && mouseX < x + dw && mouseY >= iy && mouseY < iy + 16;
			dc.fill(x, iy, x + dw, iy + 16, on ? HubTheme.SELECT : (hv ? HubTheme.ROW_HOVER : HubTheme.INNER));
			dc.drawText(tr, Text.literal(BOARDS[i]), x + 7, iy + 4, on ? HubTheme.PRIMARY_SOFT_TEXT : HubTheme.TEXT, false);
			iy += 16;
		}
	}

	/**
	 * 下拉列表顶边（绘制与命中共用这一份几何）。
	 *
	 * <p>★原来两处各写一遍 {@code Math.max(y - listH, y - listH)}★：同一个参数写两次等于没夹 ⇒
	 * 下面放不下时下拉被画到内容区顶边之上（顶栏/侧栏甚至窗口外），命中区跟着跑到面板外
	 * （「画在面板外还能点」同族）。
	 *
	 * <p>三条规则：① 下面放得下就向下；② 否则向上翻，但整块必须落在内容区内**且不压住下拉框本身**
	 * （压住的话首项与「点开/关闭」的命中区重叠，第一项永远选不到）；③ 上下都不够（窗口极窄）时
	 * 仍然向下 —— 溢出底栏总比画到面板外好，且绘制与命中同源，不会错位。
	 */
	static int dropdownTop(int y, int panelTop, int panelBottom, int listH) {
		int down = y + 20;
		if (down + listH <= panelBottom) return down;
		int up = y - listH;
		if (up >= panelTop) return up;
		return down;
	}

	public enum Action {
		NONE, ACTIVE_TOGGLE, SYNC
	}

	/** 渲染；{@code activeOnly} 为「只看活跃」开关状态。 */
	public static void render(DrawContext dc, TextRenderer tr, int x, int y, int w, int h,
			int board, boolean activeOnly, boolean dropdownOpen, double mouseX, double mouseY) {
		// 榜单下拉（默认财富）
		int dw = 86;
		boolean hovDd = mouseX >= x && mouseX < x + dw && mouseY >= y && mouseY < y + 18;
		dc.fill(x, y, x + dw, y + 18, hovDd ? HubTheme.ROW_HOVER : HubTheme.CARD);
		dc.fill(x, y, x + dw, y + 1, HubTheme.LINE);
		dc.drawText(tr, Text.literal(BOARDS[board]), x + 7, y + 5, HubTheme.PRIMARY_SOFT_TEXT, false);
		dc.drawText(tr, Text.literal("▾"), x + dw - 12, y + 5, HubTheme.MUTED, false);
		// 下拉列表不在这里画（会被底栏/其他元素盖住），交给 drawDropdown 最后叠加
		pendingDropdown = dropdownOpen;

		// 只看活跃 + 同步
		int sy = y + 24;
		// ★勾选态要有"被填上"的视觉★：10px 的框靠"金框 vs 空框"在实机截图里几乎分不出来
		// （2026-10-21 看 u6-20/u6-21 两张对比才确认），改成「边框 + 内底 + 选中加金心」。
		dc.fill(x, sy, x + 10, sy + 10, activeOnly ? HubTheme.GOLD : HubTheme.LINE);
		dc.fill(x + 1, sy + 1, x + 9, sy + 9, HubTheme.INNER);
		if (activeOnly) dc.fill(x + 3, sy + 3, x + 7, sy + 7, HubTheme.GOLD);
		dc.drawText(tr, Text.literal("只看 30 天内活跃"), x + 14, sy, HubTheme.MUTED, false);
		String syncLabel = syncLeft > 0 ? syncLeft + "s" : "同步";
		dc.drawText(tr, Text.literal(syncLabel), x + w - tr.getWidth(syncLabel), sy,
				syncLeft > 0 ? HubTheme.DISABLED_TEXT : HubTheme.MUTED, false);

		// 列表（U9b：财富榜前 10，服务端缓存 60 秒）
		// LIST_TOP 必须等于 sy+18（=y+42）：滚动上限按它算，改布局忘改常量就会多算/少算一行
		int ly = y + LIST_TOP;
		// ★底部要留 18px 给「共 N 人上榜」★：原来用 min(rowY+2, y+h-34) 兜底，
		// 行数多时这行正好落在「自己那行」的色块下面 ⇒ 被压住看不见（2026-10-21 截图实证）。
		int listBottom = y + h - 46;
		var snap = com.akira.redtale.economy.client.rank.RankClientNetworking.LAST;
		var rows = snap == null ? java.util.List.<com.akira.redtale.economy.net.RankProtocol.Row>of()
				: snap.top();
		if (board != 0) {
			// ★其余四榜只有标签、没有数据源★（§43.4 在排期）⇒ 必须说清楚，
			// 否则玩家点「收入」看到的是财富榜的钱数，界面在说谎。
			dc.fill(x, ly, x + w, ly + 44, HubTheme.INNER);
			dc.drawText(tr, Text.literal("「" + BOARDS[board] + "」榜尚未接入数据"),
					x + 8, ly + 8, HubTheme.MUTED, false);
			dc.drawText(tr, Text.literal("当前只有财富榜有数据源"), x + 8, ly + 22, HubTheme.MUTED, false);
			return;
		}
		if (rows.isEmpty()) {
			dc.fill(x, ly, x + w, ly + 44, HubTheme.INNER);
			dc.drawText(tr, Text.literal("暂无榜单数据"), x + 8, ly + 8, HubTheme.MUTED, false);
			dc.drawText(tr, Text.literal("服务端每 60 秒统计一次，稍后再看"), x + 8, ly + 22, HubTheme.MUTED, false);
		} else {
			int per = rowsVisible(h);
			int max = maxScroll(h, rows.size());
			scroll = Math.max(0, Math.min(max, scroll));
			for (int row = 0; row < per; row++) {
				int i = scroll + row;
				if (i >= rows.size()) break;
				var r = rows.get(i);
				int rowY = ly + row * ROW_H;
				boolean me = snap.self() != null && r.name().equals(snap.self().name());
				if (me) dc.fill(x, rowY, x + w, rowY + 14, HubTheme.SELECT);
				dc.drawText(tr, Text.literal(r.rank() + "."), x + 4, rowY + 3,
						r.rank() <= 3 ? HubTheme.GOLD : HubTheme.MUTED, false);
				dc.drawText(tr, Text.literal(r.name()), x + 22, rowY + 3, HubTheme.TEXT, false);
				// T55：金额图标化（右对齐：先量宽再落笔）
				int mw = HubMoney.width(tr, r.balance());
				HubMoney.draw(dc, tr, x + w - 4 - mw, rowY + 3, r.balance(), mw);
			}
			// 溢出时画 1px 滚动条（前 10 名里第 6 名以后原来完全不可达）
			if (max > 0) {
				int barX = x + w - 1;
				int trackY = ly;
				int trackH = Math.max(1, listBottom - ly);
				dc.fill(barX, trackY, barX + 1, trackY + trackH, HubTheme.TRACK);
				int knobH = Math.max(8, trackH * Math.max(1, per) / rows.size());
				int knobY = trackY + (trackH - knobH) * scroll / max;
				dc.fill(barX, knobY, barX + 1, knobY + knobH, HubTheme.LINE);
			}
			// 总人数常驻（开关切换时这行就是"行数确实变了"的读数，见 _VERIFY_ECON.md → U9）
			String more = "共 " + snap.total() + " 人上榜";
			dc.drawText(tr, Text.literal(more), x + 4, listBottom + 4, HubTheme.MUTED, false);
		}

		// 自己那一行（固定底部；rank=0 表示未上榜，但金额照显）
		int selfY = y + h - 26;
		dc.fill(x, selfY, x + w, selfY + 20, HubTheme.SELECT);
		dc.fill(x, selfY, x + w, selfY + 1, HubTheme.GOLD);
		var me = snap == null ? null : snap.self();
		String who = me == null ? "你 · 暂无数据"
				: (me.rank() > 0 ? "你 · 第 " + me.rank() + " 名" : "你 · 暂未上榜");
		dc.drawText(tr, Text.literal(who), x + 6, selfY + 6, HubTheme.PRIMARY_SOFT_TEXT, false);
		if (me == null) {
			dc.drawText(tr, Text.literal("—"), x + w - 10, selfY + 6, HubTheme.GOLD, false);
		} else {
			int mw = HubMoney.width(tr, me.balance());
			HubMoney.draw(dc, tr, x + w - 4 - mw, selfY + 6, me.balance(), mw);
		}
	}

	/** 下拉命中：返回 0..n-1 表示选中该榜，-2 = 点开/关闭，-1 = 无。 */
	public static int hitDropdown(double mx, double my, int x, int y, boolean open, int panelTop,
			int panelBottom) {
		int dw = 86;
		if (mx >= x && mx < x + dw && my >= y && my < y + 18) return -2;
		if (open) {
			int listH = BOARDS.length * 16;
			int iy = dropdownTop(y, panelTop, panelBottom, listH);
			for (int i = 0; i < BOARDS.length; i++) {
				if (mx >= x && mx < x + dw && my >= iy && my < iy + 16) return i;
				iy += 16;
			}
		}
		return -1;
	}

	public static Action click(double mx, double my, int x, int y, int w, int h, int board) {
		int sy = y + 24;
		if (mx >= x && mx < x + ACTIVE_HIT_W && my >= sy - 2 && my < sy + 12) return Action.ACTIVE_TOGGLE;
		if (mx >= x + w - 20 && my >= sy - 2 && my < sy + 12) return Action.SYNC;
		return Action.NONE;
	}

}
