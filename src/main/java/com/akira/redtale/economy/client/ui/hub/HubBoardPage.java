package com.akira.redtale.economy.client.ui.hub;

import com.akira.redtale.economy.net.BoardProtocol;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.List;

/**
 * Hub 内的委托板页（T12）。
 *
 * <p>数据来自 {@link BoardClientNetworking#LAST}（服务端 {@code S2C_BOARD} 下发）。
 * 每条一张卡：物品 × 数量 / 发布者 + 剩余时间 / 赏金 / 一个动作按钮。
 *
 * <p>动作按钮的三种语义与命令完全对应，界面不做任何结算：
 * <ul>
 *   <li>别人的 OPEN → 接单（{@code /board accept <id>}）</li>
 *   <li>我接的 ACCEPTED → 交付（{@code /board deliver <id>}）</li>
 *   <li>我发的 OPEN → 撤销（{@code /board cancel <id>}）</li>
 * </ul>
 *
 * <p>布局常量只在这里定义一次，render 与 click 共用 —— 历史上「看着能点其实点不到」
 * 全是两边各写一套坐标导致的。
 */
public final class HubBoardPage {

	private static final int HEAD_H = 16;
	private static final int ROW_H = 28;
	private static final int BTN_W = 38;
	private static final int BTN_H = 14;

	/** 滚动行号（页面重新打开时归零）。 */
	private static int scroll;

	private HubBoardPage() {
	}

	public enum Action {
		NONE, REFRESH, ACCEPT, DELIVER, CANCEL
	}

	public static void resetScroll() {
		scroll = 0;
	}

	public static int devScrollRow() {
		return scroll;
	}

	private static int rowsVisible(int h) {
		return Math.max(1, (h - HEAD_H - 2) / ROW_H);
	}

	private static int maxScroll(List<?> list, int h) {
		return Math.max(0, list.size() - rowsVisible(h));
	}

	public static void scrollByRows(int delta, int h) {
		scroll = Math.max(0, Math.min(maxScroll(BoardClientNetworking.LAST.entries(), h), scroll + delta));
	}

	// ---------------------------------------------------------------- 渲染

	public static void render(DrawContext dc, TextRenderer tr, int x, int y, int w, int h,
			double mouseX, double mouseY) {
		List<BoardProtocol.Entry> list = BoardClientNetworking.LAST.entries();

		dc.drawText(tr, Text.literal("委托板"), x, y, HubTheme.TITLE, false);
		dc.drawText(tr, Text.literal("刷新"), x + w - tr.getWidth("刷新"),
				y, HubTheme.MUTED, false);

		int top = y + HEAD_H;
		int limit = y + h;
		int rows = rowsVisible(h);
		scroll = Math.max(0, Math.min(maxScroll(list, h), scroll));

		if (list.isEmpty()) {
			dc.fill(x, top, x + w, top + 34, HubTheme.CARD);
			dc.drawText(tr, Text.literal("委托板现在是空的"), x + 8, top + 7, HubTheme.MUTED, false);
			dc.drawText(tr, Text.literal(trim(tr, "/board post <物品> <数量> <赏金> 发一单", w - 16)),
					x + 8, top + 20, HubTheme.HINT, false);
			return;
		}

		for (int i = 0; i < rows; i++) {
			int idx = scroll + i;
			if (idx >= list.size()) break;
			int ry = top + i * ROW_H;
			if (ry + ROW_H - 2 > limit) break;
			drawRow(dc, tr, x, ry, w, list.get(idx), mouseX, mouseY);
		}

		// 溢出时画 1px 滚动条（位置随 scroll 走，别只画个装饰）
		int max = maxScroll(list, h);
		if (max > 0) {
			int barX = x + w - 1;
			dc.fill(barX, top, barX + 1, limit, HubTheme.TRACK);
			int trackH = limit - top;
			int knobH = Math.max(8, trackH * rows / list.size());
			int knobY = top + (trackH - knobH) * scroll / max;
			dc.fill(barX, knobY, barX + 1, knobY + knobH, HubTheme.LINE);
		}
	}

