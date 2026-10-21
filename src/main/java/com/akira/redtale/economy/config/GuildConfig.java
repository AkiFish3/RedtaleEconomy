package com.akira.redtale.economy.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code config/redtale/guild.json} —— 冒险者协会等级（R32，spec v0.22 §34.1）。
 *
 * <p>永久成长线：不像战令按赛季清空，声望只增不减；等级由声望阈值推导，
 * 特权（商店折扣 / 悬赏位 / 传送折扣）全部按「当前等级序号」线性叠加并可封顶。
 */
public final class GuildConfig {

	public static final String FILE = "guild.json";

	private static JsonArray defaultRanks() {
		JsonArray a = new JsonArray();
		a.add(rank("F", "见习", 0));
		a.add(rank("E", "初级", 500));
		a.add(rank("D", "中级", 1500));
		a.add(rank("C", "高级", 4000));
		a.add(rank("B", "资深", 9000));
		a.add(rank("A", "精英", 20000));
		a.add(rank("S", "传说", 45000));
		return a;
	}

	private static JsonObject rank(String id, String name, long rep) {
		JsonObject o = new JsonObject();
		o.addProperty("id", id);
		o.addProperty("name", name);
		o.addProperty("rep", rep);
		return o;
	}

	private static JsonObject defaultTitles() {
		JsonObject o = new JsonObject();
		o.addProperty("F", "新手冒险者");
		o.addProperty("C", "资深采集者");
		o.addProperty("S", "赤物语传说");
		return o;
	}

	public static final ConfigSchema SCHEMA = ConfigSchema.obj("冒险者协会等级")
			.field("enabled", ConfigSchema.bool(true, "是否启用协会等级"))
			.field("ranks", ConfigSchema.rawList(defaultRanks(),
					"等级表：[{id,name,rep}]，按 rep 升序；第一个为初始等级"))
			.field("rep", ConfigSchema.obj("声望来源（每次 +N）")
					.field("bounty", ConfigSchema.integer(50, 0, 100000, "完成一条系统悬赏"))
					.field("submit", ConfigSchema.integer(20, 0, 100000, "任务缴纳"))
					.field("newBiome", ConfigSchema.integer(30, 0, 100000, "首次到达群系"))
					.field("newDimension", ConfigSchema.integer(100, 0, 100000, "首次进入维度"))
					.field("ftbqChapter", ConfigSchema.integer(200, 0, 100000, "FTBQ 章节完成"))
					.field("checkin", ConfigSchema.integer(5, 0, 100000, "每日签到")))
			.field("perks", ConfigSchema.obj("特权")
					.field("shopDiscountPerRank", ConfigSchema.decimal(0.02, 0, 1, "每级商店折扣（0.02 = 2%）"))
					.field("maxDiscount", ConfigSchema.decimal(0.15, 0, 1, "商店折扣上限"))
					.field("bountySlotsPerRank", ConfigSchema.integer(1, 0, 9, "每级多几个悬赏位"))
					.field("maxBountySlots", ConfigSchema.integer(9, 1, 9, "悬赏位上限"))
					.field("teleportDiscountPerRank", ConfigSchema.decimal(0.05, 0, 1, "每级传送折扣")))
			// T59：声望可以扣（负值），带冷却与每日上限，避免被恶意刷
			.field("repLoss", ConfigSchema.obj("扣声望（T59）")
					.field("enabled", ConfigSchema.bool(true, "是否允许扣声望（关掉=只增不减，回到老行为）"))
					.field("floor", ConfigSchema.integer(-2000, -1_000_000, 0, "声望下限（再扣也止于此）"))
					.field("dailyCap", ConfigSchema.integer(300, 0, 1_000_000, "每人每天最多扣多少（0 = 不限制）"))
					.field("cooldownSeconds", ConfigSchema.integer(60, 0, 86400,
							"同来源两次扣声望的最小间隔（秒）"))
					.field("sources", ConfigSchema.obj("扣声望来源（每次 -N）")
							.field("death", ConfigSchema.integer(10, 0, 100000, "普通死亡"))
							.field("bountyExpire", ConfigSchema.integer(30, 0, 100000, "悬赏超时未完成"))
							.field("admin", ConfigSchema.integer(100, 0, 100000, "管理员/事件手动扣"))))
			// T64：负声望的负面效果（阈值可配）
			.field("negative", ConfigSchema.obj("负声望效果（T64）")
					.field("lightBelow", ConfigSchema.integer(-100, -1_000_000, 1, "轻度阈值（rep 小于等于它起算）"))
					.field("lightTax", ConfigSchema.decimal(0.05, 0, 3, "轻度：商店加税比例（0.05 = +5%）"))
					.field("severeBelow", ConfigSchema.integer(-500, -1_000_000, 1, "重度阈值"))
					.field("severeTax", ConfigSchema.decimal(0.15, 0, 3, "重度：商店加税比例"))
					.field("severeDeny", ConfigSchema.bool(true,
							"重度：拒绝商店购买（关掉=只加税不封店）")))
			.field("titles", ConfigSchema.rawList(nodeArray(defaultTitles()),
					"等级 → 称号（R34 用）：[{id,title}]"));

