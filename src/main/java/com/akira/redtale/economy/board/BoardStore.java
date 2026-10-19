package com.akira.redtale.economy.board;

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
import java.util.ArrayList;
import java.util.List;

/**
 * 委托板数据（R30）：单文件 {@code <storage.root>/board/board.json}。
 *
 * <p>条目很少（几十条），整文件原子写足够；所有状态变更都走 {@link #save()}，
 * 崩溃最多丢最后一次操作，不会出现「赏金扣了但单子没挂上」——因为扣钱在 add 之后。
 */
public final class BoardStore {

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
	private static Path file;
	private static final List<Entry> ENTRIES = new ArrayList<>();

	public enum Status { OPEN, ACCEPTED, DONE, CANCELLED }

	public static final class Entry {
		public String id = "";
		public String poster = "";
		public String posterName = "";
		public String itemId = "";
		public int count;
		public long bounty;
		public long fee;
		public long createdAt;
		public long expiresAt;
		public String accepter = "";
		public String accepterName = "";
		public String status = Status.OPEN.name();
	}

	private BoardStore() {
	}

	public static void init(Path dir) {
		file = dir == null ? null : dir.resolve("board.json");
		ENTRIES.clear();
		load();
	}

	public static synchronized void load() {
		if (file == null || !Files.isRegularFile(file)) return;
		try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			JsonObject o = GSON.fromJson(r, JsonObject.class);
			if (o == null || !o.has("entries") || !o.get("entries").isJsonArray()) return;
			for (var e : o.getAsJsonArray("entries")) {
				if (!e.isJsonObject()) continue;
				Entry en = GSON.fromJson(e, Entry.class);
				if (en != null && en.id != null && !en.id.isEmpty()) ENTRIES.add(en);
			}
		} catch (Exception e) {
			RedtaleEconomy.LOGGER.error("[board] 读取委托板失败：{}", e.getMessage());
		}
	}

	public static synchronized void save() {
		if (file == null) return;
		JsonObject o = new JsonObject();
		o.addProperty("_version", 1);
		o.addProperty("updatedAt", System.currentTimeMillis());
		JsonArray arr = new JsonArray();
		for (Entry e : ENTRIES) arr.add(GSON.toJsonTree(e));
		o.add("entries", arr);
		Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
		try {
			Files.createDirectories(file.getParent());
			try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
				w.write(GSON.toJson(o));
			}
			try {
				Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (IOException atomicFail) {
				Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (IOException e) {
			RedtaleEconomy.LOGGER.error("[board] 写入委托板失败：{}", e.getMessage());
		}
	}

	public static synchronized List<Entry> all() {
		return new ArrayList<>(ENTRIES);
	}

	public static synchronized Entry byId(String id) {
		for (Entry e : ENTRIES) {
			if (e.id.equalsIgnoreCase(id)) return e;
		}
		return null;
	}

	public static synchronized void add(Entry e) {
		ENTRIES.add(e);
		save();
	}

	public static synchronized int openCountOf(String uuid) {
		int n = 0;
		for (Entry e : ENTRIES) {
			if (e.poster.equals(uuid) && Status.OPEN.name().equals(e.status)) n++;
		}
		return n;
	}
}
