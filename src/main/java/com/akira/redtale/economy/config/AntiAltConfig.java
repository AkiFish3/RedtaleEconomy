package com.akira.redtale.economy.config;

import java.util.List;

/**
 * {@code config/redtale/anti-alt.json} —— T18 防小号：按「IP + UUID」限制奖励领取。
 *
 * <p>为什么默认关：同一家庭/网吧/校园网共用出口 IP 是常态，硬性拦 IP 会误杀正常玩家。
 * 服主确认自己有「一人多号刷奖励」的动机再开，且建议先只开 {@code checkin}（签到）、
 * 观察 {@code security/ipmap.json} 里的 IP 聚集情况再决定阈值。
 *
 * <p>判定口径（{@link com.akira.redtale.economy.storage.AltGuard}）：同一 IP 在
 * {@code cooldownHours} 小时内出现过的<b>不同 UUID</b> 数超过 {@code maxAccountsPerIp} 时，
 * 只拒绝"新面孔"（已登记过的账号照常）。
 */
public final class AntiAltConfig {

	public static final String FILE = "anti-alt.json";

	public static final ConfigSchema SCHEMA = ConfigSchema.obj("防小号（T18，默认关闭）")
			.field("enabled", ConfigSchema.bool(false,
					"是否启用（家庭/网吧共用 IP 会被误杀，确认需要再开）"))
			.field("cooldownHours", ConfigSchema.integer(24, 1, 720,
					"统计窗口（小时）：窗口内同 IP 的不同账号超过上限则拒绝新账号"))
			.field("maxAccountsPerIp", ConfigSchema.integer(3, 1, 50,
					"同一 IP 在窗口内允许出现的账号数上限（含本人）"))
			.field("scopes", ConfigSchema.stringList(List.of("checkin", "task"),
					"生效范围：checkin（签到）/ task（任务领取与缴纳）"))
			.field("logOnly", ConfigSchema.bool(true,
					"只记日志不拦截（先观察误杀率，确认无误再改成 false）"));

	private AntiAltConfig() {
	}

	public static ConfigStore store() {
		return ConfigManager.get().store(FILE);
	}

	public static boolean enabled() {
		try {
			return store().getBool("enabled", false);
		} catch (RuntimeException e) {
			// 配置文件缺失/未注册时按关闭处理：防小号绝不能因为配置问题拦死正常玩家
			return false;
		}
	}

	public static int cooldownHours() {
		try {
			return (int) store().getLong("cooldownHours", 24);
		} catch (RuntimeException e) {
			return 24;
		}
	}

	public static int maxAccountsPerIp() {
		try {
			return (int) store().getLong("maxAccountsPerIp", 3);
		} catch (RuntimeException e) {
			return 3;
		}
	}

	public static List<String> scopes() {
		try {
			var el = store().getPath("scopes");
			java.util.List<String> out = new java.util.ArrayList<>();
			if (el != null && el.isJsonArray()) {
				for (var e : el.getAsJsonArray()) {
					if (e.isJsonPrimitive()) out.add(e.getAsString());
				}
			}
			return out.isEmpty() ? List.of("checkin", "task") : out;
		} catch (RuntimeException e) {
			return List.of("checkin", "task");
		}
	}

	public static boolean logOnly() {
		try {
			return store().getBool("logOnly", true);
		} catch (RuntimeException e) {
			return true;
		}
	}
}
