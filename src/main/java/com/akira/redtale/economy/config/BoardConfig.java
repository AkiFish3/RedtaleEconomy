package com.akira.redtale.economy.config;

import java.util.List;

/**
 * {@code config/redtale/board.json} —— 玩家委托板（R30，spec v0.22 §33.3）。
 *
 * <p>只允许「物品类」委托：进度类（代打/代开箱）一律不给发，避免变成代练市场。
 * 发布时一次性扣「赏金 + 手续费」，接单后交付物品即销毁，赏金打给接单者。
 */
public final class BoardConfig {

	public static final String FILE = "board.json";

	public static final ConfigSchema SCHEMA = ConfigSchema.obj("玩家委托板")
			.field("enabled", ConfigSchema.bool(true, "是否启用委托板"))
			.field("feePercent", ConfigSchema.decimal(5.0, 0, 50, "发布手续费百分比（赏金的 %）"))
			.field("expireHours", ConfigSchema.integer(24, 1, 720, "超过多少小时没人接自动撤销并退赏金（不退手续费）"))
			.field("maxOpenPerPlayer", ConfigSchema.integer(5, 1, 50, "每人同时挂着的委托上限"))
			.field("minBounty", ConfigSchema.integer(10, 0, 1_000_000_000L, "最低赏金（铜币）"))
			.field("maxBounty", ConfigSchema.integer(10_000_000, 0, 1_000_000_000_000L, "最高赏金（铜币）"))
			.field("maxCount", ConfigSchema.integer(576, 1, 2304, "单条委托最大物品数（36 组）"))
			.field("blockedItems", ConfigSchema.stringList(List.of(),
					"禁止委托的物品 id 黑名单（如任务券）"))
			.field("notifyOnAccept", ConfigSchema.bool(true, "有人接单时是否在动作栏提示发布者"));

	private BoardConfig() {
	}

	public static ConfigStore store() {
		return ConfigManager.get().store(FILE);
	}

	public static boolean enabled() {
		return store().getBool("enabled", true);
	}

	public static double feePercent() {
		return store().getDouble("feePercent", 5.0);
	}

	public static int expireHours() {
		return (int) store().getLong("expireHours", 24);
	}

	public static int maxOpenPerPlayer() {
		return (int) store().getLong("maxOpenPerPlayer", 5);
	}

	public static long minBounty() {
		return store().getLong("minBounty", 10);
	}

	public static long maxBounty() {
		return store().getLong("maxBounty", 10_000_000);
	}

	public static int maxCount() {
		return (int) store().getLong("maxCount", 576);
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

	public static boolean notifyOnAccept() {
		return store().getBool("notifyOnAccept", true);
	}
}
