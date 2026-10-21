package com.akira.redtale.economy.config;

import java.util.List;

/**
 * {@code config/redtale/shop/settings.json} —— 商店全局参数（spec v0.8 §2.1）。
 *
 * <p>所有数值都在这里，代码只写默认值；改完 {@code /redtale reload} 生效。
 */
public final class ShopConfig {

	public static final String FILE = "shop/settings.json";

	public static final ConfigSchema SCHEMA = ConfigSchema.obj("商店设置")
			.field("limits", ConfigSchema.obj("价格护栏（Q4）")
					.field("minPrice", ConfigSchema.integer(1, 0, 1_000_000_000L,
							"单件最低价（铜）；0=不限"))
					.field("maxPrice", ConfigSchema.integer(1_000_000_000L, 1, 1_000_000_000_000L,
							"单件最高价（铜）"))
					.field("maxChangePercentPerEdit", ConfigSchema.integer(0, 0, 100,
							"单次改价相对原价的最大变动百分比，0=不限（防止手滑多打个 0）"))
					.field("maxChangePercentPerDay", ConfigSchema.integer(0, 0, 1000,
							"同一商品当天累计改价相对当日基准价的最大变动百分比，0=不限"))
					.field("enforceSellBelowBuy", ConfigSchema.bool(true,
							"强制卖价低于买价（E1）：防止买低卖高刷钱；关闭后只保留上下限校验"))
					.field("minSellMarginPercent", ConfigSchema.integer(5, 0, 95,
							"买卖最小价差（%）：卖价 ≤ 买价×(100-该值)%；0=只要求卖价严格低于买价")))
			.field("pageSize", ConfigSchema.integer(40, 5, 200,
					"每页条数；服务端只下发这一页，绝不全量推送"))
			.field("visibleRows", ConfigSchema.integer(10, 1, 40,
					"GUI 一屏显示多少行（不影响网络分页，仅本地滚动区高度）"))
			.field("pageRequestCooldownMs", ConfigSchema.integer(150, 0, 10000,
					"同一玩家两次翻页请求的最小间隔（毫秒），防刷包"))
			.field("maxSearchLength", ConfigSchema.integer(32, 1, 128, "搜索词最大长度，超出截断"))
			.field("maxCountPerTrade", ConfigSchema.integer(6400, 1, 6400,
					"单次买卖最大数量（协议硬上限 6400，防止一个包搬空仓库）"))
			.field("conflictPolicy", ConfigSchema.choice("outer", List.of("outer", "merge"),
					"同名商店/商品冲突策略：outer=浅目录优先，merge=合并"))
			.field("buyEnabled", ConfigSchema.bool(true, "是否允许购买（总开关）"))
			.field("sellEnabled", ConfigSchema.bool(true, "是否允许出售（总开关）"))
			.field("tax", ConfigSchema.obj("手续费 / 税")
					.field("enabled", ConfigSchema.bool(false, "是否开启（默认关，避免突然改变经济平衡）"))
					.field("buyPercent", ConfigSchema.integer(0, 0, 100,
							"买入税率（%）：买家实际支付 = 价格×(1+该值%)"))
					.field("sellPercent", ConfigSchema.integer(0, 0, 100,
							"卖出税率（%）：卖家实收 = 收入×(1-该值%)"))
					.field("skipForGuildRank", ConfigSchema.integer(0, 0, 100,
							"协会等级达到该档（0=关闭）时免税；填 rank 序号，0 表示不启用"))
					.field("mode", ConfigSchema.choice("blacklist", List.of("none", "whitelist", "blacklist"),
							"名单模式：none=全部按税率收；whitelist=只有名单内收税；blacklist=只有名单外收税"))
					.field("items", ConfigSchema.stringList(List.of(
									"minecraft:diamond",
									"minecraft:netherite_ingot"),
							"名单：物品 id 或 modid:* 通配（如 redtale_economy:*）")))
			.field("sellPriceRatio", ConfigSchema.decimal(0.5, 0, 100,
					"卖出价系数：sell<=0 时按 buy×ratio 计算，0=不回收"))
			.field("sellDailyLimitPerItem", ConfigSchema.integer(0, 0, 1_000_000,
					"每人每日出售同一商品上限（0=不限）"))
			.field("sellDailyLimitTotal", ConfigSchema.integer(0, 0, 1_000_000_000L,
					"每人每日出售总额上限（铜，0=不限）"))
			.field("stockFile", ConfigSchema.string("shop/stock.json",
					"库存覆盖文件（相对数据根目录）；只在有限库存时才写"))
			.field("limitsDir", ConfigSchema.string("shop/limits",
					"每日限购记录目录（相对数据根目录）"));

