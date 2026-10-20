package com.akira.redtale.economy.economy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** B23：限时货币到期演练（FIFO 顺序、过期不计余额、过期币不被静默花掉）。 */
class TimedBatchesTest {

	private static final long HOUR = 3_600_000L;
	private static final long NOW = 1_000_000_000L;

	@Test
	void spendsEarliestExpiryFirst() {
		TimedBatches b = new TimedBatches();
		b.add(100, NOW + HOUR);          // 先到期
		b.add(100, NOW + 10 * HOUR);     // 后到期
		b.add(100, 0);                   // 永久

		assertEquals(300, b.available(NOW));
		TimedBatches.Spend s = b.spend(150, NOW);
		assertEquals(150, s.spent());

		var snap = b.snapshot();
		// 第一批 100 花光被移除；后到期的剩 50；永久仍是 100
		assertEquals(2, snap.size());
		assertEquals(50, snap.get(0).amount);
		assertEquals(0, snap.get(1).expiresAt);
		assertEquals(150, b.available(NOW));
	}

	@Test
	void expiredBatchIsNotCountedAndNotSpent() {
		TimedBatches b = new TimedBatches();
		b.add(500, NOW - HOUR);   // 已过期
		b.add(200, 0);

		assertEquals(200, b.available(NOW));
		assertEquals(500, b.expiredAmount(NOW));

		// 想花 300 但只有 200 可用 ⇒ 拒绝，且不能偷偷用过期的 500
		TimedBatches.Spend fail = b.spend(300, NOW);
		assertEquals(0, fail.spent());
		assertEquals(200, b.available(NOW));

		// 花 200 成功；过期批次应被顺带踢掉并报告作废量
		TimedBatches.Spend ok = b.spend(200, NOW);
		assertEquals(200, ok.spent());
		assertEquals(500, ok.forfietedExpired());
		assertEquals(0, b.available(NOW));
		assertEquals(0, b.size());
	}

	@Test
	void purgeOnlyRemovesExpired() {
		TimedBatches b = new TimedBatches();
		b.add(70, NOW - 1);
		b.add(30, NOW + 1);
		assertEquals(70, b.purgeExpired(NOW));
		assertEquals(1, b.size());
		assertEquals(30, b.available(NOW));
	}

	@Test
	void permanentNeverExpires() {
		TimedBatches b = new TimedBatches();
		b.add(123, 0);
		assertEquals(123, b.available(Long.MAX_VALUE - 1));
		assertTrue(b.expiredAmount(Long.MAX_VALUE - 1) == 0);
	}

	@Test
	void noticeOnlyWhenSomethingExpired() {
		TimedBatches b = new TimedBatches();
		b.add(50, NOW - HOUR);
		b.add(100, 0);
		TimedBatches.Spend s = b.spend(100, NOW);
		assertEquals("economy.timed.expired", TimedBatches.noticeKey(s));

		TimedBatches clean = new TimedBatches();
		clean.add(100, 0);
		assertEquals(null, TimedBatches.noticeKey(clean.spend(10, NOW)));
	}

	@Test
	void insufficientSpendLeavesBalanceUntouched() {
		TimedBatches b = new TimedBatches();
		b.add(10, NOW + HOUR);
		assertEquals(0, b.spend(11, NOW).spent());
		assertEquals(10, b.available(NOW));
	}
}
