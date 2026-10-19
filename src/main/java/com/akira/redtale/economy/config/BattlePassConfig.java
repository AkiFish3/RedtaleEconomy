package com.akira.redtale.economy.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code config/redtale/battlepass.json} —— 战令 / 通行证（R20，spec v0.15 §21）。
 *
 * <p>赛季 + 双轨奖励；经验来源全部是系数（签到 / 任务领取 / 在线分钟 / 商店消费），
 * 等级曲线支持 linear 与 exp 两种，奖励表按 level 挂 money / items。
 */
public final class BattlePassConfig {

	public static final String FILE = "battlepass.json";

	private static JsonArray freeTrack() {
		JsonArray a = new JsonArray();
		a.add(levelReward(1, 100));
		a.add(levelReward(2, 200));
		a.add(levelReward(3, 300));
		return a;
	}

	private static JsonArray premiumTrack() {
		JsonArray a = new JsonArray();
		JsonObject o = new JsonObject();
		o.addProperty("level", 1);
		JsonObject reward = new JsonObject();
		JsonArray items = new JsonArray();
		JsonObject it = new JsonObject();
		it.addProperty("id", "minecraft:diamond");
		it.addProperty("count", 2);
		items.add(it);
		reward.add("items", items);
		o.add("reward", reward);
		a.add(o);
		return a;
	}

	private static JsonObject levelReward(int level, long money) {
		JsonObject o = new JsonObject();
		o.addProperty("level", level);
		JsonObject reward = new JsonObject();
		reward.addProperty("money", money);
		o.add("reward", reward);
		return o;
	}

	public static final ConfigSchema SCHEMA = ConfigSchema.obj("战令")
			.field("enabled", ConfigSchema.bool(false, "是否启用战令（默认关，赛季开始时打开）"))
			.field("season", ConfigSchema.obj("赛季")
					.field("id", ConfigSchema.string("S1", "赛季 id（数据目录名）"))
					.field("name", ConfigSchema.string("第一赛季", "显示名"))
					.field("start", ConfigSchema.string("2026-11-01", "开始日期 yyyy-MM-dd"))
					.field("end", ConfigSchema.string("2026-12-31", "结束日期 yyyy-MM-dd")))
			.field("levels", ConfigSchema.obj("等级曲线")
					.field("max", ConfigSchema.integer(50, 1, 500, "等级上限"))
					.field("xpPerLevel", ConfigSchema.integer(1000, 1, 10_000_000, "每级经验基数"))
					.field("curve", ConfigSchema.choice("linear", List.of("linear", "exp"),
							"linear=每级固定；exp=每级需求递增（l(l+1)/2）")))
			.field("xp", ConfigSchema.obj("经验来源")
					.field("checkin", ConfigSchema.integer(50, 0, 100000, "每日签到"))
					.field("taskClaim", ConfigSchema.integer(120, 0, 100000, "领取每日任务"))
					.field("playMinute", ConfigSchema.integer(1, 0, 1000, "在线每分钟"))
					.field("shopSpendPerCurrency", ConfigSchema.decimal(0.01, 0, 10,
							"商店每花 1 铜币给多少经验"))
					.field("premiumBonus", ConfigSchema.decimal(0.2, 0, 5, "付费轨额外经验比例"))
					.field("ftbq", ConfigSchema.obj("FTBQ 完成 → 战令经验（四层优先级）")
							.field("enabled", ConfigSchema.bool(true, "是否启用 FTBQ → 战令经验"))
							.field("default", ConfigSchema.integer(100, 0, 100000,
									"兜底经验：任务没挂 bp_xp 奖励、byQuest/byChapter 也没配时用"))
							.field("onlyOncePerPlayer", ConfigSchema.bool(true,
									"同一任务每人只发一次（关掉会随重复完成反复发）"))
							.field("byQuest", ConfigSchema.obj("按任务 code 覆盖：{\"3A2F1C0D9E8B\": 500}"))
							.field("byChapter", ConfigSchema.obj("按章节 code 覆盖：{\"prologue\": 50}"))))
			.field("unlock", ConfigSchema.obj("付费轨解锁")
					.field("currency", ConfigSchema.choice("premium", List.of("premium", "money"),
							"premium=赞助点；money=铜币"))
					.field("amount", ConfigSchema.integer(10, 0, 1_000_000_000L, "解锁价格"))
					.field("perSeason", ConfigSchema.bool(true, "每赛季都要重新解锁")))
			.field("tracks", ConfigSchema.obj("奖励轨")
					.field("free", ConfigSchema.rawList(freeTrack(),
							"免费轨：[{level, reward:{money|items:[{id,count}]}}]"))
					.field("premium", ConfigSchema.rawList(premiumTrack(),
							"付费轨：[{level, reward:{...}}]")));

