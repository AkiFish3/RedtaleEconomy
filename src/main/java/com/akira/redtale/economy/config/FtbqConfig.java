package com.akira.redtale.economy.config;

/**
 * {@code config/redtale/ftbq.json} —— FTB Quests 对接（spec v0.8 独立文件 / R9）。
 *
 * <p>全部开关都在这里：未装 FTB Quests 时 {@code enabled} 无效，不注册任何东西；
 * 装了但 {@code enabled=false} 时同样不注册 reward 类型、不注册 {@code /quests}。
 */
public final class FtbqConfig {

	public static final String FILE = "ftbq.json";

	public static final ConfigSchema SCHEMA = ConfigSchema.obj("FTB Quests 集成（软依赖）")
			.field("enabled", ConfigSchema.bool(true,
					"总开关；false = 不注册 reward 类型与 /quests（FTBQ 侧已有任务不受影响）"))
			.field("registerReward", ConfigSchema.bool(true,
					"注册自定义奖励类型 redtale_economy:money（编辑任务书时可填金额）"))
			.field("showEntry", ConfigSchema.bool(true, "注册 /quests 快捷入口"))
			.field("entryCommand", ConfigSchema.string("ftbquests open",
					"/quests 实际转发的命令；不同 FTBQ 版本命令不同可在这里改"))
			.field("notifyOnClaim", ConfigSchema.bool(true, "领取奖励时给玩家发提示"))
			.field("logClaim", ConfigSchema.bool(true, "领取时写流水（type=ftbq.reward）"))
			.field("rewardCurrency", ConfigSchema.choice("free", java.util.List.of("free", "premium"),
					"奖励进哪个币种（premium 需 premium 模块开启）"));

	private FtbqConfig() {
	}

	public static ConfigStore store() {
		return ConfigManager.get().store(FILE);
	}

	public static boolean enabled() {
		return store().getBool("enabled", true);
	}

	public static boolean registerReward() {
		return store().getBool("registerReward", true);
	}

	public static boolean showEntry() {
		return store().getBool("showEntry", true);
	}

	public static String entryCommand() {
		return store().getString("entryCommand", "ftbquests open");
	}

	public static boolean notifyOnClaim() {
		return store().getBool("notifyOnClaim", true);
	}

	public static boolean logClaim() {
		return store().getBool("logClaim", true);
	}

	public static String rewardCurrency() {
		return store().getString("rewardCurrency", "free");
	}
}
