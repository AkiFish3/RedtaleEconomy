package com.akira.redtale.economy.client.ui.hub;

import com.akira.redtale.economy.net.TaskProtocol;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.List;

/**
 * T56：Hub 的「每日任务」页（列表 + 进度 + 领取）。
 *
 * <p>数据只来自服务端的快照（{@link TasksHubClientNetworking#LAST}）：进度是服务端数的背包/记账，
 * 客户端不做任何本地推算 —— 否则界面会显示「可领取」而服务端拒绝。
 *
 * <p>领取/刷新都发命令（{@code /tasks claim|refresh}）：校验、扣物品、发奖励、写流水只有服务端一份代码。
 * 命令回显由服务端按 Hub 模式转 actionbar（见 {@code TaskCommands.say}），不会进聊天栏。
 */
public final class HubTasksPage {

	private static final int ROW_H = 34;
	private static final int GAP = 4;
	private static int scrollRow;

	private HubTasksPage() {
	}

	public enum Action {
		NONE,
		/** 事件型（detect）：服务端已记账，直接领。 */
		CLAIM,
		/** 缴纳型（submit）：先把物品交给服务端（扣背包/仓库），再发奖励。 */
		SUBMIT,
		REFRESH
	}

	/** 手动刷新提示：距下次可刷新不拦（服务端有次数与券），这里只做按钮态。 */
	public static void render(DrawContext dc, TextRenderer tr, int x, int y, int w, int h,
			double mouseX, double mouseY) {
		TaskProtocol.Snapshot snap = TasksHubClientNetworking.LAST;

		// 顶部：日期 + 刷新剩余次数 + 刷新按钮
		String head = snap.date().isEmpty() ? "每日任务" : ("每日任务 · " + snap.date());
		dc.drawText(tr, Text.literal(head), x, y, HubTheme.TITLE, false);
		String left = "可刷新 " + snap.refreshesLeft() + " 次";
		dc.drawText(tr, Text.literal(left), x + w - 62 - tr.getWidth(left), y + 1,
				HubTheme.MUTED, false);
		drawMini(dc, tr, x + w - 56, y - 2, 56, 14, "刷新", mouseX, mouseY);
		dc.fill(x, y + 14, x + w, y + 15, HubTheme.OUTLINE);

		List<TaskProtocol.Task> tasks = snap.tasks();
		int top = y + 19;
		int bottom = y + h;
		if (tasks.isEmpty()) {
			dc.drawText(tr, Text.literal("今日没有任务"), x + 2, top + 6, HubTheme.MUTED, false);
			dc.drawText(tr, Text.literal("管理员：检查 tasks/definitions 下的定义与 pools"),
					x + 2, top + 20, HubTheme.HINT, false);
			return;
		}

		int fit = Math.max(1, (bottom - top + GAP) / (ROW_H + GAP));
		int maxRow = Math.max(0, tasks.size() - fit);
		scrollRow = Math.max(0, Math.min(scrollRow, maxRow));
		dc.enableScissor(x, top, x + w, bottom);
		for (int i = scrollRow; i < tasks.size(); i++) {
			int ry = top + (i - scrollRow) * (ROW_H + GAP);
			if (ry + ROW_H > bottom) break;
			drawRow(dc, tr, x, ry, w, tasks.get(i), mouseX, mouseY);
		}
		dc.disableScissor();
		if (maxRow > 0) {
			int trackH = bottom - top;
			int barH = Math.max(10, trackH * fit / tasks.size());
			int barY = top + (trackH - barH) * scrollRow / maxRow;
			dc.fill(x + w - 2, top, x + w - 1, bottom, HubTheme.LINE);
			dc.fill(x + w - 2, barY, x + w - 1, barY + barH, HubTheme.MUTED);
		}
	}

	private static void drawRow(DrawContext dc, TextRenderer tr, int x, int ry, int w,
			TaskProtocol.Task t, double mouseX, double mouseY) {
		boolean claimed = t.claimed();
		boolean done = isDone(t);
		dc.fill(x, ry, x + w, ry + ROW_H, claimed ? HubTheme.CARD_OK : HubTheme.CARD);
		dc.fill(x, ry, x + w, ry + 1, done && !claimed ? HubTheme.OK : HubTheme.LINE);

		// 左侧：首个物品图标（走位图，和商店/仓储一致）
		String firstId = t.items().isEmpty() ? "" : t.items().get(0).id();
		ItemStack stack = stackOf(firstId);
		if (!stack.isEmpty()) {
			dc.drawItem(stack, x + 4, ry + 7);
		} else {
			dc.fill(x + 4, ry + 7, x + 20, ry + 23, HubTheme.SLOT);
		}

		// 标题 + 进度行
		String title = t.label() == null || t.label().isBlank() ? ("#" + t.slot()) : t.label();
		dc.drawText(tr, Text.literal(trim(tr, title, w - 96)), x + 26, ry + 4,
				claimed ? HubTheme.HINT : HubTheme.TEXT, false);
		String prog = progressText(t);
		dc.drawText(tr, Text.literal(trim(tr, prog, w - 96)), x + 26, ry + 15,
				done ? HubTheme.OK : HubTheme.MUTED, false);

		// 进度条（多物品取最低完成度：任何一项没满都不算完成）
		int barX = x + 26;
		int barW = Math.max(30, w - 96 - 26);
		int barY = ry + 26;
		dc.fill(barX, barY, barX + barW, barY + 4, HubTheme.TRACK);
		double k = ratio(t);
		if (k > 0) {
			dc.fill(barX, barY, barX + (int) (barW * k), barY + 4,
					claimed ? HubTheme.HINT : (done ? HubTheme.OK : HubTheme.GOLD));
		}

		// 右侧：奖励 + 按钮（★缴纳型必须走 submit★：claim 只认 detect，按错了服务端会拒）
		String btn = claimed ? "已领取"
				: (done ? (isSubmit(t) ? "缴纳" : "领取") : "进行中");
		int bw = 38;
		int bx = x + w - bw - 4;
		boolean canClick = !claimed && done;
		drawMini(dc, tr, bx, ry + 10, bw, 14, btn, canClick, mouseX, mouseY);
		long reward = t.reward();
		int rw = HubMoney.width(tr, reward);
		HubMoney.draw(dc, tr, Math.max(x + 26, bx - rw - 6), ry + 4, reward, bx - x - 30);
	}

