package com.akira.redtale.economy.client.ui.hub;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 排行榜下拉几何回归（第 N+72 轮）。
 *
 * <p>缺陷原样：{@code drawDropdown} 与 {@code hitDropdown} 各写一遍
 * {@code Math.max(y - listH, y - listH)}（同一个参数写两次，等于没夹）⇒ 下方放不下时
 * 下拉被画到内容区顶边之上（顶栏/侧栏甚至窗口外），命中区跟着一起跑到面板外。
 * 现在两处共用 {@link HubRankPage#dropdownTop}，本测试锁住三条规则。
 *
 * <p>下拉 5 项 × 16 = 80。
 */
class HubRankDropdownHitTest {

	private static final int LIST_H = HubRankPage.BOARDS.length * 16;
	private static final int PANEL_TOP = 40;

	/** ① 下面放得下（427×240 常态）：向下展开。 */
	@Test
	void opensDownwardWhenItFits() {
		assertEquals(60, HubRankPage.dropdownTop(40, PANEL_TOP, 198, LIST_H));
	}

	/** ② 下面放不下、上面放得下：向上翻，整块在内容区内且不压住下拉框。 */
	@Test
	void flipsUpWithoutCoveringTheBox() {
		int y = 160;
		int top = HubRankPage.dropdownTop(y, PANEL_TOP, 190, LIST_H);
		assertEquals(y - LIST_H, top);
		assertTrue(top >= PANEL_TOP, "不许越过内容区顶");
		assertTrue(top + LIST_H <= y, "翻上去不许压住下拉框（压住则首项与开关命中区重叠）");
	}

	/** ③ 上下都不够（窗口极窄）：仍然向下，绝不画到面板顶之上。 */
	@Test
	void tinyPanelFallsBackDownwardInsidePanel() {
		int y = 60;
		int top = HubRankPage.dropdownTop(y, PANEL_TOP, y + 10, LIST_H);
		assertEquals(y + 20, top);
		assertTrue(top >= PANEL_TOP);
	}

	/** 命中跟着同一份几何走：正常窗口下第 i 项 = 顶边 + 16i。 */
	@Test
	void itemIndexMathMatchesRender() {
		for (int i = 0; i < HubRankPage.BOARDS.length; i++) {
			int top = HubRankPage.dropdownTop(40, PANEL_TOP, 198, LIST_H) + i * 16;
			assertEquals(i, HubRankPage.hitDropdown(10, top + 2, 0, 40, true, PANEL_TOP, 198));
		}
	}

	/** 下拉框本体（y..y+18）永远是「点开/关闭」，收起时列表区不命中。 */
	@Test
	void boxTogglesAndClosedListIsDead() {
		assertEquals(-2, HubRankPage.hitDropdown(10, 44, 0, 40, true, PANEL_TOP, 198));
		assertEquals(-1, HubRankPage.hitDropdown(10, 64, 0, 40, false, PANEL_TOP, 198));
		assertEquals(-1, HubRankPage.hitDropdown(200, 64, 0, 40, true, PANEL_TOP, 198), "x 越界");
	}

	/** 翻上去之后，越出内容区顶的部分不许命中。 */
	@Test
	void abovePanelTopIsDeadAfterFlip() {
		int y = 160;
		int top = HubRankPage.dropdownTop(y, PANEL_TOP, 190, LIST_H);
		assertEquals(-1, HubRankPage.hitDropdown(10, top - 2, 0, y, true, PANEL_TOP, 190));
		assertEquals(0, HubRankPage.hitDropdown(10, top + 2, 0, y, true, PANEL_TOP, 190));
	}
}
