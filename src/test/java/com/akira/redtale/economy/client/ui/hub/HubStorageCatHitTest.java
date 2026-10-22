package com.akira.redtale.economy.client.ui.hub;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 仓储页「分类下拉」命中回归（第 N+72 轮）。
 *
 * <p>起因：下拉列表画在网格之上（浮层最后画），但命中判定里根本没有列表行这条路径 ——
 * {@code click()} 只把 16px 的 CAT 按钮带算作分类，列表行（y+32..y+112）落进下方的
 * 仓储网格判定 ⇒ <b>点分类等于抓起/放下物品</b>。格子是搬东西的地方，误触就是搬错物品，
 * 而且「选中分类」这个操作彻底做不成。
 *
 * <p>尺寸取实测：窗口态内容区 427×240 下 w≈315 / h=160。
 */
class HubStorageCatHitTest {

	private static final int X = 0;
	private static final int Y = 0;
	private static final int W = 300;
	private static final int H = 160;

	@AfterEach
	void closeDropdown() {
		HubStoragePage.catOpen = false;
	}

	@Test
	void dropdownRowsWinOverGrid() {
		HubStoragePage.catOpen = true;
		for (int cat = 0; cat < HubStoragePage.CATS.length; cat++) {
			double[] p = HubStoragePage.catRowCenter(cat, X, Y, W, H);
			assertTrue(p != null, "第 " + cat + " 行必须在内容区内");
			HubStoragePage.Result r = HubStoragePage.click(p[0], p[1], X, Y, W, H, 0, 0);
			assertEquals(HubStoragePage.Hit.CAT, r.hit(), "列表行必须是分类命中，不能是网格");
			assertEquals(cat, r.index(), "带出的分类下标必须就是被点的那一行");
		}
	}

	/** 列表首行与网格首行重叠（y+32 vs y+38）—— 这正是误搬物品的现场。 */
	@Test
	void firstRowOverlapsGridAndStillSelects() {
		double[] p = HubStoragePage.catRowCenter(0, X, Y, W, H);
		assertTrue(p[1] > Y + 38, "首行中心应落在网格区内部（证明两者确实重叠）");
		HubStoragePage.catOpen = true;
		assertEquals(HubStoragePage.Hit.CAT,
				HubStoragePage.click(p[0], p[1], X, Y, W, H, 0, 0).hit());
		// 收起后同一坐标不再是分类（回到网格语义，这里只看纯几何，不触碰客户端仓储状态）
		HubStoragePage.catOpen = false;
		assertEquals(-1, HubStoragePage.catRowAt(p[0], p[1], X, Y, W, H));
	}

	@Test
	void toggleButtonReportsMinusTwo() {
		double[] b = HubStoragePage.catButtonCenter(X, Y, W);
		HubStoragePage.Result r = HubStoragePage.click(b[0], b[1], X, Y, W, H, 0, 0);
		assertEquals(HubStoragePage.Hit.CAT, r.hit());
		assertEquals(-2, r.index(), "-2 = 开/关，>=0 = 选中分类，两者不能混");
	}

	/** 点列表外要收起（否则下拉永远浮在网格上，玩家只能靠再点一次按钮关掉）。 */
	@Test
	void clickOutsideClosesDropdown() {
		HubStoragePage.catOpen = true;
		// 点右上角「升级」按钮（在网格之前判定，headless 安全）：同一次点击照常生效，下拉收起
		HubStoragePage.Result r = HubStoragePage.click(X + W - 130, Y + 2, X, Y, W, H, 0, 0);
		assertFalse(HubStoragePage.catOpen, "点列表外应收起下拉");
		assertEquals(HubStoragePage.Hit.UPGRADE, r.hit(), "收起之后这次点击仍要按原逻辑处理");
	}

	/** 列表左侧（同一高度但不在浮层里）不是分类行：行命中只在自己的矩形内。 */
	@Test
	void clickLeftOfListIsNotCategory() {
		HubStoragePage.catOpen = true;
		double[] p = HubStoragePage.catRowCenter(0, X, Y, W, H);
		assertEquals(-1, HubStoragePage.catRowAt(p[0] - 100, p[1], X, Y, W, H));
	}

	@Test
	void closedDropdownNeverHits() {
		assertEquals(-1, HubStoragePage.catRowAt(X + W - 44, Y + 40, X, Y, W, H));
		assertFalse(HubStoragePage.inCatList(X + W - 44, Y + 40, X, Y, W, H));
	}
}
