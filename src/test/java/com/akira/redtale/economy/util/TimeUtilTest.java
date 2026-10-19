package com.akira.redtale.economy.util;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 业务日切换规则（resetHour 之前算前一天）——纯函数部分，不依赖配置。 */
class TimeUtilTest {

	private static LocalDateTime at(int h, int m) {
		return LocalDateTime.of(2026, 10, 20, h, m);
	}

	@Test
	void beforeResetHourBelongsToPreviousDay() {
		assertEquals(LocalDate.of(2026, 10, 19), TimeUtil.businessDate(at(2, 30), 4));
		assertEquals(LocalDate.of(2026, 10, 19), TimeUtil.businessDate(at(3, 59), 4));
	}

	@Test
	void atOrAfterResetHourIsNewDay() {
		assertEquals(LocalDate.of(2026, 10, 20), TimeUtil.businessDate(at(4, 0), 4));
		assertEquals(LocalDate.of(2026, 10, 20), TimeUtil.businessDate(at(23, 59), 4));
	}

	@Test
	void zeroResetHourNeverShiftsBack() {
		assertEquals(LocalDate.of(2026, 10, 20), TimeUtil.businessDate(at(0, 0), 0));
		assertEquals(LocalDate.of(2026, 10, 20), TimeUtil.businessDate(at(0, 1), 0));
	}

	@Test
	void hourIsClampedToValidRange() {
		assertEquals(LocalDate.of(2026, 10, 19), TimeUtil.businessDate(at(22, 0), 99));
		assertEquals(LocalDate.of(2026, 10, 20), TimeUtil.businessDate(at(1, 0), -5));
	}

	@Test
	void parseDateFallsBackToMinOnGarbage() {
		assertEquals(LocalDate.of(2026, 10, 20), TimeUtil.parseDate("2026-10-20"));
		assertEquals(LocalDate.MIN, TimeUtil.parseDate(""));
		assertEquals(LocalDate.MIN, TimeUtil.parseDate("2026/10/20"));
	}
}
