package com.akira.redtale.economy.shop;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Q4：单日累计改价上限的判定（纯函数）。 */
class ShopPriceGuardTest {

	@Test
	void exceedWhenOverPercent() {
		assertTrue(ShopCatalog.dailyExceeded(100, 150, 20));   // +50%
		assertTrue(ShopCatalog.dailyExceeded(100, 50, 20));    // -50%
	}

	@Test
	void boundaryIsInclusive() {
		// 恰好 20% 不算超（判定用 >）
		assertFalse(ShopCatalog.dailyExceeded(100, 120, 20));
		assertFalse(ShopCatalog.dailyExceeded(100, 80, 20));
	}

	@Test
	void withinPercentPasses() {
		assertFalse(ShopCatalog.dailyExceeded(100, 110, 20));
		assertFalse(ShopCatalog.dailyExceeded(1000, 1050, 20));
	}

	@Test
	void zeroOrNegativePercentDisables() {
		assertFalse(ShopCatalog.dailyExceeded(100, 100000, 0));
		assertFalse(ShopCatalog.dailyExceeded(100, 100000, -5));
	}

	@Test
	void zeroBaseDisables() {
		assertFalse(ShopCatalog.dailyExceeded(0, 999, 20));
	}

	@Test
	void bigNumbersDoNotOverflow() {
		long base = 1_000_000_000L;
		assertFalse(ShopCatalog.dailyExceeded(base, base, 10));
		assertTrue(ShopCatalog.dailyExceeded(base, base * 2, 10));
	}
}
