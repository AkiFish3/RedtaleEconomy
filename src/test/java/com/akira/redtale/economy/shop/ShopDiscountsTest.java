package com.akira.redtale.economy.shop;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** T08：折扣窗口的解析、区间口径与价格折算。 */
class ShopDiscountsTest {

	private static long at(int y, int m, int d, int hh, int mm) {
		return LocalDateTime.of(y, m, d, hh, mm).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
	}

	private static ShopDiscounts.Window window(long from, long to, int percent, String... shops) {
		return new ShopDiscounts.Window("w", from, to, null, null, percent,
				List.of(shops), List.of("*"), true);
	}

	@Test
	void rangeIsHalfOpen() {
		long from = at(2026, 10, 24, 0, 0);
		long to = at(2026, 10, 27, 0, 0);
		ShopDiscounts.Window w = window(from, to, 20, "*");
		assertTrue(ShopDiscounts.active(w, from), "起点含");
		assertTrue(ShopDiscounts.active(w, to - 1));
		assertFalse(ShopDiscounts.active(w, to), "终点不含，否则相邻活动会重叠一秒");
		assertFalse(ShopDiscounts.active(w, from - 1));
	}

	@Test
	void wildcardAndShopFilter() {
		long now = at(2026, 10, 25, 12, 0);
		ShopDiscounts.Window onlyGeneral = window(now - 1000, now + 1000, 30, "general");
		assertTrue(onlyGeneral.hitsShop("general"));
		assertFalse(onlyGeneral.hitsShop("weapons"));
		ShopDiscounts.Window all = window(now - 1000, now + 1000, 30, "*");
		assertTrue(all.hitsShop("anything"));
		// 空列表按通配处理
		ShopDiscounts.Window empty = new ShopDiscounts.Window("e", now - 1000, now + 1000, null, null, 5,
				List.of(), List.of(), true);
		assertTrue(empty.hitsShop("x"));
	}

	@Test
	void multipleWindowsTakeBestDiscount() {
		long now = at(2026, 10, 25, 12, 0);
		ShopDiscounts.setForTest(List.of(
				window(now - 1000, now + 1000, 10, "*"),
				window(now - 1000, now + 1000, 25, "*")));
		assertEquals(25, ShopDiscounts.percentFor("s", "i", now));
		assertEquals(75L, ShopDiscounts.applyBuy("s", "i", 100, now), "100 打 25% = 75");
	}

	@Test
	void applyBuyFloorsAtOne() {
		long now = at(2026, 10, 25, 12, 0);
		ShopDiscounts.setForTest(List.of(window(now - 1000, now + 1000, 90, "*")));
		assertEquals(1L, ShopDiscounts.applyBuy("s", "i", 1, now));
		assertEquals(0L, ShopDiscounts.applyBuy("s", "i", 0, now), "不可购买的商品保持 0");
	}

	@Test
	void dailyWindowSupportsCrossMidnight() {
		ShopDiscounts.Window w = new ShopDiscounts.Window("n", Long.MIN_VALUE, Long.MAX_VALUE,
				ShopDiscounts.minuteOfDay("22:00"), ShopDiscounts.minuteOfDay("02:00"), 15,
				List.of("*"), List.of("*"), true);
		assertTrue(ShopDiscounts.active(w, at(2026, 10, 25, 23, 30)));
		assertTrue(ShopDiscounts.active(w, at(2026, 10, 26, 1, 0)));
		assertFalse(ShopDiscounts.active(w, at(2026, 10, 26, 12, 0)));
	}

	@Test
	void parseSkipsBadEntriesButKeepsGoodOnes() {
		List<ShopDiscounts.Window> list = ShopDiscounts.parseForTest("{\"windows\":["
				+ "{\"id\":\"ok\",\"from\":\"2026-10-24T00:00\",\"to\":\"2026-10-27T00:00\",\"percent\":20},"
				+ "{\"id\":\"zero\",\"from\":\"2026-10-24\",\"to\":\"2026-10-27\",\"percent\":0},"
				+ "{\"id\":\"badtime\",\"from\":\"不是日期\",\"to\":\"2026-10-27\",\"percent\":10},"
				+ "{\"id\":\"notime\",\"percent\":10}"
				+ "]}");
		assertEquals(1, list.size(), "0% / 非法日期 / 无时间窗 都要被跳过");
		assertEquals("ok", list.get(0).id());
	}

	@Test
	void percentIsClamped() {
		assertEquals(1, ShopDiscounts.clampPercent(0));
		assertEquals(1, ShopDiscounts.clampPercent(-5));
		assertEquals(90, ShopDiscounts.clampPercent(100));
		assertEquals(50, ShopDiscounts.clampPercent(50));
	}

	@Test
	void noWindowMeansNoDiscount() {
		ShopDiscounts.setForTest(List.of());
		assertEquals(0, ShopDiscounts.percentFor("s", "i", System.currentTimeMillis()));
		assertEquals(123L, ShopDiscounts.applyBuy("s", "i", 123, System.currentTimeMillis()));
	}
}
