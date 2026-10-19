package com.akira.redtale.economy.economy;

import com.akira.redtale.economy.config.EconomyConfig;
import com.akira.redtale.economy.lang.Lang;

/** 币值换算与格式化（1 金 = N 银 = M 铜，全部来自 economy.json）。 */
public final class Currency {

	private Currency() {
	}

	public static long silverInCopper() {
		return Math.max(1, EconomyConfig.silverInCopper());
	}

	public static long goldInCopper() {
		return Math.max(1, EconomyConfig.goldInCopper());
	}

	public static String format(long raw) {
		long copper = Math.max(0, raw);
		long gold = copper / goldInCopper();
		long rest = copper % goldInCopper();
		long silver = rest / silverInCopper();
		long c = rest % silverInCopper();

		if (copper == 0) return Lang.t("currency.zero");

		String style = EconomyConfig.format();
		return switch (style) {
			case "symbol" -> Lang.t("currency.symbol", gold, silver, c);
			case "short" -> Lang.t("currency.short", gold, silver, c);
			default -> Lang.t("currency.name", gold, silver, c);
		};
	}

	/**
	 * 解析金额：纯数字视为铜；支持 {@code 1g20s5c} / {@code 3s} / {@code 2g}。
	 *
	 * @return 铜值；解析失败返回 -1
	 */
	public static long parse(String input) {
		if (input == null || input.isBlank()) return -1;
		String s = input.trim().toLowerCase();
		try {
			if (s.chars().allMatch(Character::isDigit)) {
				return Long.parseLong(s);
			}
			long total = 0;
			long num = -1;
			boolean any = false;
			for (char ch : s.toCharArray()) {
				if (Character.isDigit(ch)) {
					num = (num < 0 ? 0 : num) * 10 + (ch - '0');
					continue;
				}
				if (num < 0) return -1;
				switch (ch) {
					case 'g', '金' -> total += num * goldInCopper();
					case 's', '银' -> total += num * silverInCopper();
					case 'c', '铜' -> total += num;
					default -> {
						return -1;
					}
				}
				num = -1;
				any = true;
			}
			if (num >= 0) total += num; // 末尾裸数字按铜算
			return any || num >= 0 ? total : -1;
		} catch (NumberFormatException e) {
			return -1;
		}
	}
}
