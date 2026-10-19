package com.akira.redtale.economy.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** B07：流水按天/按月滚动的文件名规则（纯逻辑，不依赖配置与游戏运行时）。 */
class LedgerPathTest {

	@TempDir
	Path root;

	@Test
	void dailyRollsNewFilePerDay() {
		Path a = Ledger.fileFor(LocalDate.of(2026, 10, 20), "daily", root);
		Path b = Ledger.fileFor(LocalDate.of(2026, 10, 21), "daily", root);
		assertEquals(root.resolve("2026-10").resolve("2026-10-20.jsonl"), a);
		assertEquals(root.resolve("2026-10").resolve("2026-10-21.jsonl"), b);
	}

	@Test
	void dailyCrossesMonthIntoNewDir() {
		Path nov = Ledger.fileFor(LocalDate.of(2026, 11, 1), "daily", root);
		assertEquals(root.resolve("2026-11").resolve("2026-11-01.jsonl"), nov);
	}

	@Test
	void monthlyRollsOneFilePerMonth() {
		Path oct1 = Ledger.fileFor(LocalDate.of(2026, 10, 1), "monthly", root);
		Path oct31 = Ledger.fileFor(LocalDate.of(2026, 10, 31), "monthly", root);
		assertEquals(root.resolve("2026-10.jsonl"), oct1);
		assertEquals(oct1, oct31);
	}

	@Test
	void monthlyCrossesYear() {
		Path jan = Ledger.fileFor(LocalDate.of(2027, 1, 1), "monthly", root);
		assertEquals(root.resolve("2027-01.jsonl"), jan);
	}

	@Test
	void nullRootIsSafe() {
		assertNull(Ledger.fileFor(LocalDate.of(2026, 10, 20), "daily", null));
	}
}
