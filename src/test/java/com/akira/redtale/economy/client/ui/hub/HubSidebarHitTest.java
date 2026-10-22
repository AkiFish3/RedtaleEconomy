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
}
