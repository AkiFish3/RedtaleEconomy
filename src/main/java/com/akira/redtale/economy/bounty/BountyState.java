package com.akira.redtale.economy.bounty;

import com.akira.redtale.economy.RedtaleEconomy;
import com.akira.redtale.economy.config.BountyConfig;
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
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 单个玩家的悬赏状态（R31）。
 *
 * <p>{@code <storage.root>/bounty/<uuid>.json}：
 * {@code slots} 是今天的悬赏（id + 进度 + 是否已领），
 * {@code seen} 是「历史上已经来过的群系/维度」（unique 目标靠它判断第一次）。
 */
public final class BountyState {

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
	private static final Map<UUID, BountyState> CACHE = new ConcurrentHashMap<>();
	private static Path dir;

	public static final class Slot {
		public String id = "";
		public int progress;
		public boolean claimed;

		Slot() {
		}

		Slot(String id) {
			this.id = id;
		}
	}

	public final UUID uuid;
	public String date = "";
	public final List<Slot> slots = new ArrayList<>();
	/** 历史走过的群系 / 维度 key（unique 目标用）。 */
	public final Set<String> seen = new LinkedHashSet<>();
	public boolean dirty;

	private BountyState(UUID uuid) {
		this.uuid = uuid;
	}

	public static void setDir(Path d) {
		dir = d;
	}

	public static BountyState of(UUID uuid) {
		BountyState s = CACHE.get(uuid);
		if (s != null) return s;
		s = read(uuid);
		CACHE.put(uuid, s);
		return s;
	}

	private static BountyState read(UUID uuid) {
		BountyState s = new BountyState(uuid);
		Path f = file(uuid);
		if (f == null || !Files.isRegularFile(f)) return s;
		try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
			JsonObject o = GSON.fromJson(r, JsonObject.class);
			if (o == null) return s;
			s.date = o.has("date") ? o.get("date").getAsString() : "";
			if (o.has("slots") && o.get("slots").isJsonArray()) {
				for (var e : o.getAsJsonArray("slots")) {
					if (!e.isJsonObject()) continue;
					JsonObject so = e.getAsJsonObject();
					Slot slot = new Slot(so.has("id") ? so.get("id").getAsString() : "");
					slot.progress = so.has("progress") ? so.get("progress").getAsInt() : 0;
					slot.claimed = so.has("claimed") && so.get("claimed").getAsBoolean();
					s.slots.add(slot);
				}
			}
			if (o.has("seen") && o.get("seen").isJsonArray()) {
				for (var e : o.getAsJsonArray("seen")) {
					if (e.isJsonPrimitive()) s.seen.add(e.getAsString());
				}
			}
		} catch (Exception e) {
			RedtaleEconomy.LOGGER.error("[bounty] 读取 {} 失败：{}", uuid, e.getMessage());
		}
		return s;
	}

	public String toJson() {
		JsonObject o = new JsonObject();
		o.addProperty("_version", 1);
		o.addProperty("uuid", uuid.toString());
		o.addProperty("date", date);
		JsonArray arr = new JsonArray();
		for (Slot s : slots) {
			JsonObject so = new JsonObject();
			so.addProperty("id", s.id);
			so.addProperty("progress", s.progress);
			so.addProperty("claimed", s.claimed);
			arr.add(so);
		}
		o.add("slots", arr);
		JsonArray seenArr = new JsonArray();
		for (String k : seen) seenArr.add(k);
		o.add("seen", seenArr);
		return GSON.toJson(o);
	}

	public void save() {
		Path f = file(uuid);
		if (f == null) return;
		Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
		try {
			Files.createDirectories(f.getParent());
			try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
				w.write(toJson());
			}
			try {
				Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (IOException atomicFail) {
				Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
			}
			dirty = false;
		} catch (IOException e) {
			RedtaleEconomy.LOGGER.error("[bounty] 写入 {} 失败：{}", uuid, e.getMessage());
		}
	}

	private static Path file(UUID uuid) {
		return dir == null ? null : dir.resolve(uuid + ".json");
	}

	/** 按今天的日期重建 slots（调用方负责挑	id）。 */
	public void reset(String today, List<String> ids) {
		date = today;
		slots.clear();
		for (String id : ids) slots.add(new Slot(id));
		dirty = true;
	}

	public void flush() {
		if (dirty) save();
	}

	public static void flushAll() {
		CACHE.values().forEach(s -> {
			try {
				if (s.dirty) s.save();
			} catch (Exception ignored) {
				// 单条失败不影响其余
			}
		});
	}

	public static void evict(UUID uuid) {
		BountyState s = CACHE.remove(uuid);
		if (s != null) s.flush();
	}

	public static void clearCache() {
		CACHE.clear();
	}

	/** 取定义（按 id）。 */
	public static BountyConfig.Def def(String id) {
		for (BountyConfig.Def d : BountyConfig.pool()) {
			if (d.id().equals(id)) return d;
		}
		return null;
	}
}
