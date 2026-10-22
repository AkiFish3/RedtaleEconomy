package com.akira.redtale.economy.client.ui.hub;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 侧栏行命中边界回归（第 N+72 轮）。
 *
 * <p>起因：条目列表在渲染时被 scissor 裁在 {@code [entryTop, entryBottom)}，但命中只按
 * 「(mouseY − entryTop + scroll) / 行高」算行号，完全没判 Y 是否还在条目区内 —— 底栏
 * （状态行/提示）与侧栏同列且在列表下方，点一下算出来的仍是合法页号 ⇒ <b>点底部空白会切页</b>。
 *
 * <p>只测纯几何 {@link HubSidebar#rowAt(int, int, int, int, double)}：不碰 Minecraft 运行时。
 */
class HubSidebarHitTest {

	/** 实测口径：窗口态 427×240，entryTop = y+80、entryBottom = y+200（17 个页面）。 */
	private static final int TOP = 100;
	private static final int BOTTOM = 220;
	private static final int COUNT = 17;

	@Test
	void insideEntryAreaMapsToRow() {
		assertEquals(0, HubSidebar.rowAt(COUNT, TOP, BOTTOM, 0, TOP + 1));
		assertEquals(0, HubSidebar.rowAt(COUNT, TOP, BOTTOM, 0, TOP + 23));
		assertEquals(1, HubSidebar.rowAt(COUNT, TOP, BOTTOM, 0, TOP + 24));
	}

	/** 底栏在侧栏列内、列表下方 —— 这里必须不命中（原来会切到第 5 页）。 */
	@Test
	void bottomBarNeverHits() {
		assertEquals(-1, HubSidebar.rowAt(COUNT, TOP, BOTTOM, 0, BOTTOM));
		assertEquals(-1, HubSidebar.rowAt(COUNT, TOP, BOTTOM, 0, BOTTOM + 1));
		assertEquals(-1, HubSidebar.rowAt(COUNT, TOP, BOTTOM, 0, BOTTOM + 40));
	}

	/** 顶栏/玩家区在列表上方，同样不命中。 */
	@Test
	void profileAreaNeverHits() {
		assertEquals(-1, HubSidebar.rowAt(COUNT, TOP, BOTTOM, 0, TOP - 1));
		assertEquals(-1, HubSidebar.rowAt(COUNT, TOP, BOTTOM, 0, TOP - 60));
	}

	@Test
	void scrollShiftsRows() {
		// scroll 是**像素**偏移（scrollBy 里 amount × ROW_H）：滚 2 行 = 48px
		int two = 2 * HubFrame.ROW_H;
		assertEquals(2, HubSidebar.rowAt(COUNT, TOP, BOTTOM, two, TOP + 1));
		assertEquals(3, HubSidebar.rowAt(COUNT, TOP, BOTTOM, two, TOP + 25));
	}

	/** 行号必须 < 页数：列表末尾之外的 Y 不能算出越界页号。 */
	@Test
	void pastLastRowReturnsMinusOne() {
		// 5 个页面、可视区 120px（5 行）⇒ 最后一行底边就是 BOTTOM，正好不命中
		assertEquals(4, HubSidebar.rowAt(5, TOP, TOP + 120, 0, TOP + 119));
		assertEquals(-1, HubSidebar.rowAt(5, TOP, TOP + 120, 0, TOP + 120));
		assertEquals(-1, HubSidebar.rowAt(5, TOP, TOP + 200, 0, TOP + 130));
	}

	/**
	 * 夹具点击路径（第 N+74 轮）：先按 {@code scroll = min(maxScroll, index × ROW_H)} 把目标行
	 * 滚进条目区，再取该行中心点。<b>对 17 个页面逐一断言点得中自己</b>。
	 *
	 * <p>起因：427×240 下条目区只有 5 行，第 12 行（称号）中心落在 entryBottom 之下，
	 * {@code rowAt} 的 {@code [entryTop, entryBottom)} 夹取会直接判 miss
	 * （2026-10-23 实机日志 {@code page click TITLES hit=false}，拍出来还是上一页）。
	 * 公式与 {@code HubDev.clickSidebar} 逐字一致 —— 那边改了这里必须跟着改。
	 */
	@Test
	void fixtureScrollLandsOnTargetRow() {
		int bandH = BOTTOM - TOP;
		int maxScroll = Math.max(0, COUNT * HubFrame.ROW_H - bandH);
		for (int index = 0; index < COUNT; index++) {
			int scroll = Math.max(0, Math.min(maxScroll, index * HubFrame.ROW_H));
			double y = TOP - scroll + index * HubFrame.ROW_H + HubFrame.ROW_H / 2.0;
			assertEquals(index, HubSidebar.rowAt(COUNT, TOP, BOTTOM, scroll, y),
					"index=" + index + " scroll=" + scroll + " y=" + y);
		}
	}
}
