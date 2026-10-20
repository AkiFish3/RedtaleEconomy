package com.akira.redtale.economy.market;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R40：市场费率与分页的纯逻辑。
 *
 * <p>重点防两类事故：
 * <ul>
 *   <li>抽成向上取整失效 → 1 铜的单子白挂（fee=0）或卖家倒欠钱；</li>
 *   <li>分页钳制缺失 → 空市场请求第 3 页时越界（服务端算 entry 下标直接抛）。</li>
 * </ul>
 */
class MarketRulesTest {

	@Test
	void listingFeeRoundsUp() {
		assertEquals(1, MarketRules.listingFee(1, 1.0), "1 铜 × 1% 也要收 1 铜");
		assertEquals(1, MarketRules.listingFee(50, 1.0));
		assertEquals(0, MarketRules.listingFee(0, 5.0));
		assertEquals(0, MarketRules.listingFee(100, 0));
	}

	@Test
	void commissionNeverExceedsPrice() {
		assertEquals(0, MarketRules.commission(0, 5.0));
		assertEquals(5, MarketRules.commission(100, 5.0));
		assertEquals(100, MarketRules.commission(100, 100.0), "100% 佣金不能超过货款");
		assertEquals(100, MarketRules.commission(100, 500.0), "配置写歪了也不能超");
	}

	@Test
	void sellerProceedsSubtractsCommission() {
		assertEquals(95, MarketRules.sellerProceeds(100, 5.0));
		assertEquals(0, MarketRules.sellerProceeds(100, 500.0));
		assertEquals(100, MarketRules.sellerProceeds(100, 0));
	}

	@Test
	void priceRange() {
		assertTrue(MarketRules.priceInRange(1, 1, 100));
		assertTrue(MarketRules.priceInRange(100, 1, 100));
		assertFalse(MarketRules.priceInRange(0, 1, 100));
		assertFalse(MarketRules.priceInRange(101, 1, 100));
	}

	@Test
	void expiryUsesZeroAsNever() {
		assertFalse(MarketRules.expired(0, Long.MAX_VALUE), "expireAt=0 表示永不过期");
		assertFalse(MarketRules.expired(1_000, 999));
		assertTrue(MarketRules.expired(1_000, 1_000));
	}

	@Test
	void pagingClamps() {
		assertEquals(0, MarketRules.totalPages(0, 18));
		assertEquals(1, MarketRules.totalPages(1, 18));
		assertEquals(2, MarketRules.totalPages(19, 18));
		assertEquals(0, MarketRules.clampPage(5, 0, 18), "空列表只能落在第 0 页");
		assertEquals(0, MarketRules.clampPage(-3, 100, 18));
		assertEquals(5, MarketRules.clampPage(99, 100, 18), "99 → 最后一页 5");
	}


	// ---------------------------------------------------------------- R41 拍卖

	@Test
	void firstBidStartsAtStartPrice() {
		assertEquals(100, MarketRules.minNextBid(0, 100, 10.0));
		assertEquals(1, MarketRules.minNextBid(0, 0, 10.0), "起拍价 0 时最低也要 1 铜");
	}

	@Test
	void nextBidRoundsUpAndAlwaysIncreases() {
		assertEquals(110, MarketRules.minNextBid(100, 100, 10.0));
		assertEquals(103, MarketRules.minNextBid(100, 100, 2.5), "2.5% 向上取整 = 3");
		assertEquals(101, MarketRules.minNextBid(100, 100, 0.5), "不足 1 铜也要 +1");
		assertEquals(2, MarketRules.minNextBid(1, 1, 5.0));
	}

	@Test
	void snipeWindowExtendsFromNow() {
		long now = 1_000_000L;
		long window = 2 * 60_000L;
		long extend = 2 * 60_000L;
		assertFalse(MarketRules.shouldExtend(now, now + 10 * 60_000L, window));
		assertTrue(MarketRules.shouldExtend(now, now + 60_000L, window));
		assertEquals(now + extend, MarketRules.extendedExpireAt(now, now + 60_000L, extend));
		// 原到期时间更晚时不能被缩短
		assertEquals(now + 10 * 60_000L,
				MarketRules.extendedExpireAt(now, now + 10 * 60_000L, extend));
	}

	@Test
	void remainingTextIsHumanReadable() {
		assertEquals("永久", MarketRules.remainingText(-1));
		assertEquals("<1 分钟", MarketRules.remainingText(30_000));
		assertEquals("3 分钟", MarketRules.remainingText(180_000));
		assertEquals("2 小时", MarketRules.remainingText(2 * 3600_000L));
		assertEquals("3 天", MarketRules.remainingText(3L * 24 * 3600_000L));
	}
}
