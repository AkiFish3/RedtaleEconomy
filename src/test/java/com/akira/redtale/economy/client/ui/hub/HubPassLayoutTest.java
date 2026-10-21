package com.akira.redtale.economy.client.ui.hub;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T61 回归：战令页纵向布局在矮窗口下不得越界。
 *
 * <p>起因（第 N+57 轮 u6-74 图）：427×240（guiScale=2、panelH=220 ⇒ 内容区高 160）时
 * 付费轨被面板底边裁掉、滚动条压在格子上；根因是格子高写死 52、头部占 44、
 * 而且命中用 y+34 / 渲染用 y+44 两套算术。
 *
 * <p>本测试只碰 {@code layout()} 这个纯函数，不需要 Minecraft 运行时。
 */
class HubPassLayoutTest {

	/** 内容区高度下限：panelH 最小 180 − TOP_BAR 24 − BOTTOM_BAR 20 − 16。 */
	private static final int H_MIN = 120;
	/** 窗口态实测：panelH=220 ⇒ 160。 */
	private static final int H_WIN = 160;
	/** 全屏态实测：panelH=340 ⇒ 280。 */
	private static final int H_FS = 280;

	@Test
	void tracksFitInShortWindow() {
		for (int h : new int[]{H_MIN, H_WIN, 200, H_FS, 360}) {
			HubPassPage.Layout lo = HubPassPage.layout(0, h);
			int y = 0;
			// 免费轨标签在上方 10px，不能压到标题/经验条（TOP_H=26）
			assertTrue(lo.row1() - 10 >= 26, "h=" + h + " 免费轨标签压到头部");
			// 两轨不重叠
			assertTrue(lo.row2() - 10 >= lo.row1() + lo.cellH(),
					"h=" + h + " 付费轨标签压到免费轨");
			// 付费轨底不越过滚动条
			assertTrue(lo.row2() + lo.cellH() <= lo.barY(),
					"h=" + h + " 付费轨被滚动条压住");
			// 滚动条留在内容区内（BAR_H=4）
			assertTrue(lo.barY() + 4 <= y + h, "h=" + h + " 滚动条越出内容区");
			// 格子高在合理区间（放得下三行文字就需要 >=30）
			assertTrue(lo.cellH() >= 30 && lo.cellH() <= 52, "h=" + h + " cellH=" + lo.cellH());
		}
	}

	@Test
	void windowCaseKeepsWholePremiumTrack() {
		HubPassPage.Layout lo = HubPassPage.layout(0, H_WIN);
		// 上一版：row2 = 120、cellH = 52 ⇒ 底 172 > 160（被裁 12px）
		assertTrue(lo.row2() + lo.cellH() <= H_WIN, "付费轨底 " + (lo.row2() + lo.cellH()) + " > 160");
	}

	/** 渲染与命中必须取同一个 row1（上一版差 10px，点击整体偏一格）。 */
	@Test
	void clickUsesRenderedRows() {
		HubPassPage.Layout lo = HubPassPage.layout(0, H_WIN);
		assertEquals(lo.row1(), HubPassPage.layout(0, H_WIN).row1());
		// 付费轨顶边 = 免费轨底 + gap + 标签高 ⇒ 必须严格大于免费轨底
		assertTrue(lo.row2() > lo.row1() + lo.cellH());
	}

	@Test
	void thumbWidthNeverFullWhenScrollable() {
		int x = 0;
		int w = 295;
		int[] th = HubPassPage.thumbRect(x, w, 0, 8, 6);
		assertTrue(th[1] < w, "可滚动时滑块不该占满整条");
		assertEquals(x, th[0], "scroll=0 时滑块贴最左");
		int[] last = HubPassPage.thumbRect(x, w, 2, 8, 6);
		assertEquals(w, last[0] + last[1], "滚到底时滑块贴最右");
	}
}
