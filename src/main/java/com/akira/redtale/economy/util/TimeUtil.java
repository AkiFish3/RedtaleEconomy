package com.akira.redtale.economy.util;

import com.akira.redtale.economy.RedtaleEconomy;
import com.akira.redtale.economy.config.CoreConfig;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * 时间工具：统一走 {@code core.json → timezone}（server = 跟随系统时区）。
 *
 * <p>所有跨天判定都以「服务器时区的业务日」为准：超过 {@code resetHour} 才算新的一天，
 * 这样运营把 resetHour 设成 4 点时，凌晨 2 点仍算前一天。
 */
public final class TimeUtil {

	private static final DateTimeFormatter ISO_DATE = DateTimeFormatter.ISO_LOCAL_DATE;
	private static ZoneId cached;
	private static String cachedName;

	private TimeUtil() {
	}

	public static ZoneId zone() {
		String name = CoreConfig.timezone();
		if (cached != null && name.equals(cachedName)) return cached;
		ZoneId z;
		if (name == null || name.isBlank() || "server".equalsIgnoreCase(name)) {
			z = ZoneId.systemDefault();
		} else {
			try {
				z = ZoneId.of(name);
			} catch (Exception e) {
				RedtaleEconomy.LOGGER.warn("[time] 无效时区 \"{}\"，回落到系统时区", name);
				z = ZoneId.systemDefault();
			}
		}
		cached = z;
		cachedName = name;
		return z;
	}

	public static LocalDateTime now() {
		return LocalDateTime.now(zone());
	}

	/** 业务日：{@code resetHour} 之前算前一天。 */
	public static LocalDate businessDate(int resetHour) {
		return businessDate(now(), resetHour);
	}

	/** 纯函数版本（可单测）：给定时刻算业务日。 */
	public static LocalDate businessDate(LocalDateTime at, int resetHour) {
		LocalDate d = at.toLocalDate();
		return at.getHour() < clampHour(resetHour) ? d.minusDays(1) : d;
	}

	public static String today(int resetHour) {
		return businessDate(resetHour).format(ISO_DATE);
	}

	public static String today() {
		return LocalDate.now(zone()).format(ISO_DATE);
	}

	/** 解析业务日字符串；坏数据当作「很久以前」，让签到逻辑按断签处理而不是崩。 */
	public static LocalDate parseDate(String s) {
		if (s == null || s.isBlank()) return LocalDate.MIN;
		try {
			return LocalDate.parse(s, ISO_DATE);
		} catch (DateTimeParseException e) {
			RedtaleEconomy.LOGGER.warn("[time] 无法解析日期 \"{}\"，按最小日期处理", s);
			return LocalDate.MIN;
		}
	}

	/** 展示用时间戳（流水、快照共用）。 */
	public static String nowIso() {
		return now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"));
	}

	private static int clampHour(int h) {
		return Math.max(0, Math.min(23, h));
	}
}
