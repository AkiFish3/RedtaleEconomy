package com.akira.redtale.economy.config;

import java.util.List;

/** {@code config/redtale/economy.json} —— 币值换算、provider 选择、/pay 规则。 */
public final class EconomyConfig {

	public static final String FILE = "economy.json";

	public static final ConfigSchema SCHEMA = ConfigSchema.obj("经济基础设置")
			.field("provider", ConfigSchema.choice("auto", List.of("auto", "numismatic", "internal"),
					"余额来源：auto=装了 Numismatic 就用它，否则用本 mod 的 JSON；切换需 /redtale migrate provider"))
			.field("currency", ConfigSchema.obj("币值换算（内部统一以「铜」为最小单位存储）")
					.field("silverInCopper", ConfigSchema.integer(100, 1, 100_000_000,
							"1 银 = 多少铜"))
					.field("goldInCopper", ConfigSchema.integer(10000, 1, 1_000_000_000,
							"1 金 = 多少铜（应等于 silverInCopper × 每金含银数）"))
					.field("format", ConfigSchema.choice("name", List.of("name", "symbol", "short"),
							"显示格式：name=「3 金 20 银 5 铜」symbol=「3金20银5铜」short=「3g 20s 5c」")))
			.field("startingBalance", ConfigSchema.integer(0, 0, 1_000_000_000_000L,
					"新玩家初始余额（铜）"))
			.field("pay", ConfigSchema.obj("/pay 转账")
					.field("enabled", ConfigSchema.bool(true, "是否允许玩家之间转账"))
					.field("min", ConfigSchema.integer(1, 1, 1_000_000_000_000L, "单笔最小金额（铜）"))
					.field("max", ConfigSchema.integer(1_000_000, 1, 1_000_000_000_000L, "单笔最大金额（铜）"))
					.field("taxPercent", ConfigSchema.decimal(0.0, 0.0, 100.0, "转账税率（%，向上取整，税从收款方扣除）"))
					.field("cooldownSeconds", ConfigSchema.integer(0, 0, 86400, "两次转账最小间隔（秒），0=无限制"))
					.field("allowSelf", ConfigSchema.bool(false, "是否允许给自己转账（仅测试用）")))
			.field("migrate", ConfigSchema.obj("provider 迁移")
					.field("requireConfirm", ConfigSchema.bool(true,
							"迁移必须二次确认（/redtale migrate provider 后跟 confirm）。"
									+ "★当前命令结构已强制 confirm（不带 confirm 只做预检），"
									+ "把本项改 false 不会跳过确认 ⇒ 暂不生效★")));

	private EconomyConfig() {
	}

	public static ConfigStore store() {
		return ConfigManager.get().store(FILE);
	}

	public static String provider() {
		return store().getString("provider", "auto");
	}

	public static long silverInCopper() {
		return store().getPathLong("currency.silverInCopper", 100);
	}

	public static long goldInCopper() {
		return store().getPathLong("currency.goldInCopper", 10000);
	}

	public static String format() {
		return store().getPathString("currency.format", "name");
	}

	public static long startingBalance() {
		return store().getLong("startingBalance", 0);
	}

	public static boolean payEnabled() {
		return store().getPathBool("pay.enabled", true);
	}

	public static long payMin() {
		return store().getPathLong("pay.min", 1);
	}

	public static long payMax() {
		return store().getPathLong("pay.max", 1_000_000);
	}

	public static double payTaxPercent() {
		return store().getPath("pay.taxPercent") == null ? 0.0 : store().getPath("pay.taxPercent").getAsDouble();
	}

	public static int payCooldownSeconds() {
		return (int) store().getPathLong("pay.cooldownSeconds", 0);
	}

	public static boolean payAllowSelf() {
		return store().getPathBool("pay.allowSelf", false);
	}
}
