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

	/** 在所有内容与底栏画完之后调用，保证下拉在最上层。 */
	public static void drawDropdown(DrawContext dc, TextRenderer tr, int x, int y, int board,
			int panelBottom, double mouseX, double mouseY) {
		int dw = 86;
		int count = BOARDS.length;
		int listH = count * 16;
		// 下面放不下就往上翻
		int iy = (y + 20 + listH <= panelBottom) ? y + 20 : Math.max(y - listH, y - listH);
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
		int ly = sy + 18;
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
			int rowY = ly;
			for (var r : rows) {
				if (rowY + 14 > listBottom) break;
				boolean me = snap.self() != null && r.name().equals(snap.self().name());
				if (me) dc.fill(x, rowY, x + w, rowY + 14, HubTheme.SELECT);
				dc.drawText(tr, Text.literal(r.rank() + "."), x + 4, rowY + 3,
						r.rank() <= 3 ? HubTheme.GOLD : HubTheme.MUTED, false);
				dc.drawText(tr, Text.literal(r.name()), x + 22, rowY + 3, HubTheme.TEXT, false);
				// T55：金额图标化（右对齐：先量宽再落笔）
				int mw = HubMoney.width(tr, r.balance());
				HubMoney.draw(dc, tr, x + w - 4 - mw, rowY + 3, r.balance(), mw);
				rowY += 14;
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
	public static int hitDropdown(double mx, double my, int x, int y, boolean open, int panelBottom) {
		int dw = 86;
		if (mx >= x && mx < x + dw && my >= y && my < y + 18) return -2;
		if (open) {
			int listH = BOARDS.length * 16;
			int iy = (y + 20 + listH <= panelBottom) ? y + 20 : Math.max(y - listH, y - listH);
			for (int i = 0; i < BOARDS.length; i++) {
				if (mx >= x && mx < x + dw && my >= iy && my < iy + 16) return i;
				iy += 16;
			}
		}
		return -1;
	}

	public static Action click(double mx, double my, int x, int y, int w, int h, int board) {
		int sy = y + 24;
		if (mx >= x && mx < x + 10 + 60 && my >= sy - 2 && my < sy + 12) return Action.ACTIVE_TOGGLE;
		if (mx >= x + w - 20 && my >= sy - 2 && my < sy + 12) return Action.SYNC;
		return Action.NONE;
	}

}