	private static boolean isSubmit(TaskProtocol.Task t) {
		return "submit".equals(t.mode());
	}

	/** 进度文案：{@code 石头 3/10 · 缴纳}。 */
	private static String progressText(TaskProtocol.Task t) {
		String mode = switch (t.mode() == null ? "" : t.mode()) {
			case "detect" -> "自动计数";
			case "submit" -> "缴纳";
			default -> t.mode();
		};
		if (t.items().isEmpty()) return mode;
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < t.items().size(); i++) {
			if (i > 0) sb.append("、");
			TaskProtocol.Item it = t.items().get(i);
			sb.append(nameOf(it.id())).append(' ').append(Math.min(it.have(), it.need()))
					.append('/').append(it.need());
		}
		return sb + " · " + mode;
	}

	private static boolean isDone(TaskProtocol.Task t) {
		if (t.items().isEmpty()) return false;
		for (TaskProtocol.Item it : t.items()) {
			if (it.have() < it.need()) return false;
		}
		return true;
	}

	/** 完成度（0..1），多物品取最小项。 */
	private static double ratio(TaskProtocol.Task t) {
		if (t.items().isEmpty()) return 0;
		double k = 1;
		for (TaskProtocol.Item it : t.items()) {
			if (it.need() <= 0) continue;
			k = Math.min(k, Math.min(1.0, (double) it.have() / it.need()));
		}
		return k;
	}

	private static void drawMini(DrawContext dc, TextRenderer tr, int x, int y, int w, int h,
			String label, double mouseX, double mouseY) {
		drawMini(dc, tr, x, y, w, h, label, true, mouseX, mouseY);
	}

	private static void drawMini(DrawContext dc, TextRenderer tr, int x, int y, int w, int h,
			String label, boolean enabled, double mouseX, double mouseY) {
		boolean hover = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
		int bg = enabled ? (hover ? HubTheme.PRIMARY_SOFT_HOVER : HubTheme.PRIMARY_SOFT)
				: HubTheme.DISABLED_BG;
		dc.fill(x, y, x + w, y + h, bg);
		dc.fill(x, y, x + w, y + 1, enabled ? HubTheme.GOLD : HubTheme.LINE);
		dc.fill(x, y + h - 1, x + w, y + h, enabled ? HubTheme.GOLD : HubTheme.LINE);
		dc.drawText(tr, Text.literal(label), x + (w - tr.getWidth(label)) / 2, y + 3,
				enabled ? HubTheme.PRIMARY_SOFT_TEXT : HubTheme.DISABLED_TEXT, false);
	}

	/**
	 * 点击：行内「领取」→ {@link Action#CLAIM}（slot 经 {@code outSlot} 带回）；右上角「刷新」→ REFRESH。
	 */
	public static Action click(double mx, double my, int x, int y, int w, int h, int[] outSlot) {
		TaskProtocol.Snapshot snap = TasksHubClientNetworking.LAST;
		if (my >= y - 2 && my < y + 14 && mx >= x + w - 56 && mx <= x + w) return Action.REFRESH;
		List<TaskProtocol.Task> tasks = snap.tasks();
		if (tasks.isEmpty()) return Action.NONE;
		int top = y + 19;
		int bottom = y + h;
		if (my < top || my >= bottom) return Action.NONE;
		int row = (int) ((my - top) / (ROW_H + GAP));
		int idx = row + scrollRow;
		if (idx < 0 || idx >= tasks.size()) return Action.NONE;
		// 行间空隙不算命中
		if (my - (top + row * (ROW_H + GAP)) >= ROW_H) return Action.NONE;
		TaskProtocol.Task t = tasks.get(idx);
		int bw = 38;
		int bx = x + w - bw - 4;
		if (mx >= bx && mx < bx + bw) {
			outSlot[0] = t.slot();
			return isSubmit(t) ? Action.SUBMIT : Action.CLAIM;
		}
		return Action.NONE;
	}

	public static void scrollByRows(int rows) {
		scrollRow = Math.max(0, scrollRow + rows);
	}

	public static void resetScroll() {
		scrollRow = 0;
	}

	private static ItemStack stackOf(String itemId) {
		try {
			Identifier id = new Identifier(itemId);
			Item item = Registries.ITEM.get(id);
			if (item == null || item == net.minecraft.item.Items.AIR) return ItemStack.EMPTY;
			return new ItemStack(item);
		} catch (Exception ex) {
			return ItemStack.EMPTY;
		}
	}

	private static String nameOf(String itemId) {
		ItemStack st = stackOf(itemId);
		return st.isEmpty() ? itemId : st.getName().getString();
	}

	private static String trim(TextRenderer tr, String s, int maxW) {
		if (tr.getWidth(s) <= maxW) return s;
		String out = s;
		while (out.length() > 1 && tr.getWidth(out + "…") > maxW) {
			out = out.substring(0, out.length() - 1);
		}
		return out + "…";
	}
}
