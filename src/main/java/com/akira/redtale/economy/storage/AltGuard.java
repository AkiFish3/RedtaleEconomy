package com.akira.redtale.economy.storage;

import com.akira.redtale.economy.RedtaleEconomy;
import com.akira.redtale.economy.config.AntiAltConfig;
import com.akira.redtale.economy.util.DataRoot;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * T18 防小号：记录「UUID ↔ 最近一次登录 IP」，在领取奖励前判断该 IP 是否已被多个账号使用。
 *
 * <ul>
 *   <li>只拦<b>新面孔</b>：已登记的 UUID 永远放行，避免把老玩家关在门外（误杀代价比刷奖励高）。</li>
 *   <li>无 IP（离线/未连接）一律放行 —— 判定不了就不要拦。</li>
 *   <li>默认 {@code logOnly}：先记日志看误杀率，服主确认后关掉才真拦。</li>
 *   <li>落盘 {@code <数据根>/security/ipmap.json}，5 秒内多次调用只写一次；保留
 *       {@code max(cooldown*2, 72h)} 内的记录，文件不会无限长。</li>
 * </ul>
 *
 * <p>{@link #evaluate} 是纯函数（不读配置不碰盘），单测直接喂表。
 */
public final class AltGuard {

	private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
	private static final Map<String, Rec> RECS = new ConcurrentHashMap<>();
	private static final long SAVE_INTERVAL_MS = 5_000L;

	private static volatile boolean loaded;
	private static volatile long lastSave;

	/** 一条记录：最近一次见到的 IP + 名字 + 时间戳。 */
	public record Rec(String ip, String name, long lastSeen) {
	}

	public record Decision(boolean allowed, String reason) {
	}

	public static final Decision ALLOW = new Decision(true, "");

	private AltGuard() {
	}

	/**
	 * 领取前的统一门禁：判定 + 记录。返回 {@code allowed=false} 时调用方必须放弃发奖，
	 * 且不要把物品先扣走（顺序错了等于吞玩家物品）。
	 */
	public static Decision gate(UUID uuid, String rawIp, String scope) {
		if (uuid == null) return ALLOW;
		String ip = normalizeIp(rawIp);
		boolean enabled = AntiAltConfig.enabled();
		boolean inScope = enabled && AntiAltConfig.scopes().contains(scope);
		Decision d = ALLOW;
		if (inScope && !ip.isEmpty()) {
			loadIfNeeded();
			long now = System.currentTimeMillis();
			int cooldown = AntiAltConfig.cooldownHours();
			int max = AntiAltConfig.maxAccountsPerIp();
			d = evaluate(RECS, uuid.toString(), ip, now, cooldown, max);
			if (!d.allowed()) {
				if (AntiAltConfig.logOnly()) {
					RedtaleEconomy.LOGGER.warn("[anti-alt] {} 触发（scope={}，logOnly 未拦截）：{}",
							uuid, scope, d.reason());
					d = ALLOW;
				} else {
					RedtaleEconomy.LOGGER.info("[anti-alt] 拒绝 {} 的 {}：{}", uuid, scope, d.reason());
				}
			}
			// T15：真拦截（非 logOnly）才推外部告警；logOnly 只在本机日志留痕
			if (!d.allowed()) {
				Webhook.notify("alt.block", "玩家 " + uuid + " 的 " + scope + " 被拒：" + d.reason());
			}
		}
		observe(uuid, ip, null);
		return d;
	}

	/** 记录一次出现（登录/领取都算）。{@code ip} 为空时只刷新时间戳，不覆盖已有 IP。 */
	public static void observe(UUID uuid, String rawIp, String name) {
		if (uuid == null) return;
		loadIfNeeded();
		String ip = normalizeIp(rawIp);
		String key = uuid.toString();
		long now = System.currentTimeMillis();
		RECS.compute(key, (k, old) -> {
			String useIp = ip.isEmpty() ? (old == null ? "" : old.ip()) : ip;
			String useName = name == null || name.isBlank() ? (old == null ? "" : old.name()) : name;
			return new Rec(useIp, useName, now);
		});
		maybeSave(now);
	}

	/**
	 * 纯判定：该 IP 在窗口内的不同 UUID 数（含本人）超过 {@code maxAccounts} 且本人是新面孔 → 拒绝。
	 *
	 * <p>「含本人」很关键：{@code maxAccounts=3} 表示「这个 IP 最多 3 个号」，
	 * 用 {@code >} 而不是 {@code >=} 才对得上配置说明。
	 */
	public static Decision evaluate(Map<String, Rec> table, String uuid, String ip, long now,
			int cooldownHours, int maxAccounts) {
		if (table == null || ip == null || ip.isEmpty() || uuid == null) return ALLOW;
		long window = Math.max(1, cooldownHours) * 3_600_000L;
		// 有记录就算「老面孔」：换 IP、断网重连都不该被关在门外
		if (table.containsKey(uuid)) return ALLOW;
		Set<String> seen = new LinkedHashSet<>();
		for (Map.Entry<String, Rec> e : table.entrySet()) {
			Rec r = e.getValue();
			if (r == null || r.ip() == null || r.ip().isEmpty()) continue;
			if (now - r.lastSeen() > window) continue;
			if (!r.ip().equals(ip)) continue;
			seen.add(e.getKey());
		}
		if (seen.size() + 1 > Math.max(1, maxAccounts)) {
			return new Decision(false, "同一 IP（" + ip + "）近 " + cooldownHours
					+ " 小时内已有 " + seen.size() + " 个账号领取，上限 " + maxAccounts);
		}
		return ALLOW;
	}

	/** {@code /127.0.0.1} → {@code 127.0.0.1}；空值/未知返回空串。 */
	public static String normalizeIp(String raw) {
		if (raw == null) return "";
		String s = raw.trim();
		while (s.startsWith("/")) s = s.substring(1);
		if (s.isEmpty() || "unknown".equalsIgnoreCase(s)) return "";
		return s;
	}

	// ---------------------------------------------------------------- 落盘

	static Path file() {
		try {
			return DataRoot.path().resolve("security/ipmap.json");
		} catch (RuntimeException e) {
			return null;
		}
	}

	private static synchronized void loadIfNeeded() {
		if (loaded) return;
		loaded = true;
		Path f = file();
		if (f == null || !Files.isRegularFile(f)) return;
		try {
			JsonElement el = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8));
			if (!el.isJsonObject()) return;
			JsonObject players = el.getAsJsonObject().has("players")
					? el.getAsJsonObject().getAsJsonObject("players") : null;
			if (players == null) return;
			for (String key : players.keySet()) {
				JsonElement v = players.get(key);
				if (v == null || !v.isJsonObject()) continue;
				JsonObject o = v.getAsJsonObject();
				RECS.put(key, new Rec(
						o.has("ip") ? o.get("ip").getAsString() : "",
						o.has("name") ? o.get("name").getAsString() : "",
						o.has("lastSeen") ? o.get("lastSeen").getAsLong() : 0L));
			}
			RedtaleEconomy.LOGGER.info("[anti-alt] 已载入 {} 条 IP 记录", RECS.size());
		} catch (Exception e) {
			RedtaleEconomy.LOGGER.error("[anti-alt] 读取 {} 失败：{}", f, e.getMessage());
		}
	}

	private static void maybeSave(long now) {
		if (now - lastSave < SAVE_INTERVAL_MS) return;
		saveNow();
	}

	public static synchronized void saveNow() {
		Path f = file();
		if (f == null) return;
		try {
			int cooldown = Math.max(1, AntiAltConfig.cooldownHours());
			long keep = Math.max(cooldown * 2L, 72L) * 3_600_000L;
			long now = System.currentTimeMillis();
			JsonObject players = new JsonObject();
			for (Map.Entry<String, Rec> e : new HashMap<>(RECS).entrySet()) {
				Rec r = e.getValue();
				if (r == null || now - r.lastSeen() > keep) {
					RECS.remove(e.getKey());
					continue;
				}
				JsonObject o = new JsonObject();
				o.addProperty("ip", r.ip());
				o.addProperty("name", r.name());
				o.addProperty("lastSeen", r.lastSeen());
				players.add(e.getKey(), o);
			}
			JsonObject root = new JsonObject();
			root.addProperty("_version", 1);
			root.add("players", players);
			Files.createDirectories(f.getParent());
			Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
			Files.writeString(tmp, GSON.toJson(root), StandardCharsets.UTF_8);
			try {
				Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (AtomicMoveNotSupportedException e) {
				Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
			}
			lastSave = now;
		} catch (Exception e) {
			RedtaleEconomy.LOGGER.error("[anti-alt] 写入失败：{}", e.getMessage());
		}
	}

	/** 测试用：清空内存表与载入标记。 */
	public static synchronized void resetForTest() {
		RECS.clear();
		loaded = false;
		lastSave = 0L;
	}

	/** 测试用：当前内存表快照。 */
	public static Map<String, Rec> snapshot() {
		return new HashMap<>(RECS);
	}
}