	private static void drawRow(DrawContext dc, TextRenderer tr, int x, int ry, int w,
			BoardProtocol.Entry e, double mouseX, double mouseY) {
		boolean hover = mouseX >= x && mouseX < x + w && mouseY >= ry && mouseY < ry + ROW_H - 2;
		dc.fill(x, ry, x + w, ry + ROW_H - 2, hover ? HubTheme.HOVER : HubTheme.CARD);
		dc.fill(x, ry, x + w, ry + 1,
				e.minePoster() || e.mineAccepter() ? HubTheme.GOLD : HubTheme.LINE);

		ItemStack st = stackOf(e.itemId());
		if (!st.isEmpty()) {
			dc.drawItem(st, x + 3, ry + 5);
		} else {
			dc.fill(x + 3, ry + 5, x + 19, ry + 21, HubTheme.SLOT);
		}

		// 动作按钮（右侧，先算宽度给文字留位）
		Action act = actionFor(e);
		int bx = x + w - 4 - BTN_W;
		boolean can = act != Action.NONE;
		mini(dc, tr, bx, ry + 6, BTN_W, BTN_H, btnLabel(e), can, mouseX, mouseY,
				act == Action.CANCEL ? HubTheme.DANGER : HubTheme.GOLD);

		int textW = bx - (x + 23) - 4;
		String name = (st.isEmpty() ? e.itemId() : st.getName().getString()) + " ×" + e.count();
		dc.drawText(tr, Text.literal(trim(tr, name, textW)), x + 23, ry + 3, HubTheme.TEXT, false);

		String meta = e.posterName() + " · " + e.statusName()
				+ (e.open() ? " · 剩 " + e.remainMin() + " 分" : "")
				+ (e.accepted() && !e.accepterName().isEmpty() ? " · " + e.accepterName() : "");
		dc.drawText(tr, Text.literal(trim(tr, meta, textW)), x + 23, ry + 13, HubTheme.MUTED, false);

		String bounty = e.bounty() + " 铜";
		dc.drawText(tr, Text.literal(trim(tr, bounty, textW)), x + 23, ry + 22, HubTheme.GOLD, false);
	}

	private static String btnLabel(BoardProtocol.Entry e) {
		return switch (actionFor(e)) {
			case ACCEPT -> "接单";
			case DELIVER -> "交付";
			case CANCEL -> "撤销";
			default -> e.open() ? "已接" : (e.accepted() ? "进行中" : "已结束");
		};
	}

	/** 与 {@code BoardCommands} 的校验口径一致；不满足就不给可点状态。 */
	private static Action actionFor(BoardProtocol.Entry e) {
		if (e.open()) {
			if (e.minePoster()) return Action.CANCEL;
			return Action.ACCEPT;
		}
		if (e.accepted() && e.mineAccepter()) return Action.DELIVER;
		return Action.NONE;
	}

	private static ItemStack stackOf(String itemId) {
		try {
			Identifier id = Identifier.tryParse(itemId == null ? "" : itemId);
			if (id == null || !Registries.ITEM.containsId(id)) return ItemStack.EMPTY;
			return new ItemStack(Registries.ITEM.get(id));
		} catch (Exception e) {
			return ItemStack.EMPTY;
		}
	}

	// ---------------------------------------------------------------- 点击

	/** 命中检测；命中行通过 {@code outIndex} 带回下标。 */
	public static Action click(TextRenderer tr, double mx, double my, int x, int y, int w, int h,
			int[] outIndex) {
		outIndex[0] = -1;
		if (my >= y && my < y + HEAD_H) {
			if (mx >= x + w - tr.getWidth("刷新") - 4) return Action.REFRESH;
			return Action.NONE;
		}
		int top = y + HEAD_H;
		List<BoardProtocol.Entry> list = BoardClientNetworking.LAST.entries();
		int rows = rowsVisible(h);
		for (int i = 0; i < rows; i++) {
			int idx = scroll + i;
			if (idx >= list.size()) break;
			int ry = top + i * ROW_H;
			if (ry + ROW_H - 2 > y + h) break;
			if (my < ry || my >= ry + ROW_H - 2 || mx < x || mx >= x + w) continue;
			outIndex[0] = idx;
			int bx = x + w - 4 - BTN_W;
			if (mx >= bx && mx < bx + BTN_W) return actionFor(list.get(idx));
			return Action.NONE;
		}
		return Action.NONE;
	}

	// ---------------------------------------------------------------- 工具

	private static void mini(DrawContext dc, TextRenderer tr, int x, int y, int w, int h, String label,
			boolean enabled, double mouseX, double mouseY, int accent) {
		boolean hover = enabled && mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
		dc.fill(x, y, x + w, y + h, enabled
				? (hover ? HubTheme.PRIMARY_SOFT_HOVER : HubTheme.PRIMARY_SOFT)
				: HubTheme.DISABLED_BG);
		dc.fill(x, y, x + w, y + 1, enabled ? accent : HubTheme.LINE);
		dc.fill(x, y + h - 1, x + w, y + h, enabled ? accent : HubTheme.LINE);
		String t = trim(tr, label, w - 4);
		dc.drawText(tr, Text.literal(t), x + (w - tr.getWidth(t)) / 2, y + (h - 8) / 2,
				enabled ? HubTheme.PRIMARY_SOFT_TEXT : HubTheme.DISABLED_TEXT, false);
	}

	private static String trim(TextRenderer tr, String s, int maxW) {
		if (s == null || maxW <= 0) return "";
		if (tr.getWidth(s) <= maxW) return s;
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < s.length(); i++) {
			if (tr.getWidth(sb.toString() + s.charAt(i) + "…") > maxW) break;
			sb.append(s.charAt(i));
		}
		return sb + "…";
	}
}
