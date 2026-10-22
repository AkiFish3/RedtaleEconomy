package com.akira.redtale.economy.module.impl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * T64：负声望加税/协会折扣的取整口径。
 *
 * <p>实测缺陷：{@code 100 * 1.15} 在 double 下是 {@code 114.99999999999999}，
 * 直接 {@code floor} 会按 114 收（少收 1 铜）。{@link GuildModule#priceAt} 加 1e-6 抵消。
 */
class GuildPriceTest {

	@Test
	void severeTaxRoundsUpToExact() {
		// 重度 -15% 加税：100 → 115（不是 114）
		assertEquals(115, GuildModule.priceAt(100, 1.15));
		assertEquals(1150, GuildModule.priceAt(1000, 1.15));
		assertEquals(115, GuildModule.priceAt(100, 1.0 + 0.15));
	}

	@Test
	void lightTaxFloorsFractions() {
		// 轻度 +5%：50*1.05=52.5 → 52（不多收），100*1.05=105
		assertEquals(52, GuildModule.priceAt(50, 1.05));
		assertEquals(105, GuildModule.priceAt(100, 1.05));
		assertEquals(53, GuildModule.priceAt(51, 1.05));
	}

	@Test
	void discountStillFloors() {
		assertEquals(96, GuildModule.priceAt(100, 0.96));
		assertEquals(98, GuildModule.priceAt(100, 0.98));
		assertEquals(850, GuildModule.priceAt(1000, 0.85));
	}

	@Test
	void neverBelowOneCopper() {
		assertEquals(1, GuildModule.priceAt(1, 1.15));
		assertEquals(1, GuildModule.priceAt(1, 0.5));
	}

	@Test
	void degenerateInputsArePassThrough() {
		assertEquals(0, GuildModule.priceAt(0, 1.15));
		assertEquals(-5, GuildModule.priceAt(-5, 1.15));
		assertEquals(100, GuildModule.priceAt(100, 0.0));
		assertEquals(100, GuildModule.priceAt(100, Double.NaN));
	}
}
