package com.akira.redtale.economy.client.ui.hub;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 悬赏页命中几何回归（第 N+58 轮）。
 *
 * <p>缺陷原样：点击侧自己算 {@code (int) ((mouseY - (cy+16)) / 48)}，不判 x、不判卡片是否被画出来，
 * 于是点标题行（y..y+16）时 `-n / 48` 向零截断成 0 ⇒ 直接把第一条悬赏领了。
 * 本测试只碰纯几何函数，不需要 Minecraft 运行时。
 */
class HubBountyHitTest {

	private static final int X = 0;
	private static final int Y = 0;
	private static final int W = 295;
	/** 窗口态内容区高（427×240 ⇒ panelH=220 ⇒ 160）。 */
	private static final int H = 160;

	@Test
	void titleRowIsNotACard() {
		assertEquals(-1, HubBountyPage.indexAt(X + 10, Y + 4, X, Y, W, H, 5), "标题行不该命中卡片");
		assertEquals(-1, HubBountyPage.indexAt(X + 10, Y + 15, X, Y, W, H, 5), "标题行下沿仍是标题");
	}

	@Test
	void firstAndSecondCard() {
		assertEquals(0, HubBountyPage.indexAt(X + 10, Y + 16 + 20, X, Y, W, H, 5));
		assertEquals(1, HubBountyPage.indexAt(X + 10, Y + 16 + 48 + 20, X, Y, W, H, 5));
	}

	@Test
	void gapBetweenCardsIsDead() {
		// 第 0 张卡 y+16..y+60，第 1 张 y+64.. ⇒ 中间 4px 空隙
		assertEquals(-1, HubBountyPage.indexAt(X + 10, Y + 16 + 45, X, Y, W, H, 5));
	}

	@Test
	void outsideContentAreaIsDead() {
		assertEquals(-1, HubBountyPage.indexAt(X - 1, Y + 30, X, Y, W, H, 5), "左越界");
		assertEquals(-1, HubBountyPage.indexAt(X + W, Y + 30, X, Y, W, H, 5), "右越界");
	}

	/** 内容区 160 只画得下 2 张（16+44 ≤ 134），第 3 张虽在数据里但没画 ⇒ 不许命中。 */
	@Test
	void cardsNotDrawnAreNotClickable() {
		assertEquals(2, HubBountyPage.rowsVisible(H));
		assertEquals(-1, HubBountyPage.indexAt(X + 10, Y + 16 + 96 + 20, X, Y, W, H, 5));
	}

	@Test
	void emptyListNeverHits() {
		assertEquals(-1, HubBountyPage.indexAt(X + 10, Y + 20, X, Y, W, H, 0));
	}

	// ---------------------------------------------------------------- 滚动（第 N+71 轮）

	/** 悬赏位最多 9 个（协会等级还加），160 高一屏 2 张 ⇒ 上限必须是「总数 − 屏内行」。 */
	@Test
	void scrollCeilingFollowsListSize() {
		assertEquals(7, HubBountyPage.maxScroll(H, 9), "9 条一屏 2 张 ⇒ 上限 7");
		assertEquals(0, HubBountyPage.maxScroll(H, 2), "刚好装满时不许滚");
		assertEquals(0, HubBountyPage.maxScroll(H, 0), "空列表上限为 0");
	}

	/** ★滚动后「屏内第 0 行」必须映射到数据下标 scroll★（不映射 = 看着点第 3 张、实际领第 1 张）。 */
	@Test
	void scrolledRowMapsToDataIndex() {
		assertEquals(2, HubBountyPage.indexAt(X + 10, Y + 16 + 20, X, Y, W, H, 9, 2));
		assertEquals(3, HubBountyPage.indexAt(X + 10, Y + 16 + 48 + 20, X, Y, W, H, 9, 2));
	}

	/** 滚到底时最后一行只画到列表尾，越界行不许命中。 */
	@Test
	void scrolledPastEndIsDead() {
		// scroll=7、9 条 ⇒ 屏内 0→7、1→8，没有第 3 行可点
		assertEquals(7, HubBountyPage.indexAt(X + 10, Y + 16 + 20, X, Y, W, H, 9, 7));
		assertEquals(8, HubBountyPage.indexAt(X + 10, Y + 16 + 48 + 20, X, Y, W, H, 9, 7));
		assertEquals(-1, HubBountyPage.indexAt(X + 10, Y + 16 + 96 + 20, X, Y, W, H, 9, 7));
	}
}