	private ShopConfig() {
	}

	public static ConfigStore store() {
		return ConfigManager.get().store(FILE);
	}

	public static long minPrice() {
		return store().getPathLong("limits.minPrice", 0);
	}

	public static long maxPrice() {
		return store().getPathLong("limits.maxPrice", 1_000_000_000L);
	}

	public static int maxChangePercentPerDay() {
		return (int) store().getPathLong("limits.maxChangePercentPerDay", 0);
	}

	public static int maxChangePercentPerEdit() {
		return (int) store().getPathLong("limits.maxChangePercentPerEdit", 0);
	}

	/** E1：卖价必须低于买价（防买低卖高刷钱）。 */
	public static boolean enforceSellBelowBuy() {
		return store().getPathBool("limits.enforceSellBelowBuy", true);
	}

	/** E1：最小价差百分比，卖价 ≤ 买价×(100-n)%。 */
	public static int minSellMarginPercent() {
		return (int) store().getPathLong("limits.minSellMarginPercent", 5);
	}

	public static int pageSize() {
		return (int) store().getLong("pageSize", 40);
	}

	public static int visibleRows() {
		return (int) store().getLong("visibleRows", 10);
	}

	public static int pageRequestCooldownMs() {
		return (int) store().getLong("pageRequestCooldownMs", 150);
	}

	public static int maxCountPerTrade() {
		return (int) store().getLong("maxCountPerTrade", 6400);
	}

	public static int maxSearchLength() {
		return (int) store().getLong("maxSearchLength", 32);
	}

	public static String conflictPolicy() {
		return store().getString("conflictPolicy", "outer");
	}

	public static boolean buyEnabled() {
		return store().getBool("buyEnabled", true);
	}

	public static String taxMode() {
		return store().getPathString("tax.mode", "blacklist");
	}

	public static java.util.List<String> taxItems() {
		var el = store().getPath("tax.items");
		java.util.List<String> out = new java.util.ArrayList<>();
		if (el != null && el.isJsonArray()) {
			for (var e : el.getAsJsonArray()) {
				if (e.isJsonPrimitive()) out.add(e.getAsString());
			}
		}
		return out;
	}

	public static boolean taxEnabled() {
		return store().getPathBool("tax.enabled", false);
	}

	public static int taxBuyPercent() {
		return (int) Math.max(0, Math.min(100, store().getPathLong("tax.buyPercent", 0)));
	}

	public static int taxSellPercent() {
		return (int) Math.max(0, Math.min(100, store().getPathLong("tax.sellPercent", 0)));
	}

	/** 免税所需协会档位序号；0 = 不启用。 */
	public static int taxSkipRank() {
		return (int) Math.max(0, store().getPathLong("tax.skipForGuildRank", 0));
	}

	public static boolean sellEnabled() {
		return store().getBool("sellEnabled", true);
	}

	public static double sellPriceRatio() {
		return store().getDouble("sellPriceRatio", 0.5);
	}

	public static int sellDailyLimitPerItem() {
		return (int) store().getLong("sellDailyLimitPerItem", 0);
	}

	public static long sellDailyLimitTotal() {
		return store().getLong("sellDailyLimitTotal", 0);
	}

	public static String stockFile() {
		return store().getPathString("stockFile", "shop/stock.json");
	}

	public static String limitsDir() {
		return store().getPathString("limitsDir", "shop/limits");
	}
}
