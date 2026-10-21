package com.akira.redtale.economy.shop;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** T09：每日轮换的确定性与边界。 */
class ShopRotationTest {

	private static List<String> ids(int n) {
		List<String> out = new ArrayList<>();
		for (int i = 0; i < n; i++) out.add("item_" + i);
		return out;
	}

	private static long at(int y, int m, int d, int hh) {
		return LocalDateTime.of(y, m, d, hh, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
	}

	@Test
	void sameDaySameItemsRegardlessOfInputOrder() {
		List<String> a = ids(20);
		List<String> b = new ArrayList<>(a);
		java.util.Collections.reverse(b);
		Set<String> pa = ShopRotation.pick(a, 20_300L, 8, "s1");
		Set<String> pb = ShopRotation.pick(b, 20_300L, 8, "s1");
		assertEquals(pa, pb, "换顺序不能换选品（否则文件书写顺序会变成变量）");
		assertEquals(8, pa.size());
	}

	@Test
	void nextDayRotates() {
		List<String> pool = ids(30);
		Set<String> d1 = ShopRotation.pick(pool, 20_300L, 8, "s1");
		Set<String> d2 = ShopRotation.pick(pool, 20_301L, 8, "s1");
		assertNotEquals(d1, d2, "第二天必须换一批");
	}

	@Test
	void saltChangesSelection() {
		List<String> pool = ids(30);
		assertNotEquals(ShopRotation.pick(pool, 20_300L, 8, "s1"),
				ShopRotation.pick(pool, 20_300L, 8, "s2"));
	}

	@Test
	void poolSmallerThanCountIsSafe() {
		Set<String> p = ShopRotation.pick(ids(3), 20_300L, 8, "s1");
		assertEquals(3, p.size(), "池子不够就给全部，不能死循环或抛异常");
		assertTrue(ShopRotation.pick(List.of(), 1L, 8, "").isEmpty());
	}

	@Test
	void refreshHourShiftsDayBoundary() {
		// refreshHour=4：03:59 仍算前一天，04:01 算新一天
		long before = at(2026, 10, 25, 3);
		long after = at(2026, 10, 25, 5);
		assertEquals(ShopRotation.dayIndex(before, 4), ShopRotation.dayIndex(at(2026, 10, 24, 20), 4));
		assertNotEquals(ShopRotation.dayIndex(after, 4), ShopRotation.dayIndex(before, 4));
		// refreshHour=0 时按自然日
		assertEquals(ShopRotation.dayIndex(at(2026, 10, 25, 1), 0),
				ShopRotation.dayIndex(at(2026, 10, 25, 23), 0));
	}

	@Test
	void duplicatesDoNotEatSlots() {
		List<String> pool = List.of("a", "a", "b", "b", "c");
		Set<String> p = ShopRotation.pick(pool, 1L, 3, "");
		assertEquals(3, p.size());
		assertFalse(p.contains(null));
	}
}
