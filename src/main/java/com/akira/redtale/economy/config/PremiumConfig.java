package com.akira.redtale.economy.config;

import java.util.List;

/**
 * {@code config/redtale/premium.json} —— 赞助货币（spec v0.3 §6.1 / v0.4 修订）。
 *
 * <p><b>当前只有配置骨架</b>：默认 {@code enabled=false}，客户端不显示任何入口。
 * 发放/商店/抽奖逻辑在 R13 实现，此处只保证开关与白名单可配。
 */
public final class PremiumConfig {

	public static final String FILE = "premium.json";

	public static final ConfigSchema SCHEMA = ConfigSchema.obj("赞助货币（默认隐藏）")
			.field("enabled", ConfigSchema.bool(false,
					"总开关；false 时客户端不显示任何入口，/premium 也不注册"))
			.field("displayName", ConfigSchema.string("premium", "客户端显示名称"))
			.field("symbol", ConfigSchema.string("§b✦",
					"图标/符号前缀。★当前无读取方（预留给 R13 的赞助商店/抽奖 UI）★，"
							+ "聊天里的赞助货币目前只用 displayName"))
			.field("requireWhitelist", ConfigSchema.bool(true,
					"发放时是否要求玩家在 whitelist 内（OP + 白名单为「与」关系）"))
			.field("whitelist", ConfigSchema.stringList(List.of(),
					"允许接收赞助货币的玩家 UUID 列表；requireWhitelist=false 时忽略"))
			.field("showBalanceToSelf", ConfigSchema.bool(true, "玩家能否查询自己的赞助货币余额"))
			.field("shopEnabled", ConfigSchema.bool(false, "赞助商店（预留，R13 之后实现）"))
			.field("lotteryEnabled", ConfigSchema.bool(false, "抽奖（预留，字段见 lottery.json）"));

	private PremiumConfig() {
	}

	public static ConfigStore store() {
		return ConfigManager.get().store(FILE);
	}

	public static boolean enabled() {
		return store().getBool("enabled", false);
	}

	public static String displayName() {
		return store().getString("displayName", "premium");
	}
}
