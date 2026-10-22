package com.akira.redtale.economy.shop;

import com.akira.redtale.economy.RedtaleEconomy;
import com.akira.redtale.economy.config.ConfigManager;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * T09 每日轮换特价：每天从每家店挑 N 件商品打折，第二天自动换一批。
 *
 * <p>文件 {@code <config>/shop/rotation.json}：
 * <pre>
 * {"enabled": true, "count": 8, "percent": 30, "refreshHour": 4,
 *  "shops": ["*"], "salt": "s1"}
 * </pre>
 *
 * <p>为什么要「确定性挑选」而不是随机：重启/重载后必须还是同一批（否则玩家看到的价格会跳），
 * 也不能把整张商品表落盘。做法是给每件商品算 {@code hash(day, salt, id)} 排序取前 N ——
 * 只依赖（日期, 盐, id），与服务器重启次数、遍历顺序无关。
 *
 * <p>与 T08 的关系：两者取最大折扣，见 {@link #percentFor}。
 */
public final class ShopRotation {

	public static final String FILE = "shop/rotation.json";

	/** 配置缓存（见 {@link #reload()} 注释）；{@code null} = 未加载。 */
	private static volatile JsonObject CACHE;
	private static volatile Path CACHE_PATH;
	private static volatile List<String> SHOPS_CACHE;

	private ShopRotation() {
	}

	public static Path path() {
		return ConfigManager.get().baseDir().resolve(FILE);
	}

	// ---------------------------------------------------------------- 配置

	public static boolean enabled() {
		return bool("enabled", false);
	}

	public static int count() {
		return Math.max(1, Math.min(64, integer("count", 8)));
	}

	public static int percent() {
		return ShopDiscounts.clampPercent(integer("percent", 30));
	}

	public static int refreshHour() {
		return Math.max(0, Math.min(23, integer("refreshHour", 4)));
	}

	public static String salt() {
		try {
			JsonElement el = root().get("salt");
			return el == null || !el.isJsonPrimitive() ? "" : el.getAsString();
		} catch (RuntimeException e) {
			return "";
		}
	}

	public static List<String> shops() {
		List<String> cached = SHOPS_CACHE;
		if (cached != null) return cached;
		List<String> out = new ArrayList<>();
		try {
			JsonElement el = root().get("shops");
			if (el != null && el.isJsonArray()) {
				for (JsonElement x : el.getAsJsonArray()) {
					if (x.isJsonPrimitive()) out.add(x.getAsString());
				}
			}
		} catch (RuntimeException ignored) {
			// 配置坏了就是「全部商店」
		}
		List<String> built = out.isEmpty() ? List.of("*") : List.copyOf(out);
		SHOPS_CACHE = built;
		return built;
	}

	/**
	 * 清缓存：{@code ShopCatalog.reload()}（含 {@code /shop reload}）调用。
	 *
	 * <p>★为什么必须缓存★：{@link #root()} 原来每次都 {@code Files.isRegularFile + readString +
	 * JsonParser.parseString}，而 {@code enabled()}/{@code coversShop()} 在
	 * {@code ShopCatalog.filtered()} 里是<b>逐商品</b>调用的 —— 一页 40 件就是 80 次磁盘
	 * stat（+ 文件存在时 80 次读盘解析），2803 件的店翻 71 页 = 约 40 万次 syscall。
	 * 实测 {@code ShopPagingPerfTest} 翻完全库 2378ms（预算 1000ms）。
	 */
	public static void reload() {
		CACHE = null;
		CACHE_PATH = null;
		SHOPS_CACHE = null;
	}

	private static JsonObject root() {
		Path f = path();
		JsonObject c = CACHE;
		if (c != null && f.equals(CACHE_PATH)) return c;
		synchronized (ShopRotation.class) {
			if (CACHE != null && f.equals(CACHE_PATH)) return CACHE;
			CACHE = read(f);
			CACHE_PATH = f;
			SHOPS_CACHE = null;
			return CACHE;
		}
	}

	/** 真正读盘（只在缓存未命中时走这里）。 */
	private static JsonObject read(Path f) {
		try {
			if (!Files.isRegularFile(f)) return new JsonObject();
			JsonElement el = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8));
			return el.isJsonObject() ? el.getAsJsonObject() : new JsonObject();
		} catch (Exception e) {
			RedtaleEconomy.LOGGER.error("[shop] 读取轮换配置失败：{}", e.getMessage());
			return new JsonObject();
		}
	}

	private static boolean bool(String key, boolean def) {
		try {
			JsonElement el = root().get(key);
			return el == null || !el.isJsonPrimitive() ? def : el.getAsBoolean();
		} catch (RuntimeException e) {
			return def;
		}
	}

	private static int integer(String key, int def) {
		try {
			JsonElement el = root().get(key);
			return el == null || !el.isJsonPrimitive() ? def : el.getAsInt();
		} catch (RuntimeException e) {
			return def;
		}
	}

	// ---------------------------------------------------------------- 轮换

	/** 业务日期（按 refreshHour 切日）→ 用于挑选的整数天序号。 */
	public static long dayIndex(long nowMs, int refreshHour) {
		int hour = Math.max(0, Math.min(23, refreshHour));
		// 把「刷新时刻」平移到零点：04:00 刷新时，03:59 仍算前一天
		long shifted = nowMs - hour * 3_600_000L;
		return java.time.Instant.ofEpochMilli(shifted).atZone(ZoneId.systemDefault()).toLocalDate().toEpochDay();
	}

	/**
	 * 挑选当天的特价 id 集合（纯函数）。
	 *
	 * <p>入参 id 顺序不影响结果：内部先排序，否则「商店文件里的书写顺序」会变成选品变量。
	 */
	public static Set<String> pick(List<String> ids, long day, int count, String salt) {
		Set<String> out = new LinkedHashSet<>();
		if (ids == null || ids.isEmpty() || count <= 0) return out;
		List<String> sorted = new ArrayList<>(new LinkedHashSet<>(ids));
		sorted.removeIf(x -> x == null || x.isBlank());
		sorted.sort(Comparator.naturalOrder());
		sorted.sort(Comparator.comparingLong(id -> score(day, salt, id)));
		for (String id : sorted) {
			if (out.size() >= count) break;
			out.add(id);
		}
		return out;
	}

	/** 与实现无关的稳定打分（乘法混合，避免 String.hashCode 在相邻日期上聚簇）。 */
	static long score(long day, String salt, String id) {
		long h = 1125899906842597L;
		String s = day + "\u0000" + (salt == null ? "" : salt) + "\u0000" + id;
		for (int i = 0; i < s.length(); i++) {
			h = 31 * h + s.charAt(i);
		}
		h ^= (h >>> 33);
		h *= 0xff51afd7ed558ccdL;
		h ^= (h >>> 33);
		return h;
	}

	/** 该店当前是否参与轮换（{@code shops} 含 {@code "*"} 或店 id）。 */
	public static boolean coversShop(String shopId) {
		List<String> s = shops();
		return s.contains("*") || (shopId != null && s.contains(shopId));
	}

	/** 今天这家店这台商品的特价折扣；不命中返回 0。 */
	public static int percentFor(String shopId, String itemId, List<String> shopItemIds, long nowMs) {
		if (!enabled() || !coversShop(shopId)) return 0;
		int hour = refreshHour();
		long day = dayIndex(nowMs, hour);
		if (!pick(shopItemIds, day, count(), salt()).contains(itemId)) return 0;
		return percent();
	}
}
