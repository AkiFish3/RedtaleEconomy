package com.akira.redtale.economy.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code config/redtale/bounty.json} —— 系统悬赏（R31，spec v0.22 §33.2）。
 *
 * <p>pool 里每条悬赏就是一个「行为目标」：type 决定挂哪个事件，target 决定匹配谁，
 * count 是完成量，unique=true 表示「不重复」（群系/维度这类只计第一次）。
 * 全部数值都在配置里，代码只负责按事件推进进度。
 */
public final class BountyConfig {

	public static final String FILE = "bounty.json";

	/** 支持的目标类型（事件驱动）。 */
	public static final List<String> TYPES = List.of(
			"kill", "submit", "loot_chest", "biome", "dimension", "craft", "fish", "travel", "playtime");

	private static JsonArray defaultPool() {
		JsonArray a = new JsonArray();
		a.add(def("zombie20", "kill", "minecraft:zombie", 20, false, 300, 10));
		a.add(def("chest10", "loot_chest", "", 10, false, 400, 8));
		a.add(def("biome5", "biome", "", 5, true, 500, 5));
		a.add(def("cake10", "craft", "minecraft:cake", 10, false, 350, 6));
		a.add(def("fish15", "fish", "", 15, false, 320, 6));
		a.add(def("travel2000", "travel", "", 2000, false, 260, 6));
		a.add(def("playtime60", "playtime", "", 60, false, 280, 6));
		a.add(def("dimension1", "dimension", "", 1, true, 600, 3));
		a.add(def("bread32", "submit", "minecraft:bread", 32, false, 380, 5));
		return a;
	}

	private static JsonObject def(String id, String type, String target, int count,
			boolean unique, long money, int weight) {
		JsonObject o = new JsonObject();
		o.addProperty("id", id);
		o.addProperty("type", type);
		if (!target.isEmpty()) o.addProperty("target", target);
		o.addProperty("count", count);
		o.addProperty("unique", unique);
		o.addProperty("weight", weight);
		JsonObject reward = new JsonObject();
		reward.addProperty("money", money);
		o.add("reward", reward);
		return o;
	}

	public static final ConfigSchema SCHEMA = ConfigSchema.obj("系统悬赏")
			.field("enabled", ConfigSchema.bool(true, "是否启用系统悬赏"))
			.field("resetHour", ConfigSchema.integer(4, 0, 23, "每日刷新小时（避开午夜在线高峰）"))
			.field("slotCount", ConfigSchema.integer(3, 1, 9, "每天随机几条悬赏"))
			.field("notifyOnComplete", ConfigSchema.bool(true, "进度达标时是否在动作栏提示"))
			.field("progressFlushTicks", ConfigSchema.integer(100, 20, 1200,
					"位移/在线时长多久结算一次（tick）；越小越实时，越大越省 IO"))
			.field("biomeSampleTicks", ConfigSchema.integer(40, 5, 600,
					"群系采样间隔（tick）；40 ≈ 2 秒"))
			.field("pool", ConfigSchema.rawList(defaultPool(),
					"悬赏池：[{id,type,target,count,unique,reward:{money},weight}]；"
							+ "type ∈ kill/submit/loot_chest/biome/dimension/craft/fish/travel/playtime"));

	private BountyConfig() {
	}

	public static ConfigStore store() {
		return ConfigManager.get().store(FILE);
	}

	public static boolean enabled() {
		return store().getBool("enabled", true);
	}

	public static int resetHour() {
		return (int) store().getLong("resetHour", 4);
	}

	public static int slotCount() {
		return (int) store().getLong("slotCount", 3);
	}

	public static int progressFlushTicks() {
		return (int) store().getLong("progressFlushTicks", 100);
	}

	public static int biomeSampleTicks() {
		return (int) store().getLong("biomeSampleTicks", 40);
	}

	public static boolean notifyOnComplete() {
		return store().getBool("notifyOnComplete", true);
	}

	/** 一条悬赏定义。 */
	public record Def(String id, String type, String target, int count, boolean unique,
			long money, int weight) {
	}

	/** 解析 pool；type 非法 / 缺 id 的条目丢弃并留一条日志。 */
	public static List<Def> pool() {
		List<Def> out = new ArrayList<>();
		var el = store().getPath("pool");
		if (el == null || !el.isJsonArray()) return out;
		for (var e : el.getAsJsonArray()) {
			if (!e.isJsonObject()) continue;
			JsonObject o = e.getAsJsonObject();
			String id = o.has("id") ? o.get("id").getAsString().trim() : "";
			String type = o.has("type") ? o.get("type").getAsString().trim().toLowerCase() : "";
			if (id.isEmpty() || !TYPES.contains(type)) continue;
			String target = o.has("target") ? o.get("target").getAsString().trim() : "";
			int count = o.has("count") ? Math.max(1, o.get("count").getAsInt()) : 1;
			boolean unique = o.has("unique") && o.get("unique").getAsBoolean();
			int weight = o.has("weight") ? Math.max(1, o.get("weight").getAsInt()) : 1;
			long money = 0;
			if (o.has("reward") && o.get("reward").isJsonObject()) {
				JsonObject r = o.getAsJsonObject("reward");
				if (r.has("money")) money = Math.max(0, r.get("money").getAsLong());
			}
			out.add(new Def(id, type, target, count, unique, money, weight));
		}
		return out;
	}
}
