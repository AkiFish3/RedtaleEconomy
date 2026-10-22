package com.akira.redtale.economy.client.ui.hub;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 余额显示状态机（A 第 N+78 轮）。
 *
 * <p>钉住的两件事，都是「界面上不该出现的动画」：
 * <ol>
 *   <li>未知快照（-1）不参与插值 —— 否则数字会朝 -1 快速滑一段再弹回来；</li>
 *   <li>下跌在 {@link BalanceDisplay#SETTLE_MS} 内被回滚 ⇒ <b>屏幕值一次都不变</b>
 *       （转账失败时那一下闪烁就是这么来的）。</li>
 * </ol>
 */
class BalanceDisplayTest {

	@AfterEach
	void tearDown() {
		BalanceDisplay.reset();
	}

	@Test
	void firstSnapshotSnaps() {
		BalanceDisplay.feed(916640L, 0L);
		assertEquals(916640L, BalanceDisplay.value(0L));
		assertEquals(916640L, BalanceDisplay.value(5000L));
	}

	@Test
	void unknownSnapshotIsIgnored() {
		BalanceDisplay.feed(916640L, 0L);
		BalanceDisplay.feed(-1L, 10L);          // Numismatic 拿不到组件 / 还没同步
		assertEquals(916640L, BalanceDisplay.value(10L));
		assertEquals(916640L, BalanceDisplay.value(900L));
	}

	@Test
	void failedTransferNeverFlickers() {
		BalanceDisplay.feed(916640L, 0L);
		// 失败路径：先来一个「扣掉了 100」的快照，再来一个「原值」——服务端没有扣钱
		BalanceDisplay.feed(916540L, 100L);
		assertEquals(916640L, BalanceDisplay.value(100L), "沉降窗口内不许掉");
		assertEquals(916640L, BalanceDisplay.value(399L), "沉降窗口内不许掉");
		BalanceDisplay.feed(916640L, 200L);      // 回滚
		assertEquals(916640L, BalanceDisplay.value(400L));
		assertFalse(BalanceDisplay.hasPending(), "回滚必须把待确认的下跌丢掉");
		assertEquals(916640L, BalanceDisplay.value(5000L));
	}

	@Test
	void realSpendSettlesThenAnimates() {
		BalanceDisplay.feed(916640L, 0L);
		BalanceDisplay.feed(916540L, 100L);
		assertTrue(BalanceDisplay.hasPending());
		// 沉降期内不动
		assertEquals(916640L, BalanceDisplay.value(399L));
		// 到点：开始 250ms 滚动
		assertEquals(916640L, BalanceDisplay.value(400L));
		long mid = BalanceDisplay.value(525L);
		assertTrue(mid < 916640L && mid > 916540L, "中间帧应在两者之间：" + mid);
		assertEquals(916540L, BalanceDisplay.value(650L));
	}

	@Test
	void incomeAppliesImmediately() {
		BalanceDisplay.feed(916640L, 0L);
		BalanceDisplay.feed(916740L, 100L);     // 签到/收款：不能等 300ms
		long v = BalanceDisplay.value(100L);
		assertEquals(916640L, v, "动画起点是最新一帧的屏幕值");
		assertEquals(916740L, BalanceDisplay.value(350L));
	}

	@Test
	void resetForgetsPreviousSession() {
		BalanceDisplay.feed(916640L, 0L);
		BalanceDisplay.reset();
		assertEquals(-1L, BalanceDisplay.value(0L));
		BalanceDisplay.feed(5L, 0L);            // 新局首个快照仍然直接落定
		assertEquals(5L, BalanceDisplay.value(0L));
	}
}
