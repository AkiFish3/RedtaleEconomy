package com.akira.redtale.economy.shop;

import com.akira.redtale.economy.RedtaleEconomy;
import com.akira.redtale.economy.config.ConfigManager;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * T08 商店限时折扣：按时间段覆盖<b>买入价</b>（卖出价不动，避免「折扣期间倒卖套利」）。
 *
 * <p>文件 {@code <config>/shop/discounts.json}：
 * <pre>
 * {
 *   "windows": [
 *     {"id": "weekend", "from": "2026-10-24T00:00", "to": "2026-10-27T00:00",
 *      "percent": 20, "shops": ["*"], "items": ["*"]},
 *     {"id": "evening", "dailyFrom": "20:00", "dailyTo": "22:00",
 *      "percent": 10, "shops": ["general"], "items": ["*"]}
 *   ]
 * }
 * </pre>
 *
 * <p>口径（全部有单测）：
 * <ul>
 *   <li>区间 {@code [from, to)}：起点含、终点不含 —— 否则两个相邻活动会在交界那一秒同时生效。</li>
 *   <li>{@code shops}/{@code items} 支持 {@code "*"} 通配；空列表按 {@code "*"} 处理（少写一个字段不该等于不生效）。</li>
 *   <li>多个窗口命中取<b>最大</b>折扣（对玩家最有利，且结果与文件顺序无关）。</li>
 *   <li>{@code percent} 钳 {{1..90}}：0 等于没打折、100 等于白送，都不允许。</li>
 *   <li>{@code dailyFrom/dailyTo} 支持跨零点（22:00→02:00）。</li>
 * </ul>
 */
public final class ShopDiscounts {

	public static final String FILE = "shop/discounts.json";

	/** 一个折扣窗口。时间都是 epoch millis（{@code daily*} 用纳秒字段 0 的毫秒数表示当天时刻）。 */
	public record Window(String id, long from, long to, Integer dailyFrom, Integer dailyTo,
			int percent, List<String> shops, List<String> items, boolean enabled) {

		boolean hitsShop(String shopId) {
			return shops.isEmpty() || shops.contains("*") || shops.contains(shopId);
		}

		boolean hitsItem(String itemId) {
			return items.isEmpty() || items.contains("*") || items.contains(itemId);
		}
	}

	private static final Gson GSON = new Gson();
	private static volatile List<Window> WINDOWS = List.of();

	private ShopDiscounts() {
	}

	public static Path path() {
		return ConfigManager.get().baseDir().resolve(FILE);
	}

