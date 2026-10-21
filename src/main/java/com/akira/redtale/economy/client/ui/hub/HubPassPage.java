package com.akira.redtale.economy.client.ui.hub;

import com.akira.redtale.economy.client.pass.PassClientNetworking;
import com.akira.redtale.economy.net.PassProtocol;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;

import java.util.List;

/**
 * Hub 内的战令页（U4/R20）：上下双轨 + 滚轮 / 滚动条拖拽 + 切换动画（T61）。
 *
 * <p>数据来自 {@link PassClientNetworking#LAST}（服务端 `/pass` 下发）。
 * 两轨共用同一个横向偏移，等级始终对齐。
 *
 * <p>★滚动只有一份真相★：{@code scroll}（int，宿主持有）是目标值；
 * {@code smooth} 是渲染用的动画值，逐帧逼近目标。命中判定**永远用目标值**，
 * 不用动画值 —— 否则「动画还在走的时候点一下」会点错格子。
 *
 * <p>★纵向几何只有一份真相★：{@link Layout}。以前渲染写 {@code row1 = y+44}、
 * 命中写 {@code row1 = y+34}（差 10px），窗口态下点轨道永远偏一格；而且格子高固定 52、
 * 滚动条钉在内容区最底部，427×240 时付费轨被面板底边裁掉、滚动条正好压在格子上。
 * 现在整块按可用高度算：格子高在 30~52 之间自适应，滚动条紧贴轨底且不越过内容区。
 */
public final class HubPassPage {

	private static final int CELL_W = 46;
	private static final int CELL_H = 52;
	private static final int GAP = 4;

	/** 滚动条厚度与离轨底距离。 */
	private static final int BAR_H = 4;
	private static final int BAR_GAP = 5;

	/** 标题行 + 经验条（含右侧经验文字）占用的高度。 */
	private static final int TOP_H = 26;
	/** 轨道标签（免费/付费）高度。 */
	private static final int LABEL_H = 10;
	/** 经验条右侧留给「0 / 1000 经验」的宽度。 */
	private static final int XP_TEXT_W = 64;

	/**
	 * 动画用：当前视觉偏移（小数）。
	 *
	 * <p>★只在 render 里推进★：render 每帧一次就够平滑；不要挂 tick 事件（切页后没人推进就是死的）。
	 */
	private static float smooth;
	/** 上次渲染时的目标值 / 可视格数；变化时直接吸附，不做补间。 */
	private static int smoothTarget = -1;
	private static int smoothVisible = -1;

	private HubPassPage() {
	}

	/** 领取动作。 */
	public record Claim(String track, int level) {
	}

	/** 点击结果：{@code claim!=null} 表示点到奖励格；{@code scrollTo>=0} 表示拖到了滚动条。 */
	public record Result(Claim claim, int scrollTo) {
		public static final Result NONE = new Result(null, -1);

		public boolean isEmpty() {
			return claim == null && scrollTo < 0;
		}
	}

	/**
	 * 纵向布局（渲染与命中唯一来源）。
	 *
	 * @param cellH 格子高度（30~52 自适应）
	 * @param row1  免费轨顶边
	 * @param row2  付费轨顶边
	 * @param barY  滚动条顶边
	 */
	record Layout(int cellH, int row1, int row2, int barY) {
	}

	static Layout layout(int y, int h) {
		int barBand = BAR_H + 4;
		int minGap = 6;
		int avail = h - TOP_H - LABEL_H * 2 - minGap - barBand;
		int cellH = Math.max(30, Math.min(CELL_H, avail / 2));
		int slack = Math.max(0, avail - cellH * 2);
		int extra = Math.min(40, slack);
		int gap = minGap + extra;
		slack -= extra;
		int topPad = Math.min(12, slack / 2);

		int row1 = y + TOP_H + topPad + LABEL_H;
		int row2 = row1 + cellH + gap + LABEL_H;
		int barY = Math.max(row2 + cellH + 3, y + h - BAR_H - 4);
		barY = Math.min(barY, y + h - BAR_H);
		return new Layout(cellH, row1, row2, barY);
	}