	private BattlePassConfig() {
	}

	public static ConfigStore store() {
		return ConfigManager.get().store(FILE);
	}

	public static boolean enabled() {
		return store().getBool("enabled", false);
	}

	public static String seasonId() {
		return store().getString("season.id", "S1");
	}

	public static String seasonName() {
		return store().getString("season.name", "第一赛季");
	}

	public static String seasonStart() {
		return store().getString("season.start", "");
	}

	public static String seasonEnd() {
		return store().getString("season.end", "");
	}

	public static int maxLevel() {
		return (int) store().getLong("levels.max", 50);
	}

	public static long xpPerLevel() {
		return Math.max(1, store().getPathLong("levels.xpPerLevel", 1000));
	}

	public static String curve() {
		return store().getPathString("levels.curve", "linear");
	}

	public static long xpFor(String source) {
		return store().getPathLong("xp." + source, 0);
	}

	public static double xpPerCurrency() {
		return store().getPathDouble("xp.shopSpendPerCurrency", 0.01);
	}

	public static boolean ftbqEnabled() {
		return store().getPathBool("xp.ftbq.enabled", true);
	}

	public static long ftbqDefault() {
		return store().getPathLong("xp.ftbq.default", 100);
	}

	public static boolean ftbqOnlyOnce() {
		return store().getPathBool("xp.ftbq.onlyOncePerPlayer", true);
	}

	/** byQuest / byChapter 是动态键，按 code 直接取值（没配返回 -1）。 */
	public static long ftbqByQuest(String code) {
		return store().getPathLong("xp.ftbq.byQuest." + code, -1);
	}

	public static long ftbqByChapter(String code) {
		return store().getPathLong("xp.ftbq.byChapter." + code, -1);
	}

	public static double premiumBonus() {
		return store().getPathDouble("xp.premiumBonus", 0.2);
	}

	public static String unlockCurrency() {
		return store().getPathString("unlock.currency", "premium");
	}

	public static long unlockAmount() {
		return store().getPathLong("unlock.amount", 10);
	}

	public static boolean perSeason() {
		return store().getPathBool("unlock.perSeason", true);
	}

	/** 一条轨道奖励。 */
	public record Reward(int level, long money, List<ItemReward> items) {
	}

	public record ItemReward(String id, int count) {
	}

	public static List<Reward> track(String name) {
		List<Reward> out = new ArrayList<>();
		var el = store().getPath("tracks." + name);
		if (el == null || !el.isJsonArray()) return out;
		for (var e : el.getAsJsonArray()) {
			if (!e.isJsonObject()) continue;
			JsonObject o = e.getAsJsonObject();
			int level = o.has("level") ? o.get("level").getAsInt() : 0;
			if (level <= 0) continue;
			long money = 0;
			List<ItemReward> items = new ArrayList<>();
			if (o.has("reward") && o.get("reward").isJsonObject()) {
				JsonObject r = o.getAsJsonObject("reward");
				if (r.has("money")) money = Math.max(0, r.get("money").getAsLong());
				if (r.has("items") && r.get("items").isJsonArray()) {
					for (var ie : r.getAsJsonArray("items")) {
						if (!ie.isJsonObject()) continue;
						JsonObject io = ie.getAsJsonObject();
						if (!io.has("id")) continue;
						items.add(new ItemReward(io.get("id").getAsString(),
								io.has("count") ? Math.max(1, io.get("count").getAsInt()) : 1));
					}
				}
			}
			out.add(new Reward(level, money, items));
		}
		out.sort(java.util.Comparator.comparingInt(Reward::level));
		return out;
	}
}
