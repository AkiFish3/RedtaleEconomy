package com.akira.redtale.economy.client.ui.hub;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 任务页顶部间距（Aki 2026-10-23：日期行离顶部太近）。
 *
 * <p>三条几何必须满足：分栏条与内容区顶之间留白、信息行不压分栏条、
 * 列表不压信息行。绘制与命中都调这三个函数 —— 以前各写一套数字，
 * 调间距就会变成「画在这、点在那」。
 */
class HubTasksLayoutTest {

	private static final int Y = 40;

	@Test
	void leavesBreathingRoomAboveTabs() {
		int pad = HubTasksPage.tabTop(Y) - Y;
		assertTrue(pad >= 4, "分栏上方至少留 4px，实际 " + pad);
	}

	@Test
	void infoRowSitsBelowTabStrip() {
		int tabsY = HubTasksPage.tabTop(Y);
		int infoY = HubTasksPage.infoTop(Y);
		// chip 高 13 + 分隔线 1 + 至少 3px 空隙
		assertTrue(infoY - tabsY >= 13 + 1 + 3,
				"信息行必须离分栏有间距：tabs=" + tabsY + " info=" + infoY);
	}

	@Test
	void listDoesNotOverlapInfoText() {
		int infoY = HubTasksPage.infoTop(Y);
		int listY = HubTasksPage.listTop(Y);
		// 信息行是 8px 字形
		assertTrue(listY - infoY >= 10,
				"列表顶边必须在信息行文字之下：info=" + infoY + " list=" + listY);
	}

	@Test
	void geometryIsMonotonic() {
		assertTrue(HubTasksPage.tabTop(Y) < HubTasksPage.infoTop(Y));
		assertTrue(HubTasksPage.infoTop(Y) < HubTasksPage.listTop(Y));
	}

	@Test
	void offsetsScaleWithContentOrigin() {
		// 面板顶下移 20px，三行整体跟着下移 20px（不能有硬编码的绝对 y）
		assertEquals(HubTasksPage.tabTop(Y) + 20, HubTasksPage.tabTop(Y + 20));
		assertEquals(HubTasksPage.infoTop(Y) + 20, HubTasksPage.infoTop(Y + 20));
		assertEquals(HubTasksPage.listTop(Y) + 20, HubTasksPage.listTop(Y + 20));
	}
}
