package com.akira.redtale.economy.shop;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** U11：商店目录树（路径即树，`/` 分段）。 */
class ShopTreeTest {

	private static final List<String> CATS = List.of(
			"建材/木材/原木",
			"建材/木材/台阶",
			"建材/石材/方块",
			"材料/矿石",
			"食物");

	@Test
	void rootChildrenAreFirstSegments() {
		assertEquals(List.of("建材", "材料", "食物"), ShopTree.children(CATS, ""));
	}

	@Test
	void childrenOfNodeAreNextSegment() {
		assertEquals(List.of("木材", "石材"), ShopTree.children(CATS, "建材"));
		assertEquals(List.of("原木", "台阶"), ShopTree.children(CATS, "建材/木材"));
	}

	@Test
	void maxDepthStopsExpanding() {
		// 3 级封顶：建材/木材 是第 2 级，还能出第 3 级；第 3 级不再往下
		assertTrue(ShopTree.children(CATS, "建材/木材").isEmpty() == false);
		assertTrue(ShopTree.children(CATS, ShopTree.join("建材/木材", "原木")).isEmpty());
	}

	@Test
	void hasChildrenDrivesArrow() {
		assertTrue(ShopTree.hasChildren(CATS, "建材"));
		assertFalse(ShopTree.hasChildren(CATS, "食物"));
		assertFalse(ShopTree.hasChildren(CATS, "建材/木材/原木"));
	}

	@Test
	void parentAndCrumbs() {
		assertEquals("建材/木材", ShopTree.parent("建材/木材/台阶"));
		assertEquals("", ShopTree.parent("食物"));
		assertEquals(List.of("建材", "木材", "台阶"), ShopTree.crumbs("建材/木材/台阶"));
		assertEquals(List.of(), ShopTree.crumbs(""));
		assertEquals("台阶", ShopTree.label("建材/木材/台阶"));
	}

	@Test
	void filterMatchesWholeSubtreeButNotFakePrefix() {
		assertTrue(ShopTree.matches("建材/木材/台阶", "建材"), "选中建材要含整棵子树");
		assertTrue(ShopTree.matches("建材/木材/台阶", "建材/木材"));
		assertTrue(ShopTree.matches("建材", "建材"), "自身命中");
		assertFalse(ShopTree.matches("wooden_planks", "wood"), "假前缀不能被当成子目录");
		assertTrue(ShopTree.matches("任意", ""), "空条件 = 不过滤");
	}

	@Test
	void depthCountsSegments() {
		assertEquals(0, ShopTree.depth(""));
		assertEquals(1, ShopTree.depth("食物"));
		assertEquals(3, ShopTree.depth("建材/木材/台阶"));
	}
}
