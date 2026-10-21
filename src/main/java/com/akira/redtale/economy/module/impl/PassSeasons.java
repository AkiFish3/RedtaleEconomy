package com.akira.redtale.economy.module.impl;

import com.akira.redtale.economy.RedtaleEconomy;
import com.akira.redtale.economy.util.DataRoot;
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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * T02 战令赛季归档：赛季清单 + 结束快照（进度冻结）。
 *
 * <p>数据：{@code <数据根>/battlepass/seasons.json}
 * <pre>
 * {"_version": 1, "seasons": [
 *   {"id":"s1","name":"第一赛季","startAt":"2026-10-01","endAt":"2026-12-31","archivedAt":""}
 * ]}
 * </pre>
 *
 * <p>归档做什么：
 * <ol>
 *   <li>把该赛季目录下每个玩家文件汇总成 {@code _snapshot.json}（按 xp 降序，便于直接发榜）；</li>
 *   <li>在 seasons.json 里写 {@code archivedAt}；</li>
 *   <li>之后 {@link #writable} 返回 false ⇒ 加经验/领奖全部拒绝（进度冻结）。</li>
 * </ol>
 *
 * <p>玩家原始文件<b>不删不改</b>：快照是「冻结视图」，原档留着才能查账、也才能做 T03 补领。
 * 归档幂等：已归档再调返回 {@link ArchiveResult#ALREADY}，不覆盖已有快照（除非手工删掉）。
 */
public final class PassSeasons {

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
	public static final String FILE = "seasons.json";
	public static final String SNAPSHOT = "_snapshot.json";

	/** 一条赛季记录。{@code archivedAt} 为空 = 未归档。 */
	public record Entry(String id, String name, String startAt, String endAt, String archivedAt) {
		public boolean archived() {
			return archivedAt != null && !archivedAt.isBlank();
		}
	}

	/** 快照摘要。 */
	public record Snapshot(String season, String archivedAt, int players, long totalXp,
			int premium, int claimedFree, int claimedPremium) {
	}

	public enum ArchiveResult { OK, ALREADY, NO_DATA, ERROR }

	private PassSeasons() {
	}

	public static Path root() {
		try {
			return DataRoot.path().resolve("battlepass");
		} catch (RuntimeException e) {
			return null;
		}
	}

	public static Path file() {
		Path r = root();
		return r == null ? null : r.resolve(FILE);
	}

	// ---------------------------------------------------------------- 清单

	public static synchronized List<Entry> list() {
		List<Entry> out = new ArrayList<>();
		JsonObject o = readFile();
		JsonElement arr = o.get("seasons");
		if (arr == null || !arr.isJsonArray()) return out;
		for (JsonElement e : arr.getAsJsonArray()) {
			if (e == null || !e.isJsonObject()) continue;
			JsonObject s = e.getAsJsonObject();
			out.add(new Entry(
					str(s, "id"), str(s, "name"), str(s, "startAt"), str(s, "endAt"), str(s, "archivedAt")));
		}
		return out;
	}

	public static synchronized Entry find(String id) {
		for (Entry e : list()) {
			if (e.id().equals(id)) return e;
		}
		return null;
	}

	/** 保证当前赛季在清单里（开服/重载时调）。已存在则原样返回，不覆盖 endAt。 */
	public static synchronized Entry ensureCurrent(String id, String name) {
		if (id == null || id.isBlank()) return null;
		Entry got = find(id);
		if (got != null) return got;
		JsonObject o = readFile();
		JsonArray arr = o.has("seasons") && o.get("seasons").isJsonArray()
				? o.getAsJsonArray("seasons") : new JsonArray();
		JsonObject s = new JsonObject();
		s.addProperty("id", id);
		s.addProperty("name", name == null ? id : name);
		s.addProperty("startAt", LocalDate.now().toString());
		s.addProperty("endAt", "");
		s.addProperty("archivedAt", "");
		arr.add(s);
		o.add("seasons", arr);
		writeFile(o);
		return new Entry(id, name == null ? id : name, LocalDate.now().toString(), "", "");
	}

	/**
	 * 该赛季还能不能写（未归档 = true）。
	 *
	 * <p><b>清单读不到时放行</b>（fail-open）：冻结只是防止旧赛季被改，
	 * 而清单文件缺失/世界尚未就绪（R36 两段式）是常态 —— 那种情况下把加经验全拦掉，
	 * 等于战令静默失效，比「旧赛季多涨几点经验」严重得多。
	 */
	public static boolean writable(String seasonId) {
		if (seasonId == null || seasonId.isBlank()) return false;
		Entry e = find(seasonId);
		return e == null || !e.archived();
	}

	// ---------------------------------------------------------------- 归档

	/** 归档某赛季；seasonId 对应的目录必须存在（否则 NO_DATA）。 */
	public static synchronized ArchiveResult archive(String seasonId, long nowMs) {
		Entry e = find(seasonId);
		if (e == null) return ArchiveResult.NO_DATA;
		if (e.archived()) return ArchiveResult.ALREADY;
		Path dir = root() == null ? null : root().resolve(seasonId);
		if (dir == null || !Files.isDirectory(dir)) return ArchiveResult.NO_DATA;
		ArchiveResult r = archiveDir(dir, seasonId, nowMs);
		if (r != ArchiveResult.OK) return r;
		markArchived(seasonId, nowMs);
		return ArchiveResult.OK;
	}

	/**
	 * 纯目录操作（单测入口）：把 {@code <seasonDir>/*.json} 汇总成 {@code _snapshot.json}。
	 *
	 * <p>以 {@code _} 开头的文件（快照、临时文件）都会被跳过，否则第二次归档会把快照自己当玩家。
	 */
	public static ArchiveResult archiveDir(Path dir, String seasonId, long nowMs) {
		if (dir == null || !Files.isDirectory(dir)) return ArchiveResult.NO_DATA;
		Path snap = dir.resolve(SNAPSHOT);
		if (Files.isRegularFile(snap)) return ArchiveResult.ALREADY;
		JsonArray entries = new JsonArray();
		List<JsonObject> rows = new ArrayList<>();
		int premium = 0;
		long totalXp = 0;
		int claimedFree = 0;
		int claimedPremium = 0;
		int players = 0;
		try (var s = Files.list(dir)) {
			List<Path> files = new ArrayList<>(s.toList());
			files.sort(Comparator.comparing((Path p) -> p.getFileName().toString()));
			for (Path f : files) {
				String name = f.getFileName().toString();
				if (!name.endsWith(".json") || name.startsWith("_")) continue;
				try {
					JsonElement el = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8));
					if (!el.isJsonObject()) continue;
					JsonObject o = el.getAsJsonObject();
					long xp = o.has("xp") ? Math.max(0, o.get("xp").getAsLong()) : 0;
					boolean prem = o.has("premium") && o.get("premium").getAsBoolean();
					int cf = 0;
					int cp = 0;
					if (o.has("claimed") && o.get("claimed").isJsonObject()) {
						JsonObject c = o.getAsJsonObject("claimed");
						if (c.has("free") && c.get("free").isJsonArray()) cf = c.getAsJsonArray("free").size();
						if (c.has("premium") && c.get("premium").isJsonArray()) {
							cp = c.getAsJsonArray("premium").size();
						}
					}
					JsonObject row = new JsonObject();
					row.addProperty("uuid", name.substring(0, name.length() - 5));
					row.addProperty("xp", xp);
					row.addProperty("premium", prem);
					row.addProperty("claimedFree", cf);
					row.addProperty("claimedPremium", cp);
					rows.add(row);
					players++;
					totalXp += xp;
					if (prem) premium++;
					claimedFree += cf;
					claimedPremium += cp;
				} catch (Exception bad) {
					// 单个坏档不影响整季归档，但要在日志里留痕
					RedtaleEconomy.LOGGER.warn("[pass] 归档跳过坏档 {}：{}", name, bad.getMessage());
				}
			}
		} catch (Exception e) {
			RedtaleEconomy.LOGGER.error("[pass] 归档扫描失败：{}", e.getMessage());
			return ArchiveResult.ERROR;
		}
		rows.sort(Comparator.comparingLong((JsonObject o) -> -o.get("xp").getAsLong()));
		for (JsonObject row : rows) entries.add(row);
		JsonObject snapObj = new JsonObject();
		snapObj.addProperty("_version", 1);
		snapObj.addProperty("season", seasonId);
		snapObj.addProperty("archivedAt", iso(nowMs));
		snapObj.addProperty("players", players);
		snapObj.addProperty("totalXp", totalXp);
		snapObj.addProperty("premium", premium);
		snapObj.addProperty("claimedFree", claimedFree);
		snapObj.addProperty("claimedPremium", claimedPremium);
		snapObj.add("entries", entries);
		try {
			Path tmp = dir.resolve(SNAPSHOT + ".tmp");
			Files.writeString(tmp, GSON.toJson(snapObj), StandardCharsets.UTF_8);
			try {
				Files.move(tmp, snap, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (AtomicMoveNotSupportedException e) {
				Files.move(tmp, snap, StandardCopyOption.REPLACE_EXISTING);
			}
			return ArchiveResult.OK;
		} catch (Exception e) {
			RedtaleEconomy.LOGGER.error("[pass] 写快照失败：{}", e.getMessage());
			return ArchiveResult.ERROR;
		}
	}

	/**
	 * T04：定时结算 —— 把 endAt 已过去的赛季自动归档（不动当前赛季指针）。
	 *
	 * <p>「换赛季」是服主的决定（要写配置），不能由程序猜下一个 id；所以这里只做**关账**
	 * （写快照 + 冻结），换指针留给 {@code /pass rollover <next>}。
	 *
	 * @return 本次关掉的赛季 id 列表
	 */
	public static synchronized List<String> autoCloseExpired(long nowMs, LocalDate today) {
		List<String> closed = new ArrayList<>();
		for (Entry e : list()) {
			if (!needsClose(e, today)) continue;
			if (archive(e.id(), nowMs) == ArchiveResult.OK) closed.add(e.id());
		}
		if (!closed.isEmpty()) {
			RedtaleEconomy.LOGGER.info("[pass] 已自动关账 {} 个到期赛季：{}", closed.size(), closed);
		}
		return closed;
	}

	/** 纯判据：有 endAt、已过期、且没归档过 ⇒ 该关账。 */
	public static boolean needsClose(Entry e, LocalDate today) {
		if (e == null || e.archived()) return false;
		if (e.endAt() == null || e.endAt().isBlank()) return false;
		try {
			LocalDate end = LocalDate.parse(e.endAt().trim().length() > 10
					? e.endAt().trim().substring(0, 10) : e.endAt().trim());
			return end.isBefore(today);
		} catch (Exception bad) {
			// 日期写错不该导致误关账
			return false;
		}
	}

	/** 读快照摘要（不存在返回 null）。 */
	public static Snapshot snapshotOf(String seasonId) {
		Path dir = root() == null ? null : root().resolve(seasonId);
		if (dir == null) return null;
		Path f = dir.resolve(SNAPSHOT);
		if (!Files.isRegularFile(f)) return null;
		try {
			JsonElement el = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8));
			if (!el.isJsonObject()) return null;
			JsonObject o = el.getAsJsonObject();
			return new Snapshot(str(o, "season"), str(o, "archivedAt"),
					o.has("players") ? o.get("players").getAsInt() : 0,
					o.has("totalXp") ? o.get("totalXp").getAsLong() : 0L,
					o.has("premium") ? o.get("premium").getAsInt() : 0,
					o.has("claimedFree") ? o.get("claimedFree").getAsInt() : 0,
					o.has("claimedPremium") ? o.get("claimedPremium").getAsInt() : 0);
		} catch (Exception e) {
			return null;
		}
	}

	private static void markArchived(String seasonId, long nowMs) {
		JsonObject o = readFile();
		JsonElement arr = o.get("seasons");
		if (arr == null || !arr.isJsonArray()) return;
		for (JsonElement e : arr.getAsJsonArray()) {
			if (!e.isJsonObject()) continue;
			JsonObject s = e.getAsJsonObject();
			if (seasonId.equals(str(s, "id"))) {
				s.addProperty("archivedAt", iso(nowMs));
			}
		}
		writeFile(o);
	}

	// ---------------------------------------------------------------- IO

	private static JsonObject readFile() {
		Path f = file();
		if (f == null || !Files.isRegularFile(f)) return new JsonObject();
		try {
			JsonElement el = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8));
			return el.isJsonObject() ? el.getAsJsonObject() : new JsonObject();
		} catch (Exception e) {
			RedtaleEconomy.LOGGER.error("[pass] 读取赛季清单失败：{}", e.getMessage());
			return new JsonObject();
		}
	}

	private static void writeFile(JsonObject o) {
		Path f = file();
		if (f == null) return;
		try {
			o.addProperty("_version", 1);
			Files.createDirectories(f.getParent());
			Path tmp = f.resolveSibling(FILE + ".tmp");
			Files.writeString(tmp, GSON.toJson(o), StandardCharsets.UTF_8);
			try {
				Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (AtomicMoveNotSupportedException e) {
				Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (Exception e) {
			RedtaleEconomy.LOGGER.error("[pass] 写入赛季清单失败：{}", e.getMessage());
		}
	}

	private static String str(JsonObject o, String key) {
		JsonElement e = o.get(key);
		return e == null || !e.isJsonPrimitive() ? "" : e.getAsString();
	}

	private static String iso(long ms) {
		return java.time.LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(ms),
				java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME);
	}
}