	private static JsonArray nodeArray(JsonObject o) {
		JsonArray a = new JsonArray();
		for (var e : o.entrySet()) {
			JsonObject it = new JsonObject();
			it.addProperty("id", e.getKey());
			it.addProperty("title", e.getValue().getAsString());
			a.add(it);
		}
		return a;
	}

	private GuildConfig() {
	}

	public static ConfigStore store() {
		return ConfigManager.get().store(FILE);
	}

	public static boolean enabled() {
		return store().getBool("enabled", true);
	}

	/** 等级定义（按 rep 升序）。 */
	public record Rank(String id, String name, long rep) {
	}

	public static List<Rank> ranks() {
		List<Rank> out = new ArrayList<>();
		var el = store().getPath("ranks");
		if (el != null && el.isJsonArray()) {
			for (var e : el.getAsJsonArray()) {
				if (!e.isJsonObject()) continue;
				JsonObject o = e.getAsJsonObject();
				String id = o.has("id") ? o.get("id").getAsString() : "";
				if (id.isEmpty()) continue;
				String name = o.has("name") ? o.get("name").getAsString() : id;
				long rep = o.has("rep") ? Math.max(0, o.get("rep").getAsLong()) : 0;
				out.add(new Rank(id, name, rep));
			}
		}
		out.sort(java.util.Comparator.comparingLong(Rank::rep));
		return out;
	}

	public static long repGain(String source) {
		return store().getPathLong("rep." + source, 0);
	}

	public static double shopDiscountPerRank() {
		return store().getPathDouble("perks.shopDiscountPerRank", 0.02);
	}

	public static double maxDiscount() {
		return store().getPathDouble("perks.maxDiscount", 0.15);
	}

	public static int bountySlotsPerRank() {
		return (int) store().getPathLong("perks.bountySlotsPerRank", 1);
	}

	public static int maxBountySlots() {
		return (int) store().getPathLong("perks.maxBountySlots", 9);
	}

	public static double teleportDiscountPerRank() {
		return store().getPathDouble("perks.teleportDiscountPerRank", 0.05);
	}

	/** 等级对应的称号（没配返回空串）。 */
	public static String title(String rankId) {
		var el = store().getPath("titles");
		if (el != null && el.isJsonArray()) {
			for (var e : el.getAsJsonArray()) {
				if (!e.isJsonObject()) continue;
				JsonObject o = e.getAsJsonObject();
				if (o.has("id") && rankId.equals(o.get("id").getAsString())) {
					return o.has("title") ? o.get("title").getAsString() : "";
				}
			}
		}
		return "";
	}

	// ---------------------------------------------------------------- T59 扣声望

	public static boolean repLossEnabled() {
		return store().getPathBool("repLoss.enabled", true);
	}

	public static long repFloor() {
		return store().getPathLong("repLoss.floor", -2000);
	}

	/** 每日扣声望上限；0 = 不限制。 */
	public static long repLossDailyCap() {
		return store().getPathLong("repLoss.dailyCap", 300);
	}

	public static int repLossCooldownSeconds() {
		return (int) store().getPathLong("repLoss.cooldownSeconds", 60);
	}

	/** 某来源单次扣多少（未配置 = 0 = 不扣）。 */
	public static long repLoss(String source) {
		return store().getPathLong("repLoss.sources." + source, 0);
	}

	// ---------------------------------------------------------------- T64 负声望效果

	public static long negativeLightBelow() {
		return store().getPathLong("negative.lightBelow", -100);
	}

	public static double negativeLightTax() {
		return store().getPathDouble("negative.lightTax", 0.05);
	}

	public static long negativeSevereBelow() {
		return store().getPathLong("negative.severeBelow", -500);
	}

	public static double negativeSevereTax() {
		return store().getPathDouble("negative.severeTax", 0.15);
	}

	public static boolean negativeSevereDeny() {
		return store().getPathBool("negative.severeDeny", true);
	}
}