	/** 重载（{@code /shop admin discount reload} 与 ShopCatalog.reload 都会调）。 */
	public static synchronized void reload() {
		Path f = path();
		if (!Files.isRegularFile(f)) {
			WINDOWS = List.of();
			return;
		}
		try {
			JsonElement el = com.google.gson.JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8));
			WINDOWS = parse(el);
			RedtaleEconomy.LOGGER.info("[shop] 折扣窗口 {} 个（{}）", WINDOWS.size(), f.getFileName());
		} catch (Exception e) {
			WINDOWS = List.of();
			RedtaleEconomy.LOGGER.error("[shop] 读取折扣表 {} 失败，按无折扣处理：{}", f, e.getMessage());
		}
	}

	/** 纯解析：坏条目逐条跳过并计数，不影响其他条目。 */
	public static List<Window> parse(JsonElement root) {
		List<Window> out = new ArrayList<>();
		if (root == null || !root.isJsonObject()) return out;
		JsonObject o = root.getAsJsonObject();
		JsonElement arrEl = o.get("windows");
		if (arrEl == null || !arrEl.isJsonArray()) return out;
		JsonArray arr = arrEl.getAsJsonArray();
		for (JsonElement e : arr) {
			if (e == null || !e.isJsonObject()) continue;
			JsonObject w = e.getAsJsonObject();
			String id = w.has("id") ? w.get("id").getAsString() : "";
			int raw = w.has("percent") ? w.get("percent").getAsInt() : 0;
			// 先判原始值再钳制：钳制会把 0 变成 1，等于把「写错成 0」变成 1% 的折扣
			if (raw <= 0) continue;
			int percent = clampPercent(raw);
			boolean enabled = !w.has("enabled") || w.get("enabled").getAsBoolean();
			List<String> shops = strings(w, "shops");
			List<String> items = strings(w, "items");
			Integer df = w.has("dailyFrom") ? minuteOfDay(w.get("dailyFrom").getAsString()) : null;
			Integer dt = w.has("dailyTo") ? minuteOfDay(w.get("dailyTo").getAsString()) : null;
			long from = w.has("from") ? millis(w.get("from").getAsString()) : Long.MIN_VALUE;
			long to = w.has("to") ? millis(w.get("to").getAsString()) : Long.MAX_VALUE;
			if (df == null && (from == Long.MIN_VALUE || to == Long.MAX_VALUE)) continue;
			out.add(new Window(id, from, to, df, dt, percent, shops, items, enabled));
		}
		return out;
	}

	public static int clampPercent(int p) {
		return Math.max(1, Math.min(90, p));
	}

	/** 当前生效的最大折扣百分比；没有命中返回 0。 */
	public static int percentFor(String shopId, String itemId, long nowMs) {
		int best = 0;
		for (Window w : WINDOWS) {
			if (!w.enabled()) continue;
			if (!w.hitsShop(shopId) || !w.hitsItem(itemId)) continue;
			if (!active(w, nowMs)) continue;
			if (w.percent() > best) best = w.percent();
		}
		return best;
	}

	/** 打折扣的买入价（向下取整，最低 1）。{@code base<=0} 表示不可购买，原样返回。 */
	public static long applyBuy(String shopId, String itemId, long base, long nowMs) {
		return priceWith(base, percentFor(shopId, itemId, nowMs));
	}

	/** 按百分比折算（T08/T09 共用同一套取整口径，避免两处各写一份算出不同价）。 */
	public static long priceWith(long base, int percent) {
		if (base <= 0 || percent <= 0) return base;
		return Math.max(1L, base * (100L - Math.min(90, percent)) / 100L);
	}

	/** 纯区间判定：{@code [from,to)}，支持 daily 跨零点。 */
	public static boolean active(Window w, long nowMs) {
		if (w == null) return false;
		if (w.dailyFrom() != null && w.dailyTo() != null) {
			LocalDateTime dt = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(nowMs), ZoneId.systemDefault());
			int now = dt.getHour() * 60 + dt.getMinute();
			int a = w.dailyFrom();
			int b = w.dailyTo();
			return a <= b ? (now >= a && now < b) : (now >= a || now < b);
		}
		return nowMs >= w.from() && nowMs < w.to();
	}

	/** 诊断用：当前生效的窗口 id 列表。 */
	public static List<String> activeIds(long nowMs) {
		List<String> out = new ArrayList<>();
		for (Window w : WINDOWS) {
			if (w.enabled() && active(w, nowMs)) out.add(w.id());
		}
		return out;
	}

	public static int windowCount() {
		return WINDOWS.size();
	}

	// ---------------------------------------------------------------- 解析工具

	private static List<String> strings(JsonObject o, String key) {
		List<String> out = new ArrayList<>();
		JsonElement e = o.get(key);
		if (e != null && e.isJsonArray()) {
			for (JsonElement x : e.getAsJsonArray()) {
				if (x.isJsonPrimitive()) out.add(x.getAsString());
			}
		}
		return out;
	}

	/** {@code yyyy-MM-dd} 或 {@code yyyy-MM-ddTHH:mm}（本地时区）。解析失败返回 {@link Long#MIN_VALUE}。 */
	public static long millis(String raw) {
		if (raw == null || raw.isBlank()) return Long.MIN_VALUE;
		String s = raw.trim();
		try {
			if (s.length() <= 10) {
				return LocalDate.parse(s).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
			}
			return LocalDateTime.parse(s).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
		} catch (DateTimeParseException e) {
			return Long.MIN_VALUE;
		}
	}

	/** {@code HH:mm} → 当天第几分钟；非法返回 null。 */
	public static Integer minuteOfDay(String raw) {
		if (raw == null || raw.isBlank()) return null;
		try {
			LocalTime t = LocalTime.parse(raw.trim());
			return t.getHour() * 60 + t.getMinute();
		} catch (DateTimeParseException e) {
			return null;
		}
	}

	/** 测试用：直接注入窗口表。 */
	static void setForTest(List<Window> windows) {
		WINDOWS = windows == null ? List.of() : windows;
	}

	/** 测试用：Gson→Window 的便捷入口。 */
	static List<Window> parseForTest(String json) {
		return parse(com.google.gson.JsonParser.parseString(json));
	}

	static String toJsonForDebug() {
		return GSON.toJson(WINDOWS);
	}
}
