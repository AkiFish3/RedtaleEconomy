package com.akira.redtale.economy.module.impl;

import com.akira.redtale.economy.RedtaleEconomy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * T03 历史赛季补领：读 {@code <赛季>/_snapshot.json} 的冻结视图，按 accessMode 决定谁能补领。
 *
 * <p>为什么不复用 {@link BattlePassModule} 的 claimed 集合：归档赛季的玩家档是<b>冻结证据</b>，
 * 补领如果写回原档，就把「当季实际领了什么」和「事后补偿了什么」混成一份数据，
 * 事后查账分不清。所以补领状态单独写 {@code <赛季>/_claims.json}：
 * <pre>
 * {"_version":1,"season":"s1","players":{"&lt;uuid&gt;":{"free":[3,4],"premium":[1]}}}
 * </pre>
 *
 * <p>三档 accessMode（配置 {@code battlepass.json → archive.accessMode}）：
 * <ul>
 *   <li>{@code off}：默认，历史赛季不可补领；</li>
 *   <li>{@code purchased}：只有当季<b>确实买过付费轨</b>的人可补领（含免费轨）；</li>
 *   <li>{@code all}：当季有存档的人都能补领，但付费轨仍要求当季是付费玩家。</li>
 * </ul>
 *
 * <p>本类除日志外不碰 MC API，可直接用临时目录做单测。
 */
public final class PassArchive {

	public static final String CLAIMS = "_claims.json";
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

	/** 补领权限档位。 */
	public enum Access {
		OFF, PURCHASED, ALL;

		/** 宽容解析：未知值一律 off（宁可不开，也不要误开历史补偿）。 */
		public static Access parse(String raw) {
			if (raw == null) return OFF;
			switch (raw.trim().toLowerCase(Locale.ROOT)) {
				case "purchased":
				case "paid":
				case "premium":
					return PURCHASED;
				case "all":
				case "everyone":
					return ALL;
				default:
					return OFF;
			}
		}
	}

	/** 快照里的一行（只取补领需要的字段）。 */
	public record Entry(UUID uuid, long xp, boolean premium) {
	}

	/** 一次补领的结果。{@code CURRENT} = 目标就是当前赛季，应走正常 {@code /pass claim}。 */
	public enum Result {
		OK, OFF, CURRENT, NO_SNAPSHOT, NOT_FOUND, NOT_ENTITLED, LOCKED, NOT_REACHED, ALREADY, BAD_LEVEL, ERROR
	}

	/** 等级曲线（生产环境传 {@code BattlePassModule::levelFor}，单测传线性函数）。 */
	public interface LevelCurve {
		int levelFor(long xp);
	}

	private PassArchive() {
	}

	// ---------------------------------------------------------------- 快照

	public static Path snapshotPath(Path seasonDir) {
		return seasonDir == null ? null : seasonDir.resolve(PassSeasons.SNAPSHOT);
	}

	public static Path claimsPath(Path seasonDir) {
		return seasonDir == null ? null : seasonDir.resolve(CLAIMS);
	}

	/** 该赛季是否已归档（判据：快照文件存在）。 */
	public static boolean archived(Path seasonDir) {
		Path f = snapshotPath(seasonDir);
		return f != null && Files.isRegularFile(f);
	}

