package com.akira.redtale.economy.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.List;

/**
 * {@code config/redtale/warehouse.json} —— 玩家仓储（R27）+ 扩容（R28，spec v0.20 §26 / v0.22 §33.4）。
 *
 * <p>所有数值都从这里读，代码里只留默认值；改完 {@code /redtale reload} 生效。
 * 扩容价格表按「目标格数」配置：走到 {@code slots} 时一次性收 {@code price} 铜 / {@code premium} 点。
 */
public final class WarehouseConfig {

	public static final String FILE = "warehouse.json";

	private static JsonArray defaultUpgrades() {
		JsonArray a = new JsonArray();
		a.add(step(63, 5000, 0));
		a.add(step(72, 12000, 0));
		a.add(step(81, 24000, 0));
		a.add(step(90, 45000, 1));
		a.add(step(99, 80000, 2));
		a.add(step(108, 140000, 3));
		return a;
	}

	private static JsonObject step(int slots, long price, long premium) {
		JsonObject o = new JsonObject();
		o.addProperty("slots", slots);
		o.addProperty("price", price);
		o.addProperty("premium", premium);
		return o;
	}

	public static final ConfigSchema SCHEMA = ConfigSchema.obj("玩家仓储")
			.field("enabled", ConfigSchema.bool(true, "是否启用仓储；关掉后 /storage 不注册"))
			.field("slots", ConfigSchema.integer(54, 9, 108, "基础格数（必须是 9 的倍数；54 = 3 大箱子）"))
			.field("maxSlots", ConfigSchema.integer(108, 9, 108, "可扩容到的上限（必须是 9 的倍数）"))
			.field("rows", ConfigSchema.integer(6, 1, 6, "一页显示几行（每行 9 格）；超过部分用分页查看"))
			.field("openCommand", ConfigSchema.stringList(List.of("storage", "warehouse"),
					"打开仓库的命令别名；第一个作为主命令，其余作为 /redtale 下的别名"))
			.field("blockItem", ConfigSchema.bool(false, "是否禁止存入潜影盒（防嵌套；false = 允许）"))
			.field("taskSubmitFromWarehouse", ConfigSchema.bool(true,
					"任务缴纳时优先扣仓库（背包不够再扣背包）"))
			.field("mailToWarehouse", ConfigSchema.bool(false, "邮件物品直接进仓库（R21 上线后生效）"))
			.field("accessPermission", ConfigSchema.string("",
					"访问所需的权限节点；留空 = 用 core.json 的 use 节点"))
			.field("upgrade", ConfigSchema.obj("扩容（R28）")
					.field("enabled", ConfigSchema.bool(true, "是否允许 /storage upgrade"))
					.field("steps", ConfigSchema.rawList(defaultUpgrades(),
							"扩容档位：[{slots:63, price:5000, premium:0}]；slots=目标格数，price=铜币，premium=赞助点"))
					.field("allowDowngrade", ConfigSchema.bool(false,
							"已购买格数超过 maxSlots 时是否自动缩容（缩容不退款，超出部分进暂存）")));

	private WarehouseConfig() {
	}

	public static ConfigStore store() {
		return ConfigManager.get().store(FILE);
	}

	public static boolean enabled() {
		return store().getBool("enabled", true);
	}

	public static int baseSlots() {
		return normalise((int) store().getLong("slots", 54));
	}

	public static int maxSlots() {
		int max = normalise((int) store().getLong("maxSlots", 108));
		return Math.max(baseSlots(), max);
	}

	public static int rows() {
		return Math.max(1, Math.min(6, (int) store().getLong("rows", 6)));
	}

	/** 一页的格数 = rows × 9。 */
	public static int pageSize() {
		return rows() * 9;
	}

	public static List<String> openCommands() {
		var el = store().getPath("openCommand");
		if (el == null || !el.isJsonArray() || el.getAsJsonArray().isEmpty()) return List.of("storage");
		java.util.List<String> out = new java.util.ArrayList<>();
		for (var e : el.getAsJsonArray()) {
			if (e.isJsonPrimitive()) {
				String s = e.getAsString().trim();
				if (!s.isEmpty()) out.add(s);
			}
		}
		return out.isEmpty() ? List.of("storage") : out;
	}

	public static boolean blockItem() {
		return store().getBool("blockItem", false);
	}

	public static boolean taskSubmitFromWarehouse() {
		return store().getBool("taskSubmitFromWarehouse", true);
	}

	public static boolean mailToWarehouse() {
		return store().getBool("mailToWarehouse", false);
	}

	public static String accessPermission() {
		return store().getString("accessPermission", "").trim();
	}

	public static boolean upgradeEnabled() {
		return store().getPathBool("upgrade.enabled", true);
	}

	public static boolean allowDowngrade() {
		return store().getPathBool("upgrade.allowDowngrade", false);
	}

	/** 一条扩容档位。 */
	public record Upgrade(int slots, long price, long premium) {
	}

	/** 按目标格数升序的档位表（非法项丢弃）。 */
	public static List<Upgrade> upgrades() {
		var el = store().getPath("upgrade.steps");
		java.util.List<Upgrade> out = new java.util.ArrayList<>();
		if (el != null && el.isJsonArray()) {
			for (var e : el.getAsJsonArray()) {
				if (!e.isJsonObject()) continue;
				JsonObject o = e.getAsJsonObject();
				if (!o.has("slots")) continue;
				int slots = normalise(o.get("slots").getAsInt());
				long price = o.has("price") ? Math.max(0, o.get("price").getAsLong()) : 0;
				long premium = o.has("premium") ? Math.max(0, o.get("premium").getAsLong()) : 0;
				out.add(new Upgrade(slots, price, premium));
			}
		}
		out.sort(java.util.Comparator.comparingInt(Upgrade::slots));
		return out;
	}

	/** 下一个可购买的档位；当前已到顶返回 null。 */
	public static Upgrade nextUpgrade(int currentSlots) {
		for (Upgrade u : upgrades()) {
			if (u.slots() > currentSlots && u.slots() <= maxSlots()) return u;
		}
		return null;
	}

	/** 向下取整到 9 的倍数并夹在 [9, maxSlots]。 */
	public static int normalise(int slots) {
		int v = (slots / 9) * 9;
		return Math.max(9, v);
	}
}
