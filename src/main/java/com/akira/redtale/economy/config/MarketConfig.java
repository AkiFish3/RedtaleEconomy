package com.akira.redtale.economy.config;

import java.util.List;

/**
 * {@code config/redtale/market.json} —— 玩家市场（R40，spec §43.5 统一 offers 表）。
 *
 * <p>与其他模块的取舍一致：<b>托管优先</b>。挂单时物品立刻离开玩家背包进系统托管，
 * 成交/撤单/过期时再归还；不做「挂单后物品仍在背包」的懒托管（那等于让玩家一物多卖）。
 *
 * <p>手续费分两段：
 * <ul>
 *   <li>{@code feePercent} 挂单费：按标价抽，挂单时扣，撤单<b>不退</b>（防刷单占位）；</li>
 *   <li>{@code commissionPercent} 成交佣金：从货款里扣，卖家到账 = 标价 - 佣金。</li>
 * </ul>
 */
public final class MarketConfig {

	public static final String FILE = "market.json";

	public static final ConfigSchema SCHEMA = ConfigSchema.obj("玩家市场（R40）")
			.field("enabled", ConfigSchema.bool(true, "是否启用玩家市场"))
			.field("feePercent", ConfigSchema.decimal(1.0, 0, 50, "挂单费百分比（按标价，挂单时扣，撤单不退）"))
			.field("commissionPercent", ConfigSchema.decimal(5.0, 0, 50, "成交佣金百分比（从货款扣）"))
			.field("expireHours", ConfigSchema.integer(72, 1, 720, "挂单有效期（小时），到期自动下架并退回物品"))
			.field("maxOpenPerPlayer", ConfigSchema.integer(5, 1, 50, "每人同时挂单上限"))
			.field("minPrice", ConfigSchema.integer(1, 0, 1_000_000_000L, "最低标价（铜币，整单）"))
			.field("maxPrice", ConfigSchema.integer(1_000_000_000, 0, 1_000_000_000_000L, "最高标价（铜币，整单）"))
			.field("maxCount", ConfigSchema.integer(2304, 1, 3456, "单条挂单最大物品数（36 组）"))
			.field("blockedItems", ConfigSchema.stringList(List.of(),
					"禁止上架的物品 id 黑名单（如任务券/补签券）"))
			.field("notifyOnSell", ConfigSchema.bool(true, "成交时动作栏提示卖家"))
			.field("notifyOnExpire", ConfigSchema.bool(true, "过期下架时动作栏提示卖家"))
			.field("pageSize", ConfigSchema.integer(18, 1, 54, "市场列表一页条目数"))
			// ---- R41 拍卖 ----
			.field("auctionEnabled", ConfigSchema.bool(true, "是否允许挂拍卖（与市场共用一张 offers 表）"))
			.field("auctionHours", ConfigSchema.integer(24, 1, 336, "拍卖默认时长（小时）"))
			.field("auctionMinIncrementPercent", ConfigSchema.decimal(5.0, 1, 100,
					"每次加价至少比当前价高百分之几"))
			.field("auctionExtendMinutes", ConfigSchema.integer(2, 0, 60,
					"最后一刻出价顺延多少分钟（0 = 不顺延，狙击会更容易）"))
			.field("auctionExtendWindowMinutes", ConfigSchema.integer(2, 0, 60,
					"剩余时间不足多少分钟时触发展期"));

	private MarketConfig() {
	}

	public static ConfigStore store() {
		return ConfigManager.get().store(FILE);
	}

	public static boolean enabled() {
		return store().getBool("enabled", true);
	}

	public static double feePercent() {
		return store().getDouble("feePercent", 1.0);
	}

	public static double commissionPercent() {
		return store().getDouble("commissionPercent", 5.0);
	}

	public static int expireHours() {
		return (int) store().getLong("expireHours", 72);
	}

	public static int maxOpenPerPlayer() {
		return (int) store().getLong("maxOpenPerPlayer", 5);
	}

	public static long minPrice() {
		return store().getLong("minPrice", 1);
	}

	public static long maxPrice() {
		return store().getLong("maxPrice", 1_000_000_000L);
	}

	public static int maxCount() {
		return (int) store().getLong("maxCount", 2304);
	}

	public static List<String> blockedItems() {
		var el = store().getPath("blockedItems");
		java.util.List<String> out = new java.util.ArrayList<>();
		if (el != null && el.isJsonArray()) {
			for (var e : el.getAsJsonArray()) {
				if (e.isJsonPrimitive()) out.add(e.getAsString());
			}
		}
		return out;
	}

	public static boolean notifyOnSell() {
		return store().getBool("notifyOnSell", true);
	}

	public static boolean notifyOnExpire() {
		return store().getBool("notifyOnExpire", true);
	}

	public static int pageSize() {
		return (int) store().getLong("pageSize", 18);
	}

	// ---------------------------------------------------------------- R41 拍卖

	public static boolean auctionEnabled() {
		return store().getBool("auctionEnabled", true);
	}

	public static int auctionHours() {
		return (int) store().getLong("auctionHours", 24);
	}

	public static double auctionMinIncrementPercent() {
		return store().getDouble("auctionMinIncrementPercent", 5.0);
	}

	public static int auctionExtendMinutes() {
		return (int) store().getLong("auctionExtendMinutes", 2);
	}

	public static int auctionExtendWindowMinutes() {
		return (int) store().getLong("auctionExtendWindowMinutes", 2);
	}
}
