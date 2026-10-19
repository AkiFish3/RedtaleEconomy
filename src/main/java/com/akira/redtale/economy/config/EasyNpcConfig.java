package com.akira.redtale.economy.config;

import java.util.List;

/**
 * {@code config/redtale/easynpc.json} —— EasyNPC 联动（R33，spec v0.22 §34.2）。
 *
 * <p>零耦合做法：我们只负责给玩家打 scoreboard tag（{@code rt_rank_F}…），
 * EasyNPC 的条件对话按 tag 分支；对话按钮本身由服主在 EasyNPC 编辑器里填命令。
 */
public final class EasyNpcConfig {

	public static final String FILE = "easynpc.json";

	public static final ConfigSchema SCHEMA = ConfigSchema.obj("EasyNPC 联动")
			.field("enabled", ConfigSchema.bool(true, "是否启用（关掉后不打 tag、不生成预设）"))
			.field("tagPrefix", ConfigSchema.string("rt_rank_", "协会等级 tag 前缀，实际 tag = 前缀 + 等级 id"))
			.field("syncOnJoin", ConfigSchema.bool(true, "玩家进服时同步一次等级 tag"))
			.field("removeStaleTags", ConfigSchema.bool(true, "同步时清掉其它 rt_rank_* tag（防止旧等级残留）"))
			.field("presetsDir", ConfigSchema.string("easynpc", "预设文件目录（相对 config/redtale/）"))
			.field("writePresets", ConfigSchema.bool(true, "是否在启动时写出 3 套预设（已存在则不覆盖）"))
			.field("presets", ConfigSchema.stringList(
					List.of("guild_receptionist", "shop_keeper", "warehouse_keeper"),
					"要生成的预设名（对应 presetsDir 下的 <名字>.json）"));

	private EasyNpcConfig() {
	}

	public static ConfigStore store() {
		return ConfigManager.get().store(FILE);
	}

	public static boolean enabled() {
		return store().getBool("enabled", true);
	}

	public static String tagPrefix() {
		return store().getString("tagPrefix", "rt_rank_");
	}

	public static boolean syncOnJoin() {
		return store().getBool("syncOnJoin", true);
	}

	public static boolean removeStaleTags() {
		return store().getBool("removeStaleTags", true);
	}

	public static String presetsDir() {
		return store().getString("presetsDir", "easynpc");
	}

	public static boolean writePresets() {
		return store().getBool("writePresets", true);
	}

	public static List<String> presets() {
		var el = store().getPath("presets");
		java.util.List<String> out = new java.util.ArrayList<>();
		if (el != null && el.isJsonArray()) {
			for (var e : el.getAsJsonArray()) {
				if (e.isJsonPrimitive() && !e.getAsString().isBlank()) out.add(e.getAsString().trim());
			}
		}
		return out.isEmpty() ? List.of("guild_receptionist", "shop_keeper", "warehouse_keeper") : out;
	}
}
