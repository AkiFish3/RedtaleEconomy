package com.akira.redtale.economy.guild;

import com.akira.redtale.economy.RedtaleEconomy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 冒险者协会数据（R32）：{@code <storage.root>/guild/<uuid>.json}。
 *
 * <p>只存「声望 + 已见过的群系/维度 + 已领特权」；等级每次按 {@code guild.json} 的阈值现算，
 * 改配置立刻生效，不需要迁移历史数据。
 */
public final class GuildData {

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
	private static final Map<UUID, GuildData> CACHE = new ConcurrentHashMap<>();
	private static Path dir;

	public final UUID uuid;
	public long rep;
	/** T59：今日已扣的声望（绝对值）与所属日期（yyyy-MM-dd），跨天自动归零。 */
	public String repLossDay = "";
	public long repLossToday;
	public final Set<String> seenBiomes = new LinkedHashSet<>();
	public final Set<String> seenDimensions = new LinkedHashSet<>();
	public final Set<String> claimedPerks = new LinkedHashSet<>();
	public boolean dirty;

	private GuildData(UUID uuid) {
		this.uuid = uuid;
	}

	public static void setDir(Path d) {
		dir = d;
	}

	public static GuildData of(UUID uuid) {
		GuildData d = CACHE.get(uuid);
		if (d != null) return d;
		d = read(uuid);
		CACHE.put(uuid, d);
		return d;
	}

	private static GuildData read(UUID uuid) {
		GuildData d = new GuildData(uuid);
		Path f = file(uuid);
		if (f == null || !Files.isRegularFile(f)) return d;
		try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
			JsonObject o = GSON.fromJson(r, JsonObject.class);
			if (o == null) return d;
			// T59：声望可为负，这里不再钳 0（下限由 GuildModule 按配置控制）
			d.rep = o.has("rep") ? o.get("rep").getAsLong() : 0;
			d.repLossDay = o.has("repLossDay") ? o.get("repLossDay").getAsString() : "";
			d.repLossToday = o.has("repLossToday") ? o.get("repLossToday").getAsLong() : 0;
			readSet(o, "seenBiomes", d.seenBiomes);
			readSet(o, "seenDimensions", d.seenDimensions);
			readSet(o, "claimedPerks", d.claimedPerks);
		} catch (Exception e) {
			RedtaleEconomy.LOGGER.error("[guild] 读取 {} 失败：{}", uuid, e.getMessage());
		}
		return d;
	}

	private static void readSet(JsonObject o, String key, Set<String> target) {
		if (!o.has(key) || !o.get(key).isJsonArray()) return;
		for (var e : o.getAsJsonArray(key)) {
			if (e.isJsonPrimitive()) target.add(e.getAsString());
		}
	}

	public void save() {
		Path f = file(uuid);
		if (f == null) return;
		JsonObject o = new JsonObject();
		o.addProperty("_version", 1);
		o.addProperty("uuid", uuid.toString());
		o.addProperty("rep", rep);
		o.addProperty("repLossDay", repLossDay);
		o.addProperty("repLossToday", repLossToday);
		o.add("seenBiomes", arr(seenBiomes));
		o.add("seenDimensions", arr(seenDimensions));
		o.add("claimedPerks", arr(claimedPerks));
		Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
		try {
			Files.createDirectories(f.getParent());
			try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
				w.write(GSON.toJson(o));
			}
			try {
				Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (IOException atomicFail) {
				Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
			}
			dirty = false;
		} catch (IOException e) {
			RedtaleEconomy.LOGGER.error("[guild] 写入 {} 失败：{}", uuid, e.getMessage());
		}
	}

	private static JsonArray arr(Set<String> set) {
		JsonArray a = new JsonArray();
		for (String s : set) a.add(s);
		return a;
	}

	private static Path file(UUID uuid) {
		return dir == null ? null : dir.resolve(uuid + ".json");
	}

	public static void evict(UUID uuid) {
		GuildData d = CACHE.remove(uuid);
		if (d != null && d.dirty) d.save();
	}

	public static void flushAll() {
		for (GuildData d : CACHE.values()) {
			try {
				if (d.dirty) d.save();
			} catch (Exception ignored) {
				// 单条失败不影响其余
			}
		}
	}

	public static void clearCache() {
		CACHE.clear();
	}
}
