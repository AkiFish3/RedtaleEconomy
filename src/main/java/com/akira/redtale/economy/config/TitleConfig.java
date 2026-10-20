package com.akira.redtale.economy.config;

import java.util.List;

/**
 * {@code config/redtale/title.json} —— 称号（R34 / R35，spec v0.22 §35.9-35.10）。
 *
 * <p>实证链路：`/styledchat set &lt;玩家&gt; display_name &lt;格式&gt;` 写 StyledChat 的 PlayerData，
 * 聊天里 `${player}` 展开成 display_name ⇒ 称号 = display_name 里名字前面那段。
 * 所以我们不改任何模组内部数据，只以服务端 4 级权限执行命令；原始 display_name 由我们自己备份以便还原。
 */
public final class TitleConfig {

	public static final String FILE = "title.json";

	public static final ConfigSchema SCHEMA = ConfigSchema.obj("称号")
			.field("enabled", ConfigSchema.bool(true, "是否启用称号"))
			.field("provider", ConfigSchema.choice("styledchat", List.of("styledchat", "internal"),
					"styledchat=执行 /styledchat set（推荐）；internal=只在 /titles 里显示，不改聊天"))
			.field("commandTemplate", ConfigSchema.string(
					"styledchat set {player} display_name {value}",
					"实际执行的命令（不带斜杠）；{player}=玩家名，{value}=格式化后的 display_name"))
			.field("displayNameTemplate", ConfigSchema.string(
					"<white>[</white><c:gold>{title}</c><white>]</white> <white>{player}</white>",
					"称号模板：{title}=称号文本，{player}=玩家名；用 StyledChat 的标签语法"))
			.field("restoreTemplate", ConfigSchema.string(
					"<white>[</white><c:green>玩家</c><white>]</white> <white>${vanillaDisplayName}</white>",
					"清除称号时还原成的 display_name（StyledChat 默认样式）"))
			.field("restoreOnDisable", ConfigSchema.bool(true,
					"模块关闭/卸载时是否把所有在线玩家的 display_name 还原"))
			.field("maxLength", ConfigSchema.integer(16, 1, 64, "称号文本最大长度（防刷屏）"))
			.field("item", ConfigSchema.obj("称号物品（R35）")
					.field("enabled", ConfigSchema.bool(true, "是否允许 /titles give 生成物品"))
					.field("itemId", ConfigSchema.string("minecraft:name_tag", "载体物品 id"))
					.field("namePrefix", ConfigSchema.string("称号：", "物品显示名前缀"))
					.field("lore", ConfigSchema.stringList(List.of(
							"右键使用：把称号写进你的聊天名",
							"物品由服务器发放，请勿交易给他人"),
							"物品 lore（每行一条）"))
					.field("commandTemplate", ConfigSchema.string(
							"/styledchat set @p display_name {value}",
							"ItemCommander 里绑定的命令；{value}=格式化后的 display_name"))
					.field("source", ConfigSchema.integer(0, 0, 2,
							"ItemCommander Source：0=以玩家身份（@p 生效），2=以服务器身份"))
					.field("cooldown", ConfigSchema.integer(0, 0, 3600, "ItemCommander 冷却秒数")));

	private TitleConfig() {
	}

	public static ConfigStore store() {
		return ConfigManager.get().store(FILE);
	}

	public static boolean enabled() {
		return store().getBool("enabled", true);
	}

	public static String provider() {
		return store().getString("provider", "styledchat");
	}

	public static String commandTemplate() {
		return store().getString("commandTemplate", "styledchat set {player} display_name {value}");
	}

	public static String displayNameTemplate() {
		return store().getString("displayNameTemplate",
				"<white>[</white><c:gold>{title}</c><white>]</white> <white>{player}</white>");
	}

	public static String restoreTemplate() {
		return store().getString("restoreTemplate",
				"<white>[</white><c:green>玩家</c><white>]</white> <white>${vanillaDisplayName}</white>");
	}

	public static boolean restoreOnDisable() {
		return store().getBool("restoreOnDisable", true);
	}

	public static int maxLength() {
		return (int) store().getLong("maxLength", 16);
	}

	public static boolean itemEnabled() {
		return store().getPathBool("item.enabled", true);
	}

	public static String itemId() {
		return store().getPathString("item.itemId", "minecraft:name_tag");
	}

	public static String itemNamePrefix() {
		return store().getPathString("item.namePrefix", "称号：");
	}

	public static List<String> itemLore() {
		var el = store().getPath("item.lore");
		java.util.List<String> out = new java.util.ArrayList<>();
		if (el != null && el.isJsonArray()) {
			for (var e : el.getAsJsonArray()) {
				if (e.isJsonPrimitive()) out.add(e.getAsString());
			}
		}
		return out;
	}

	public static String itemCommandTemplate() {
		return store().getPathString("item.commandTemplate", "/styledchat set @p display_name {value}");
	}

	public static int itemSource() {
		return (int) store().getPathLong("item.source", 0);
	}

	public static int itemCooldown() {
		return (int) store().getPathLong("item.cooldown", 0);
	}
}