	public static void render(DrawContext dc, TextRenderer tr, int x, int y, int w, int h,
			int scroll, double mouseX, double mouseY) {
		PassProtocol.Snapshot s = PassClientNetworking.LAST;
		Layout lo = layout(y, h);

		dc.drawText(tr, Text.literal(s.season().isEmpty() ? "战令" : s.season()), x, y, HubTheme.TITLE, false);
		String lv = "等级 " + s.level();
		dc.drawText(tr, Text.literal(lv), x + w - tr.getWidth(lv), y, HubTheme.GOLD, false);

		// 经验条：右侧留出经验文字位置，字与条同一行（省一行高度，窗口态原本就是被这行挤爆的）
		int barY = y + 14;
		int barW = Math.max(20, w - XP_TEXT_W);
		dc.fill(x, barY, x + barW, barY + 6, HubTheme.TRACK);
		long need = Math.max(1, s.xpNext());
		int fill = (int) (barW * Math.min(1.0, (double) s.xp() / need));
		dc.fill(x, barY, x + fill, barY + 6, HubTheme.GOLD);
		String xpTxt = s.xp() + " / " + need + " 经验";
		dc.drawText(tr, Text.literal(xpTxt), x + w - tr.getWidth(xpTxt), barY - 1, HubTheme.MUTED, false);

		float sc = animate(scroll, visibleCells(w));

		// 免费轨
		dc.drawText(tr, Text.literal("免费"), x, lo.row1() - LABEL_H, HubTheme.TEXT, false);
		drawTrack(dc, tr, x, lo.row1(), w, lo.cellH(), s.free(), s.claimedFree(), sc,
				s.level(), true, true, mouseX, mouseY);

		// 付费轨
		dc.drawText(tr, Text.literal("付费"), x, lo.row2() - LABEL_H, HubTheme.GOLD, false);
		drawTrack(dc, tr, x, lo.row2(), w, lo.cellH(), s.prem(), s.claimedPrem(), sc,
				s.level(), s.premium(), false, mouseX, mouseY);

		// T61：滚动条（整条奖励装得下时不画）；位置由 layout 保证不压格子
		int size = maxSize();
		int visible = visibleCells(w);
		if (size > visible) {
			int tTop = lo.barY();
			dc.fill(x, tTop, x + w, tTop + BAR_H, HubTheme.TRACK);
			int[] th = thumbRect(x, w, scroll, size, visible);
			boolean hot = mouseX >= th[0] && mouseX < th[0] + th[1]
					&& mouseY >= tTop - 2 && mouseY < tTop + BAR_H + 2;
			dc.fill(th[0], tTop, th[0] + th[1], tTop + BAR_H, hot ? HubTheme.GOLD : HubTheme.PRIMARY);
		}

		// 未解锁时整条付费轨已经画暗，不再额外写文字（Aki 要求）
	}

	private static void drawTrack(DrawContext dc, TextRenderer tr, int x, int y, int w, int cellH,
			List<PassProtocol.Reward> rewards, List<Integer> claimed, float scroll, int level,
			boolean unlocked, boolean freeTrack, double mouseX, double mouseY) {
		if (rewards.isEmpty()) {
			dc.fill(x, y, x + w, y + cellH, HubTheme.CARD);
			dc.drawText(tr, Text.literal("奖励未配置"), x + 6, y + 8, HubTheme.MUTED, false);
			return;
		}
		int visible = visibleCells(w);
		float max = Math.max(0, rewards.size() - visible);
		float sc = Math.max(0f, Math.min(scroll, max));
		int first = (int) Math.floor(sc);
		int off = Math.round((sc - first) * (CELL_W + GAP));
		int last = Math.min(rewards.size(), first + visible + 1);

		// 动画期间首尾格会探出内容区 ⇒ 必须裁剪，否则会画到侧栏/边框上
		dc.enableScissor(x, y, x + w, y + cellH);
		for (int i = first; i < last; i++) {
			PassProtocol.Reward r = rewards.get(i);
			int cx = x + (i - first) * (CELL_W + GAP) - off;
			boolean done = r.level() <= level;
			boolean got = claimed.contains(r.level());
			boolean can = unlocked && done && !got;
			boolean hover = mouseX >= cx && mouseX < cx + CELL_W && mouseY >= y && mouseY < y + cellH;

			int bg = got ? HubTheme.DISABLED_BG : (can ? HubTheme.SELECT : (unlocked ? HubTheme.CARD : HubTheme.INNER));
			dc.fill(cx, y, cx + CELL_W, y + cellH, bg);
			int edge = can ? HubTheme.GOLD : (hover ? HubTheme.EDGE_HOVER : HubTheme.LINE);
			dc.fill(cx, y, cx + CELL_W, y + 1, edge);
			dc.fill(cx, y + cellH - 1, cx + CELL_W, y + cellH, edge);

			String lvl = String.valueOf(r.level());
			dc.drawText(tr, Text.literal(lvl), cx + 4, y + 4, done ? HubTheme.TEXT : HubTheme.MUTED, false);
			// 奖励：钱优先显示，其次物品数量
			String reward = r.money() > 0 ? ("+" + shortNum(r.money())) : (r.items().isEmpty() ? "—" : "×" + r.items().get(0).count());
			dc.drawText(tr, Text.literal(reward), cx + 4, y + 20, HubTheme.GOLD, false);
			String state = got ? "已领" : (done ? (unlocked ? "可领" : "锁定") : "未达");
			// 格子被压到 30px 时第三行会溢出 ⇒ 只在放得下时画状态行
			if (cellH >= 44) {
				dc.drawText(tr, Text.literal(state), cx + 4, y + 36,
						can ? HubTheme.PRIMARY_SOFT_TEXT : HubTheme.MUTED, false);
			}
			if (!freeTrack && !unlocked) {
				dc.fill(cx + CELL_W - 10, y + 4, cx + CELL_W - 4, y + 10, HubTheme.GOLD);
			}
		}
		dc.disableScissor();
	}

