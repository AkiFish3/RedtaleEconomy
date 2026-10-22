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
 * T56：Hub 的「任务」页（列表 + 进度 + 领取）；T06 加「每日 / 每周 / 活动」三个分栏。
 *
 * <p>数据只来自服务端的快照（{@link TasksHubClientNetworking#LAST}）：进度是服务端数的背包/记账，
 * 客户端不做任何本地推算 —— 否则界面会显示「可领取」而服务端拒绝。
 *
 * <p>分栏是纯展示过滤，不改数据：服务端一份快照里同时带 daily 与 weekly（见
 * {@code TaskNetworking.build}），每条任务自带 {@code period}。三条栏的归属互斥：
 * <ul>
 *   <li>每日 = period=daily 且不是事件型</li>
 *   <li>每周 = period=weekly 且不是事件型</li>
 *   <li>活动 = 事件型（kill/mine/travel，服务端记账，不分日/周）</li>
 * </ul>
 *
 * <p>领取/刷新都发命令（{@code /tasks claim|refresh}）：校验、扣物品、发奖励、写流水只有服务端一份代码。
 * 命令回显由服务端按 Hub 模式转 actionbar（见 {@code TaskCommands.say}），不会进聊天栏。
 */
public final class HubTasksPage {

	private static final int ROW_H = 34;
	private static final int GAP = 4;
	/** 分栏条高度（含 1px 分隔线）。 */
	private static final int TAB_H = 15;
	/** 分栏下方的信息行（日期 / 刷新次数），行高 12。 */
	private static final int INFO_H = 12;
	/**
	 * 内容区顶到分栏条的留白。
	 *
	 * <p>★Aki 2026-10-23★：原来分栏直接贴在内容区顶（y+0），下面紧接日期行，
	 * 视觉上「每日任务 · 日期」像被压在顶栏分隔线上。留 5px 呼吸区。
	 */
	private static final int TOP_PAD = 5;
	/** 分栏条 → 信息行的额外间距（分隔线下面再让 3px）。 */
	private static final int INFO_PAD = 4;

	/** 分栏条顶边（绘制与命中共用）。 */
	static int tabTop(int y) {
		return y + TOP_PAD;
	}

	/** 信息行文字顶边（分隔线在 {@link #tabTop} + TAB_H 处）。 */
	static int infoTop(int y) {
		return tabTop(y) + TAB_H + INFO_PAD;
	}

	/** 任务列表顶边。 */
	static int listTop(int y) {
		return tabTop(y) + TAB_H + INFO_H + 2;
	}

	private static final String[] TABS = {"每日", "每周", "活动"};

	public static final int TAB_DAILY = 0;
	public static final int TAB_WEEKLY = 1;
	public static final int TAB_EVENT = 2;

	private static int scrollRow;
	private static int tab = TAB_DAILY;

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
		List<TaskProtocol.Task> tasks = visible();

		// 第一行：分栏
		int tabsY = tabTop(y);
		int cx = x;
		for (int i = 0; i < TABS.length; i++) {
			int cw = chipW(i, tr);
			boolean on = i == tab;
			boolean hover = mouseX >= cx && mouseX < cx + cw && mouseY >= tabsY && mouseY < tabsY + 13;
			dc.fill(cx, tabsY, cx + cw, tabsY + 13, on ? HubTheme.PRIMARY_SOFT
					: (hover ? HubTheme.PRIMARY_SOFT_HOVER : HubTheme.CARD));
			dc.fill(cx, tabsY + 12, cx + cw, tabsY + 13, on ? HubTheme.GOLD : HubTheme.LINE);
			dc.drawText(tr, Text.literal(TABS[i]), cx + 7, tabsY + 3,
					on ? HubTheme.PRIMARY_SOFT_TEXT : HubTheme.MUTED, false);
			cx += cw + 3;
		}

		// 第二行：本栏的时间口径（日/周各一条）+ 刷新（只有日任务可刷）
		int infoY = infoTop(y);
		dc.drawText(tr, Text.literal(infoLine(snap)), x, infoY, HubTheme.MUTED, false);
		if (tab == TAB_DAILY) {
			String left = "可刷新 " + snap.refreshesLeft() + " 次";
			dc.drawText(tr, Text.literal(left), x + w - 62 - tr.getWidth(left), infoY,
					HubTheme.MUTED, false);
			drawMini(dc, tr, x + w - 56, infoY - 3, 56, 13, "刷新", mouseX, mouseY);
		}
		dc.fill(x, tabsY + TAB_H - 1, x + w, tabsY + TAB_H, HubTheme.OUTLINE);

		int top = listTop(y);
		int bottom = y + h;
		if (tasks.isEmpty()) {
			dc.drawText(tr, Text.literal(emptyText()), x + 2, top + 6, HubTheme.MUTED, false);
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

	private static String infoLine(TaskProtocol.Snapshot snap) {
		return switch (tab) {
			case TAB_WEEKLY -> "每周任务 · " + (snap.weekKey().isEmpty() ? "-" : snap.weekKey());
			case TAB_EVENT -> "活动任务 · 自动计数";
			default -> snap.date().isEmpty() ? "每日任务" : ("每日任务 · " + snap.date());
		};
	}

	private static String emptyText() {
		return switch (tab) {
			case TAB_WEEKLY -> "本周没有任务";
			case TAB_EVENT -> "没有进行中的活动任务";
			default -> "今日没有任务";
		};
	}

	/** 本栏可见的任务（纯过滤，顺序沿用快照）。 */
	public static List<TaskProtocol.Task> visible() {
		List<TaskProtocol.Task> out = new java.util.ArrayList<>();
		for (TaskProtocol.Task t : TasksHubClientNetworking.LAST.tasks()) {
			if (inTab(t)) out.add(t);
		}
		return out;
	}

	private static boolean inTab(TaskProtocol.Task t) {
		String period = t.period() == null || t.period().isBlank() ? "daily" : t.period();
		if (isEvent(t)) return tab == TAB_EVENT;
		return tab == (("weekly".equals(period)) ? TAB_WEEKLY : TAB_DAILY);
	}

	/** 分栏 chip 的左边（相对 x）与宽度：渲染与命中共用，禁止两处各算一遍。 */
	private static int chipRelX(int i, TextRenderer tr) {
		int v = 0;
		for (int k = 0; k < i; k++) v += chipW(k, tr) + 3;
		return v;
	}

	private static int chipW(int i, TextRenderer tr) {
		return tr.getWidth(TABS[i]) + 14;
	}

	/** 夹具用：直接选栏（不手算 chip 坐标）。 */
	public static void selectTab(int index) {
		tab = Math.max(0, Math.min(TABS.length - 1, index));
		scrollRow = 0;
	}

	public static int tab() {
		return tab;
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
		drawMini(dc, tr, bx, ry + 10, bw, 14, btn, canAct(t), mouseX, mouseY);
		long reward = t.reward();
		int rw = HubMoney.width(tr, reward);
		HubMoney.draw(dc, tr, Math.max(x + 26, bx - rw - 6), ry + 4, reward, bx - x - 30);
	}

	private static boolean isSubmit(TaskProtocol.Task t) {
		return "submit".equals(t.mode());
	}

	/** 事件型（kill/mine/travel）：服务端记账，归「活动」栏。 */
	private static boolean isEvent(TaskProtocol.Task t) {
		return "detect".equals(t.mode());
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
	 * 点击：分栏 chip → 切栏；右上角「刷新」→ REFRESH（仅每日栏）；
	 * 行内按钮 → CLAIM/SUBMIT（slot 经 {@code outSlot} 带回）。
	 */
	public static Action click(double mx, double my, int x, int y, int w, int h, int[] outSlot) {
		// 分栏（第一行，高 13）。★与渲染共用 tabTop/infoTop/listTop★：
		// 以前绘制写 y、命中写 y+TAB_H-1 两套数字，任何一侧调间距都会让按钮「画在这、点在那」。
		int tabsY = tabTop(y);
		if (my >= tabsY && my < tabsY + 13) {
			net.minecraft.client.MinecraftClient mc = net.minecraft.client.MinecraftClient.getInstance();
			if (mc != null && mc.textRenderer != null) {
				for (int i = 0; i < TABS.length; i++) {
					int cx = x + chipRelX(i, mc.textRenderer);
					if (mx >= cx && mx < cx + chipW(i, mc.textRenderer)) {
						selectTab(i);
						return Action.NONE;
					}
				}
			}
		}

		// 刷新：命中区 = 绘制区（infoTop-3 起，高 13）
		int infoY = infoTop(y);
		if (tab == TAB_DAILY && my >= infoY - 3 && my < infoY - 3 + 13
				&& mx >= x + w - 56 && mx <= x + w) return Action.REFRESH;

		List<TaskProtocol.Task> tasks = visible();
		if (tasks.isEmpty()) return Action.NONE;
		int top = listTop(y);
		int bottom = y + h;
		if (my < top || my >= bottom) return Action.NONE;
		int row = (int) ((my - top) / (ROW_H + GAP));
		int idx = row + scrollRow;
		if (idx < 0 || idx >= tasks.size()) return Action.NONE;
		// 行间空隙不算命中
		if (my - (top + row * (ROW_H + GAP)) >= ROW_H) return Action.NONE;
		TaskProtocol.Task t = tasks.get(idx);
		// ★按钮命中必须与渲染同几何★（bx / ry+10 / 38×14）：原来只判 x 区间，
		// 点行内任意高度都会领奖；且不判 canAct ⇒ 点「进行中 / 已领取」照样发命令，
		// 服务端只能回一条拒绝消息（阶段 2 的误点来源）。
		int bw = 38;
		int bx = x + w - bw - 4;
		int by = top + row * (ROW_H + GAP) + 10;
		if (mx >= bx && mx < bx + bw && my >= by && my < by + 14 && canAct(t)) {
			outSlot[0] = t.slot();
			return isSubmit(t) ? Action.SUBMIT : Action.CLAIM;
		}
		return Action.NONE;
	}

	/** 按钮是否可按：未领取 + 已完成（渲染与命中同一判据）。 */
	private static boolean canAct(TaskProtocol.Task t) {
		return !t.claimed() && isDone(t);
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
