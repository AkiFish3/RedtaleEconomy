package com.akira.redtale.economy.storage;

import com.akira.redtale.economy.RedtaleEconomy;
import com.akira.redtale.economy.config.CoreConfig;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * T27 经济事件日历：在指定时间窗内给某类收益/价格上倍率。
 *
 * <p>配置住在 {@code core.json → calendar}（不新建配置文件：新文件必须在根主类 define，
 * 而根主类不在 A 队列白名单）：
 * <pre>
 * "calendar": [
 *   {"id":"xmas","name":"圣诞双倍","from":"2026-12-24","to":"2026-12-26","multiplier":2.0,
 *    "scopes":["battlepass","checkin"]}
 * ]
 * </pre>
 *
 * <p>日期两种写法：{@code yyyy-MM-dd}（含当天，结束日按 23:59:59）或 ISO 本地时间
 * （{@code 2026-12-24T20:00}）。写错的事件**跳过而不是抛**：一个错字不该让整个日历失效。
 * 同一 scope 多个事件重叠时取<b>最大倍率</b>（不叠乘，避免 2×2=4 的意外）。
 */
public final class EconomyCalendar {

	/** 一条事件。{@code scopes} 为空 = 对所有 scope 生效。 */
	public record Event(String id, String name, LocalDateTime from, LocalDateTime to,
			double multiplier, Set<String> scopes) {

		public boolean covers(String scope) {
			if (scopes.isEmpty()) return true;
			return scope != null && scopes.contains(scope.toLowerCase(Locale.ROOT));
		}

		public boolean activeAt(LocalDateTime now) {
			return now != null && !now.isBefore(from) && !now.isAfter(to);
		}

		public boolean before(LocalDateTime now) {
			return now != null && now.isBefore(from);
		}
	}

	/** 命中的事件（倍率最高者）。{@code label} 供界面显示，未命中时为空串。 */
	public record Hit(Event event, double multiplier) {
		public boolean active() {
			return event != null;
		}

		public String name() {
			return event == null ? "" : event.name();
		}

		public static Hit none() {
			return new Hit(null, 1.0);
		}
	}

	private EconomyCalendar() {
	}

	/** 读配置（坏配置返回空表）。 */
	public static List<Event> list() {
		try {
			JsonElement el = CoreConfig.store().getPath("calendar");
			return el != null && el.isJsonArray() ? parse(el.getAsJsonArray()) : List.of();
		} catch (Exception e) {
			return List.of();
		}
	}

	/** 解析事件数组（纯函数，单测入口）。 */
	public static List<Event> parse(JsonArray arr) {
		List<Event> out = new ArrayList<>();
		if (arr == null) return out;
		for (JsonElement e : arr) {
			if (e == null || !e.isJsonObject()) continue;
			JsonObject o = e.getAsJsonObject();
			try {
				String id = str(o, "id");
				String name = str(o, "name");
				LocalDateTime from = time(o.has("from") ? str(o, "from") : "", false);
				LocalDateTime to = time(o.has("to") ? str(o, "to") : "", true);
				double mult = o.has("multiplier") ? o.get("multiplier").getAsDouble() : 1.0;
				if (from == null || to == null || to.isBefore(from)) continue;
				if (!(mult > 0) || mult == 1.0) continue;   // 1.0 = 无效果，不入表
				Set<String> scopes = new LinkedHashSet<>();
				if (o.has("scopes") && o.get("scopes").isJsonArray()) {
					for (JsonElement s : o.getAsJsonArray("scopes")) {
						if (s != null && s.isJsonPrimitive()) scopes.add(s.getAsString().toLowerCase(Locale.ROOT));
					}
				}
				out.add(new Event(id.isEmpty() ? "event" : id, name.isEmpty() ? id : name,
						from, to, Math.min(10.0, mult), scopes));
			} catch (Exception bad) {
				RedtaleEconomy.LOGGER.warn("[calendar] 跳过一条坏事件：{}", bad.getMessage());
			}
		}
		return out;
	}

	/** 当前生效的事件（同 scope 取最大倍率；没有则 {@link Hit#none()}）。 */
	public static Hit at(JsonArray arr, String scope, LocalDateTime now) {
		Event best = null;
		for (Event e : parse(arr)) {
			if (!e.covers(scope) || !e.activeAt(now)) continue;
			if (best == null || e.multiplier() > best.multiplier()) best = e;
		}
		return best == null ? Hit.none() : new Hit(best, best.multiplier());
	}

	/** 从配置取倍率（生产调用点）。 */
	public static Hit hit(String scope) {
		try {
			JsonElement el = CoreConfig.store().getPath("calendar");
			JsonArray arr = el != null && el.isJsonArray() ? el.getAsJsonArray() : new JsonArray();
			return at(arr, scope, LocalDateTime.now());
		} catch (Exception e) {
			return Hit.none();
		}
	}

	/** 便捷取倍率（无事件 = 1.0）。 */
	public static double multiplier(String scope) {
		return hit(scope).multiplier();
	}

	/** 应用倍率到金额/经验（四舍五入，至少 1 —— 倍率只放大不吞）。 */
	public static long apply(long base, double multiplier) {
		if (base <= 0) return base;
		if (!(multiplier > 1.0)) return base;
		return Math.max(1, Math.round(base * multiplier));
	}

	/** 日期/时间解析：{@code yyyy-MM-dd} 或 ISO 本地时间；结束日按当天最后一秒。 */
	static LocalDateTime time(String raw, boolean endOfDay) {
		if (raw == null || raw.isBlank()) return null;
		String s = raw.trim();
		try {
			if (s.length() <= 10) {
				LocalDate d = LocalDate.parse(s, DateTimeFormatter.ISO_LOCAL_DATE);
				return LocalDateTime.of(d, endOfDay ? LocalTime.of(23, 59, 59) : LocalTime.MIDNIGHT);
			}
			return LocalDateTime.parse(s, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
		} catch (Exception e) {
			return null;
		}
	}

	private static String str(JsonObject o, String key) {
		JsonElement e = o.get(key);
		return e == null || !e.isJsonPrimitive() ? "" : e.getAsString();
	}
}