	/** 每帧把视觉偏移推向目标值（指数逼近，约 6 帧收敛）。 */
	private static float animate(int target, int visible) {
		float t = Math.max(0, target);
		if (smoothTarget != target || smoothVisible != visible) {
			// 换页 / 窗口宽度变了：直接吸附，避免从上一页的偏移滑过来（看着像穿模）
			if (smoothTarget < 0 || smoothVisible != visible) smooth = t;
			smoothTarget = target;
			smoothVisible = visible;
		}
		float diff = t - smooth;
		if (Math.abs(diff) < 0.02f) {
			smooth = t;
		} else {
			smooth += diff * 0.35f;
		}
		return smooth;
	}

	private static String shortNum(long v) {
		if (v >= 10000) return (v / 1000) + "k";
		return String.valueOf(v);
	}

	// ---------------------------------------------------------------- 几何（渲染与命中共用同一份）

	private static int visibleCells(int w) {
		return Math.max(1, (w + GAP) / (CELL_W + GAP));
	}

	private static int maxSize() {
		return Math.max(PassClientNetworking.LAST.free().size(), PassClientNetworking.LAST.prem().size());
	}

	/** 滑块 {左, 宽}；{@code scroll}/{@code size} 都是目标值，和命中口径一致。 */
	static int[] thumbRect(int x, int w, int scroll, int size, int visible) {
		int max = Math.max(0, size - visible);
		if (max <= 0) return new int[]{x, w};
		int tw = Math.min(w, Math.max(16, Math.round((float) w * visible / Math.max(1, size))));
		int left = x + Math.round((float) (w - tw) * Math.max(0, Math.min(scroll, max)) / max);
		return new int[]{left, tw};
	}

	/** 鼠标 x → 滚动条对应的目标 scroll。 */
	private static int scrollFromX(double mx, int x, int w, int size, int visible) {
		int max = Math.max(0, size - visible);
		if (max <= 0) return 0;
		int tw = Math.min(w, Math.max(16, Math.round((float) w * visible / Math.max(1, size))));
		double frac = (mx - x - tw / 2.0) / Math.max(1, w - tw);
		return (int) Math.round(Math.max(0, Math.min(1, frac)) * max);
	}

	/** 是否点在滚动条带上（含 2px 容差，方便点中）。 */
	public static boolean hitBar(double mx, double my, int x, int y, int w, int h) {
		int size = maxSize();
		if (size <= visibleCells(w)) return false;
		int t = layout(y, h).barY();
		return mx >= x && mx < x + w && my >= t - 2 && my < t + BAR_H + 2;
	}

	/**
	 * T61 夹具：滚动条右端中心点（{@code null} = 整条装得下、没有滚动条）。
	 *
	 * <p>坐标由 layout 算，夹具不写死 —— 上一版滚动条钉在内容区最底，改布局后
	 * 写死的 y 会点到付费轨上，还会「验证成功」。
	 */
	static double[] barEndCenter(int x, int y, int w, int h) {
		if (maxSize() <= visibleCells(w)) return null;
		Layout lo = layout(y, h);
		return new double[]{x + w - 6, lo.barY() + BAR_H / 2.0};
	}

	/** 拖拽期间每帧调用（宿主在 mouseDragged 里）。 */
	public static int scrollForDrag(double mx, int x, int w) {
		return scrollFromX(mx, x, w, maxSize(), visibleCells(w));
	}

	/** 点击：返回要领取的 (track, level) 或滚动条目标位；都没点中返回 {@link Result#NONE}。 */
	public static Result click(double mx, double my, int x, int y, int w, int h, int scroll) {
		PassProtocol.Snapshot s = PassClientNetworking.LAST;
		int visible = visibleCells(w);
		Layout lo = layout(y, h);

		// T61：先判滚动条（它在轨下方，和奖励格不重叠）
		if (hitBar(mx, my, x, y, w, h)) {
			return new Result(null, scrollFromX(mx, x, w, maxSize(), visible));
		}

		int idx = hitCell(mx, my, x, lo.row1(), w, lo.cellH(), visible, s.free().size(), scroll);
		if (idx >= 0 && idx < s.free().size()) {
			return new Result(new Claim("free", s.free().get(idx).level()), -1);
		}
		idx = hitCell(mx, my, x, lo.row2(), w, lo.cellH(), visible, s.prem().size(), scroll);
		if (idx >= 0 && idx < s.prem().size()) {
			return new Result(new Claim("premium", s.prem().get(idx).level()), -1);
		}
		return Result.NONE;
	}

	private static int hitCell(double mx, double my, int x, int rowY, int w, int cellH, int visible,
			int size, int scroll) {
		if (my < rowY || my >= rowY + cellH) return -1;
		int start = Math.max(0, Math.min(scroll, Math.max(0, size - visible)));
		for (int i = start; i < Math.min(size, start + visible); i++) {
			int cx = x + (i - start) * (CELL_W + GAP);
			if (mx >= cx && mx < cx + CELL_W) return i;
		}
		return -1;
	}

	public static int maxScroll(int w) {
		return Math.max(0, maxSize() - visibleCells(w));
	}
}
