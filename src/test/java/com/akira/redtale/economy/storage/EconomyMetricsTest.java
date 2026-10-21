package com.akira.redtale.economy.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** T07：经济指标口径（产出/消耗/净增/通胀）与流水文件定位。 */
class EconomyMetricsTest {

	@TempDir
	Path root;

	private void write(LocalDate day, String... lines) throws Exception {
		Path f = Ledger.fileFor(day, "daily", root);
		Files.createDirectories(f.getParent());
		Files.writeString(f, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
	}

	private static String tx(long delta, String type) {
		return "{\"ts\":\"2026-10-21T12:00:00\",\"uuid\":\"11111111-1111-1111-1111-111111111111\","
				+ "\"delta\":" + delta + ",\"after\":0,\"type\":\"" + type + "\",\"ref\":\"\"}";
	}

	@Test
	void mintsAndBurnsAreSeparated() throws Exception {
		LocalDate today = LocalDate.of(2026, 10, 21);
		write(today, tx(500, "checkin"), tx(-200, "shop.buy"), tx(50, "task.claim"));
		EconomyMetrics.Totals t = EconomyMetrics.window(root, "daily", today, 1);
		assertEquals(550, t.minted());
		assertEquals(200, t.burned());
		assertEquals(350, t.net());
		assertEquals(3, t.tx());
		assertEquals(0, t.skipped());
		assertEquals("checkin", t.mintedByType().get(0).getKey(), "产出按金额降序");
		assertEquals(1, t.burnedByType().size());
	}

	@Test
	void transferCancelsOutInNet() throws Exception {
		LocalDate today = LocalDate.of(2026, 10, 21);
		write(today, tx(-1000, "pay"), tx(1000, "pay"));
		EconomyMetrics.Totals t = EconomyMetrics.window(root, "daily", today, 1);
		assertEquals(1000, t.minted());
		assertEquals(1000, t.burned());
		assertEquals(0, t.net(), "玩家间转账不该改变净增");
	}

	@Test
	void brokenLinesAreCountedNotThrown() throws Exception {
		LocalDate today = LocalDate.of(2026, 10, 21);
		write(today, tx(100, "checkin"), "{\"delta\":", "", "not json");
		EconomyMetrics.Totals t = EconomyMetrics.window(root, "daily", today, 1);
		assertEquals(100, t.minted());
		assertEquals(1, t.tx());
		assertEquals(2, t.skipped());
	}

	@Test
	void windowOnlyCoversRequestedDays() throws Exception {
		LocalDate today = LocalDate.of(2026, 10, 21);
		write(today, tx(100, "checkin"));
		write(today.minusDays(2), tx(999, "checkin"));
		assertEquals(100, EconomyMetrics.window(root, "daily", today, 1).minted());
		assertEquals(1099, EconomyMetrics.window(root, "daily", today, 3).minted());
	}

	@Test
	void monthlyGranularityUsesOneFile() throws Exception {
		LocalDate today = LocalDate.of(2026, 10, 21);
		Path f = Ledger.fileFor(today, "monthly", root);
		Files.createDirectories(f.getParent());
		Files.writeString(f, tx(42, "checkin") + "\n", StandardCharsets.UTF_8);
		assertEquals(42, EconomyMetrics.window(root, "monthly", today, 5).minted());
	}

	@Test
	void missingFilesYieldEmptyTotals() {
		EconomyMetrics.Totals t = EconomyMetrics.window(root, "daily", LocalDate.of(2026, 10, 21), 7);
		assertEquals(0, t.minted());
		assertEquals(0, t.tx());
		assertTrue(t.mintedByType().isEmpty());
	}

	@Test
	void totalBalanceSumsAccountsAndSkipsUnderscore() throws Exception {
		Path acc = root.resolve("accounts");
		Files.createDirectories(acc);
		Files.writeString(acc.resolve("a.json"), "{\"balance\":100}", StandardCharsets.UTF_8);
		Files.writeString(acc.resolve("b.json"), "{\"balance\":250}", StandardCharsets.UTF_8);
		Files.writeString(acc.resolve("_index.json"), "{\"balance\":99999}", StandardCharsets.UTF_8);
		Files.writeString(acc.resolve("bad.json"), "{oops", StandardCharsets.UTF_8);
		assertEquals(350, EconomyMetrics.totalBalance(acc));
		assertEquals(0, EconomyMetrics.totalBalance(root.resolve("nope")));
	}

	@Test
	void inflationAndFormatting() {
		EconomyMetrics.Totals t = new EconomyMetrics.Totals(300, 100, 2, 0, List.of(), List.of());
		assertEquals(0.02, EconomyMetrics.inflation(t, 10_000), 1e-9);
		assertTrue(Double.isNaN(EconomyMetrics.inflation(t, 0)), "总量未知时不假装 0");
		assertEquals("+2.00%", EconomyMetrics.pct(0.02));
		assertEquals("-1.50%", EconomyMetrics.pct(-0.015));
		assertEquals("—", EconomyMetrics.pct(Double.NaN));
	}

	@Test
	void topClampsToAvailable() {
		var list = List.of(Map.entry("a", 10L), Map.entry("b", 5L));
		assertEquals(2, EconomyMetrics.top(list, 5).size());
		assertEquals(1, EconomyMetrics.top(list, 1).size());
		assertTrue(EconomyMetrics.top(List.of(), 3).isEmpty());
	}

	@Test
	void summaryLineMentionsAllThreeNumbers() {
		EconomyMetrics.Totals t = new EconomyMetrics.Totals(120, 20, 3, 0, List.of(), List.of());
		String line = EconomyMetrics.line("今日", t, 0);
		assertTrue(line.contains("+120"));
		assertTrue(line.contains("-20"));
		assertTrue(line.contains("+100"));
		assertFalse(line.contains("NaN"));
	}
}
