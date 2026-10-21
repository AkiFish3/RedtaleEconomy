package com.akira.redtale.economy.storage;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** T27：事件日历解析与倍率（纯函数，不读全局配置）。 */
class EconomyCalendarTest {

	private static JsonArray arr(String... objects) {
		return JsonParser.parseString("[" + String.join(",", objects) + "]").getAsJsonArray();
	}

	private static final LocalDateTime AT = LocalDateTime.of(2026, 10, 21, 20, 0);

	@Test
	void dateOnlyWindowIncludesWholeEndDay() {
		var a = arr("{\"id\":\"x\",\"name\":\"双倍\",\"from\":\"2026-10-21\",\"to\":\"2026-10-21\","
				+ "\"multiplier\":2.0,\"scopes\":[\"battlepass\"]}");
		var hit = EconomyCalendar.at(a, "battlepass", AT);
		assertTrue(hit.active());
		assertEquals(2.0, hit.multiplier());
		assertEquals("双倍", hit.name());
		assertFalse(EconomyCalendar.at(a, "battlepass", LocalDateTime.of(2026, 10, 22, 0, 0, 1)).active(),
				"to 当天 23:59:59 之后应失效");
	}

	@Test
	void isoDateTimeBounds() {
		var a = arr("{\"id\":\"y\",\"from\":\"2026-10-21T21:00\",\"to\":\"2026-10-21T22:00\","
				+ "\"multiplier\":1.5}");
		assertFalse(EconomyCalendar.at(a, "shop", AT).active(), "20:00 尚未开始");
		assertTrue(EconomyCalendar.at(a, "shop", LocalDateTime.of(2026, 10, 21, 21, 30)).active());
	}

	@Test
	void scopeFiltersAndEmptyMeansAll() {
		var a = arr("{\"id\":\"s\",\"from\":\"2026-10-01\",\"to\":\"2026-10-31\",\"multiplier\":2.0,"
						+ "\"scopes\":[\"CHECKIN\"]}",
				"{\"id\":\"g\",\"from\":\"2026-10-01\",\"to\":\"2026-10-31\",\"multiplier\":1.2}");
		assertEquals(2.0, EconomyCalendar.at(a, "checkin", AT).multiplier(), "scope 大小写不敏感");
		assertEquals(1.2, EconomyCalendar.at(a, "shop", AT).multiplier(), "空 scopes = 全部生效");
	}

	@Test
	void overlapTakesMaxNotProduct() {
		var a = arr("{\"id\":\"a\",\"from\":\"2026-10-01\",\"to\":\"2026-10-31\",\"multiplier\":2.0,"
						+ "\"scopes\":[\"battlepass\"]}",
				"{\"id\":\"b\",\"from\":\"2026-10-20\",\"to\":\"2026-10-25\",\"multiplier\":3.0,"
						+ "\"scopes\":[\"battlepass\"]}");
		assertEquals(3.0, EconomyCalendar.at(a, "battlepass", AT).multiplier(), "重叠取最大，不叠乘");
	}

	@Test
	void invalidEntriesAreSkippedNotFatal() {
		var a = arr("{\"id\":\"bad1\",\"from\":\"2026-13-40\",\"to\":\"2026-10-31\",\"multiplier\":2.0}",
				"{\"id\":\"bad2\",\"from\":\"2026-10-31\",\"to\":\"2026-10-01\",\"multiplier\":2.0}",
				"{\"id\":\"noop\",\"from\":\"2026-10-01\",\"to\":\"2026-10-31\",\"multiplier\":1.0}",
				"{\"id\":\"ok\",\"from\":\"2026-10-01\",\"to\":\"2026-10-31\",\"multiplier\":1.5}");
		var list = EconomyCalendar.parse(a);
		assertEquals(1, list.size(), "坏日期 / 反向区间 / 倍率 1.0 都应跳过");
		assertEquals("ok", list.get(0).id());
	}

	@Test
	void multiplierIsClampedToSaneCeiling() {
		var a = arr("{\"id\":\"huge\",\"from\":\"2026-10-01\",\"to\":\"2026-10-31\",\"multiplier\":9999}");
		assertEquals(10.0, EconomyCalendar.parse(a).get(0).multiplier(), "上限 10，防配置手滑发穿经济");
	}

	@Test
	void applyOnlyAmplifiesAndRounds() {
		assertEquals(1000L, EconomyCalendar.apply(500, 2.0));
		assertEquals(333L, EconomyCalendar.apply(222, 1.5));
		assertEquals(500L, EconomyCalendar.apply(500, 1.0), "1.0 不变");
		assertEquals(500L, EconomyCalendar.apply(500, 0.5), "小于 1 的倍率不生效（只放大不缩小）");
		assertEquals(0L, EconomyCalendar.apply(0, 2.0));
	}

	@Test
	void noEventMeansOne() {
		assertEquals(1.0, EconomyCalendar.at(new JsonArray(), "shop", AT).multiplier());
		assertFalse(EconomyCalendar.at(new JsonArray(), "shop", AT).active());
		assertEquals(100L, EconomyCalendar.apply(100, EconomyCalendar.at(new JsonArray(), "shop", AT).multiplier()));
	}

	@Test
	void timeParsingRejectsGarbage() {
		assertNull(EconomyCalendar.time("", false));
		assertNull(EconomyCalendar.time("2026/10/21", false));
		assertEquals(LocalDateTime.of(2026, 10, 21, 23, 59, 59),
				EconomyCalendar.time("2026-10-21", true), "结束日按最后一秒");
		assertEquals(LocalDateTime.of(2026, 10, 21, 0, 0), EconomyCalendar.time("2026-10-21", false));
	}
}
