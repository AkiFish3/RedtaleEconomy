package com.akira.redtale.economy.client.ui.hub;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 列表页滚动上限回归（第 N+71 轮）。
 *
 * <p>起因：悬赏 / 协会 / 排行三个页面的数据条数由配置或服务端决定，427×240（内容区 160）
 * 下一屏只能放 2 / 6 / 5 行，超出的条目原来直接 {@code break} 掉 ⇒ 既看不见也点不到，
 * 而且界面上没有任何「下面还有」的线索。
 *
 * <p>本测试只碰纯几何（rowsVisible / maxScroll），不需要 Minecraft 运行时；
 * 尺寸取自实测：窗口态内容区 160、全屏态 280。
 */
class HubListScrollTest {

	private static final int H_WIN = 160;
	private static final int H_FS = 280;

	@Test
	void bountyShowsTwoCardsPerScreen() {
		assertEquals(2, HubBountyPage.rowsVisible(H_WIN));
		assertEquals(7, HubBountyPage.maxScroll(H_WIN, 9), "9 个悬赏位 ⇒ 上限 7");
		// 全屏 280：usable=238 ⇒ 1+(238-44)/48 = 5
		assertEquals(5, HubBountyPage.rowsVisible(H_FS));
	}

	@Test
	void guildLadderNeedsScrollWithDefaultRanks() {
		// 默认 7 档（F/E/D/C/B/A/S）
		assertEquals(6, HubGuildPage.rowsVisible(H_WIN));
		assertEquals(1, HubGuildPage.maxScroll(H_WIN, 7), "7 档一屏 6 行 ⇒ 必须能滚 1 行");
		assertTrue(HubGuildPage.maxScroll(H_FS, 7) == 0, "全屏放得下就不该有滚动条");
	}

	@Test
	void rankTop10NeedsScroll() {
		// 服务端只发前 10 名
		assertEquals(5, HubRankPage.rowsVisible(H_WIN));
		assertEquals(5, HubRankPage.maxScroll(H_WIN, 10), "第 6~10 名必须滚得到");
		assertTrue(HubRankPage.maxScroll(H_FS, 10) >= 0);
	}

	/** 上限不能为负：列表比一屏短时 maxScroll 必须是 0（否则夹具会"滚"进负数）。 */
	@Test
	void shortListsNeverScroll() {
		assertEquals(0, HubBountyPage.maxScroll(H_WIN, 1));
		assertEquals(0, HubGuildPage.maxScroll(H_WIN, 3));
		assertEquals(0, HubRankPage.maxScroll(H_WIN, 4));
	}
}
