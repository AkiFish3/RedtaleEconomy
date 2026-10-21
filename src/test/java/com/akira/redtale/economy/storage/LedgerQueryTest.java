package com.akira.redtale.economy.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** T16 流水查询：类型分桶 / 方向 / 玩家 / 排序 / 截断前计数。 */
class LedgerQueryTest {

	private static final UUID ALICE = UUID.fromString("11111111-1111-1111-1111-111111111111");
	private static final UUID BOB = UUID.fromString("22222222-2222-2222-2222-222222222222");

	private static String line(LocalDate d, UUID who, String type, long delta, long after) {
		return "{\"ts\":\"" + d + "T10:00:00\",\"uuid\":\"" + who + "\",\"delta\":" + delta
				+ ",\"after\":" + after + ",\"type\":\"" + type + "\",\"ref\":\"\",\"server\":\"test\"}";
	}

	private static Path writeDay(Path root, LocalDate d, List<String> lines) throws Exception {
		Path f = Ledger.fileFor(d, "daily", root);
		Files.createDirectories(f.getParent());
		Files.write(f, lines, StandardCharsets.UTF_8);
		return f;
	}

	@Test
	void typeBucketsMatchByPrefixOnly() {
		assertTrue(LedgerQuery.matchType("shop.buy", ""));
		assertTrue(LedgerQuery.matchType("shop.buy", "shop"));
		assertTrue(LedgerQuery.matchType("shop", "shop"));
		// 前缀必须是整段：shopX 不算 shop
		assertFalse(LedgerQuery.matchType("shopX.buy", "shop"));
		assertFalse(LedgerQuery.matchType("market.buy", "shop"));
		// 「其它」= 落到前几桶之外
		assertTrue(LedgerQuery.matchType("checkin", "!"));
		assertFalse(LedgerQuery.matchType("shop.buy", "!"));
		assertFalse(LedgerQuery.matchType("market.buy", "!"));
	}

	@Test
	void daysOfClampsOutOfRange() {
		assertEquals(1, LedgerQuery.daysOf(0));
		assertEquals(30, LedgerQuery.daysOf(2));
		assertEquals(1, LedgerQuery.daysOf(-3));
		assertEquals(1, LedgerQuery.daysOf(99));
	}

	@Test
	void queryFiltersByPlayerDirAndType(@TempDir Path tmp) throws Exception {
		LocalDate today = LocalDate.of(2026, 10, 21);
		writeDay(tmp, today, List.of(
				line(today, ALICE, "shop.buy", -100, 900),
				line(today, ALICE, "task.claim", 50, 950),
				line(today, BOB, "shop.sell", 30, 130),
				line(today, ALICE, "checkin", 10, 960)));

		// 只看 alice：3 笔；只看支出：1 笔（-100）
		LedgerQuery.Result all = LedgerQuery.query(tmp, "daily", today, 1, 0, 0, ALICE, 32);
		assertEquals(3, all.total());
		LedgerQuery.Result out = LedgerQuery.query(tmp, "daily", today, 1, 2, 0, ALICE, 32);
		assertEquals(1, out.total());
		assertEquals(-100, out.rows().get(0).delta());
		// 收入 + 商店桶：alice 没有 shop 收入 ⇒ 0
		assertEquals(0, LedgerQuery.query(tmp, "daily", today, 1, 1, 1, ALICE, 32).total());
		// 其它桶：checkin 命中
		LedgerQuery.Result other = LedgerQuery.query(tmp, "daily", today, 1, 0, 4, ALICE, 32);
		assertEquals(1, other.total());
		assertEquals("checkin", other.rows().get(0).type());
		// who=null 表示全服
		assertEquals(4, LedgerQuery.query(tmp, "daily", today, 1, 0, 0, null, 32).total());
	}

	@Test
	void totalCountsBeforeTruncationAndOrderIsNewestFirst(@TempDir Path tmp) throws Exception {
		LocalDate today = LocalDate.of(2026, 10, 21);
		List<String> lines = new java.util.ArrayList<>();
		for (int i = 0; i < 10; i++) {
			lines.add("{\"ts\":\"2026-10-21T10:0" + i + ":00\",\"uuid\":\"" + ALICE
					+ "\",\"delta\":1,\"after\":" + i + ",\"type\":\"shop.buy\",\"ref\":\"\"}");
		}
		writeDay(tmp, today, lines);
		LedgerQuery.Result r = LedgerQuery.query(tmp, "daily", today, 1, 0, 0, ALICE, 3);
		assertEquals(10, r.total(), "total 必须是截断前的匹配数，界面要显示「共 N 笔」");
		assertEquals(3, r.rows().size());
		assertEquals("2026-10-21T10:09:00", r.rows().get(0).ts(), "倒序：最新的在最前");
	}

	@Test
	void brokenLinesAreSkippedNotThrown(@TempDir Path tmp) throws Exception {
		LocalDate today = LocalDate.of(2026, 10, 21);
		writeDay(tmp, today, List.of("{\"ts\":\"2026-10-21T10:00:00\"", // 半行：进程被杀
				line(today, ALICE, "shop.buy", -5, 5)));
		LedgerQuery.Result r = LedgerQuery.query(tmp, "daily", today, 1, 0, 0, ALICE, 32);
		assertEquals(1, r.total());
	}

	@Test
	void missingDirIsEmptyNotCrash(@TempDir Path tmp) {
		LedgerQuery.Result r = LedgerQuery.query(tmp.resolve("nope"), "daily",
				LocalDate.of(2026, 10, 21), 7, 0, 0, ALICE, 32);
		assertEquals(0, r.total());
		assertTrue(r.rows().isEmpty());
	}
}