	/** 读快照里的全部玩家行；快照不存在或坏档返回空列表。 */
	public static List<Entry> entries(Path seasonDir) {
		List<Entry> out = new ArrayList<>();
		Path f = snapshotPath(seasonDir);
		if (f == null || !Files.isRegularFile(f)) return out;
		try {
			JsonElement el = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8));
			if (!el.isJsonObject()) return out;
			JsonElement arr = el.getAsJsonObject().get("entries");
			if (arr == null || !arr.isJsonArray()) return out;
			for (JsonElement e : arr.getAsJsonArray()) {
				if (e == null || !e.isJsonObject()) continue;
				JsonObject o = e.getAsJsonObject();
				UUID id = parseUuid(str(o, "uuid"));
				if (id == null) continue;
				out.add(new Entry(id, o.has("xp") ? Math.max(0, o.get("xp").getAsLong()) : 0L,
						o.has("premium") && o.get("premium").getAsBoolean()));
			}
		} catch (Exception e) {
			RedtaleEconomy.LOGGER.error("[pass] 读取赛季快照失败：{}", e.getMessage());
		}
		return out;
	}

	/** 该玩家在该赛季的快照行（没有返回 null ⇒ 当季没数据，不能补领）。 */
	public static Entry entryOf(Path seasonDir, UUID uuid) {
		if (uuid == null) return null;
		for (Entry e : entries(seasonDir)) {
			if (uuid.equals(e.uuid())) return e;
		}
		return null;
	}

	/** 权限判定：off 全拒；purchased 只认当季付费玩家；all 认有存档的人。 */
	public static boolean entitled(Access access, Entry entry) {
		if (access == null || access == Access.OFF || entry == null) return false;
		if (access == Access.PURCHASED) return entry.premium();
		return true;
	}

	// ---------------------------------------------------------------- 补领状态

	/** 已补领的等级集合（track = free / premium）。 */
	public static Set<Integer> claimed(Path seasonDir, UUID uuid, String track) {
		Set<Integer> out = new LinkedHashSet<>();
		JsonObject all = readClaims(seasonDir);
		if (all == null || uuid == null) return out;
		JsonObject players = obj(all, "players");
		if (players == null) return out;
		JsonObject me = obj(players, uuid.toString());
		if (me == null) return out;
		JsonElement arr = me.get(isPremiumTrack(track) ? "premium" : "free");
		if (arr == null || !arr.isJsonArray()) return out;
		for (JsonElement e : arr.getAsJsonArray()) {
			if (e != null && e.isJsonPrimitive()) out.add(e.getAsInt());
		}
		return out;
	}

	/**
	 * 列出该玩家还能补领的等级（升序）。
	 *
	 * <p>付费轨还要过「当季 premium」这一关：{@code access=all} 只放宽"谁能补"，
	 * 不放宽"能拿哪一轨"。权限不足时返回空表，调用方据此显示 0 而不是报错。
	 */
	public static List<Integer> claimable(Path seasonDir, UUID uuid, Access access, LevelCurve curve,
			int maxLevel) {
		List<Integer> out = new ArrayList<>();
		Entry entry = entryOf(seasonDir, uuid);
		if (!entitled(access, entry) || curve == null) return out;
		int reached = Math.min(Math.max(1, maxLevel), Math.max(1, curve.levelFor(entry.xp())));
		Set<Integer> free = claimed(seasonDir, uuid, "free");
		for (int lv = 1; lv <= reached; lv++) {
			if (!free.contains(lv)) out.add(lv);
		}
		return out;
	}

	/**
	 * 补领一个等级。**先写状态再发奖**由调用方负责：本方法只记「已领」，
	 * 返回 OK 之后才允许 {@code grant}，这样即使发奖中途崩掉，最坏是少发而不是重复发。
	 */
	public static synchronized Result claim(Path seasonDir, UUID uuid, String track, int level,
			Access access, LevelCurve curve, int maxLevel, long nowMs) {
		if (uuid == null || seasonDir == null) return Result.ERROR;
		if (access == null || access == Access.OFF) return Result.OFF;
		if (level < 1 || level > Math.max(1, maxLevel)) return Result.BAD_LEVEL;
		if (!archived(seasonDir)) return Result.NO_SNAPSHOT;
		Entry entry = entryOf(seasonDir, uuid);
		if (entry == null) return Result.NOT_FOUND;
		if (!entitled(access, entry)) return Result.NOT_ENTITLED;
		boolean premium = isPremiumTrack(track);
		if (premium && !entry.premium()) return Result.LOCKED;
		if (curve == null || level > curve.levelFor(entry.xp())) return Result.NOT_REACHED;

		JsonObject all = readClaims(seasonDir);
		if (all == null) all = new JsonObject();
		all.addProperty("_version", 1);
		all.addProperty("season", seasonDir.getFileName() == null ? "" : seasonDir.getFileName().toString());
		JsonObject players = obj(all, "players");
		if (players == null) {
			players = new JsonObject();
			all.add("players", players);
		}
		String key = uuid.toString();
		JsonObject me = obj(players, key);
		if (me == null) {
			me = new JsonObject();
			players.add(key, me);
		}
		String field = premium ? "premium" : "free";
		JsonArray arr = me.has(field) && me.get(field).isJsonArray()
				? me.getAsJsonArray(field) : new JsonArray();
		for (JsonElement e : arr) {
			if (e != null && e.isJsonPrimitive() && e.getAsInt() == level) return Result.ALREADY;
		}
		arr.add(level);
		me.add(field, arr);
		me.addProperty("lastAt", nowMs);
		if (!writeClaims(seasonDir, all)) return Result.ERROR;
		return Result.OK;
	}

	// ---------------------------------------------------------------- IO

	private static JsonObject readClaims(Path seasonDir) {
		Path f = claimsPath(seasonDir);
		if (f == null || !Files.isRegularFile(f)) return new JsonObject();
		try {
			JsonElement el = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8));
			return el.isJsonObject() ? el.getAsJsonObject() : new JsonObject();
		} catch (Exception e) {
			// 坏档当空处理：宁可让玩家重领一次，也不要因为一行坏 json 让整个补领永久不可用
			RedtaleEconomy.LOGGER.error("[pass] 读取补领状态失败（按空处理）：{}", e.getMessage());
			return new JsonObject();
		}
	}

	private static boolean writeClaims(Path seasonDir, JsonObject o) {
		Path f = claimsPath(seasonDir);
		if (f == null) return false;
		try {
			Files.createDirectories(f.getParent());
			Path tmp = f.resolveSibling(CLAIMS + ".tmp");
			Files.writeString(tmp, GSON.toJson(o), StandardCharsets.UTF_8);
			try {
				Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (AtomicMoveNotSupportedException notAtomic) {
				Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
			}
			return true;
		} catch (Exception e) {
			RedtaleEconomy.LOGGER.error("[pass] 写补领状态失败：{}", e.getMessage());
			return false;
		}
	}

	private static boolean isPremiumTrack(String track) {
		return "premium".equalsIgnoreCase(track) || "paid".equalsIgnoreCase(track);
	}

	private static JsonObject obj(JsonObject parent, String key) {
		if (parent == null) return null;
		JsonElement e = parent.get(key);
		return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
	}

	private static String str(JsonObject o, String key) {
		JsonElement e = o.get(key);
		return e == null || !e.isJsonPrimitive() ? "" : e.getAsString();
	}

	private static UUID parseUuid(String raw) {
		if (raw == null || raw.isBlank()) return null;
		try {
			return UUID.fromString(raw.trim());
		} catch (IllegalArgumentException e) {
			return null;
		}
	}
}
