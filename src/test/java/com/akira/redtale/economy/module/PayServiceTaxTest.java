package com.akira.redtale.economy.module;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 转账税率（纯函数）。
 *
 * <p>税率从「收款方」扣，向上取整——这决定了玩家实际到账多少，
 * 也是界面路径以前完全绕过的那部分逻辑（{@code PayNetworking} 自己算 0 税）。
 */
class PayServiceTaxTest {

	@Test
	void zeroPercentMeansNoTax() {
		assertEquals(0L, PayService.taxOf(1_000_000L, 0.0));
	}

	@Test
	void roundsUpToWholeCopper() {
		assertEquals(5L, PayService.taxOf(100L, 5.0));
		// 101 × 5% = 5.05 → 6（向上取整，不四舍五入）
		assertEquals(6L, PayService.taxOf(101L, 5.0));
		assertEquals(1L, PayService.taxOf(1L, 1.0));
	}

	@Test
	void taxNeverExceedsAmount() {
		assertEquals(50L, PayService.taxOf(50L, 100.0));
		assertEquals(50L, PayService.taxOf(50L, 250.0));
	}

	@Test
	void invalidInputsAreZero() {
		assertEquals(0L, PayService.taxOf(0L, 5.0));
		assertEquals(0L, PayService.taxOf(-100L, 5.0));
		assertEquals(0L, PayService.taxOf(100L, -1.0));
	}
}
