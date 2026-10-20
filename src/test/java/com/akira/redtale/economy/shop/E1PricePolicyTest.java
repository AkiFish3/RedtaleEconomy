package com.akira.redtale.economy.shop;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * E1：价格护栏纯函数（不读配置，直接给 margin）。
 *
 * <p>覆盖四个易踩点：卖价等于买价要拦、margin=0 要严格小于、ratio≥上限要压回、
 * 买价 0 时不许把卖价钳成 0（那会让本来能卖的东西变成不能卖）。
 */
class E1PricePolicyTest {

	@Test
	void maxSellWithFivePercentMargin() {
		assertEquals(95, PricePolicy.maxSellFor(100, 5));
		assertEquals(9, PricePolicy.maxSellFor(10, 5));      // 10*95/100=9.5 → 向下取整
	}

	@Test
	void maxSellWithZeroMarginIsStrictlyBelow() {
		assertEquals(99, PricePolicy.maxSellFor(100, 0));
		assertEquals(0, PricePolicy.maxSellFor(1, 0));
	}

	@Test
	void sellEqualToBuyIsRejected() {
		assertTrue(PricePolicy.violates(100, 100, 5));
		assertTrue(PricePolicy.violates(100, 99, 5));
		assertFalse(PricePolicy.violates(100, 95, 5));
		assertFalse(PricePolicy.violates(100, 1, 5));
	}

	@Test
	void sellHigherThanBuyIsRejected() {
		// 这就是「买低卖高刷钱」的核心场景
		assertTrue(PricePolicy.violates(100, 120, 5));
		assertTrue(PricePolicy.violates(100, 120, 0));
	}

	@Test
	void clampSellNeverExceedsCap() {
		assertEquals(95, PricePolicy.clampSell(100, 500, 5));
		assertEquals(60, PricePolicy.clampSell(100, 60, 5));
		assertEquals(60, PricePolicy.clampSell(0, 60, 5));   // 没有买价就不钳（卖价原样保留）
	}

	@Test
	void ratioIsCappedBelowOne() {
		assertEquals(0.95, PricePolicy.clampRatio(1.5, 5), 1e-9);
		assertEquals(0.3, PricePolicy.clampRatio(0.3, 5), 1e-9);
		assertEquals(1.0, PricePolicy.clampRatio(1.0, 0), 1e-9);   // margin=0 → cap=1，交给 violates 兜
	}
}
