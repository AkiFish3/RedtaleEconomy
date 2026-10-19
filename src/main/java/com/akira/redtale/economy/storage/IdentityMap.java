package com.akira.redtale.economy.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;

/**
 * 身份别名表（B22 的 identity 迁移部分）。
 *
 * <p>场景：服务器从离线模式切成正版验证（或反之）后，同一玩家 UUID 变了，
 * 旧账号文件变成孤儿、余额「消失」。这里维护 {@code <root>/accounts/_identities.json}：
 * <pre>{ "旧UUID": {"to":"新UUID","name":"Aki","at":"2026-10-20"} }</pre>
 * 读账号时若本 UUID 没有文件，就沿别名链找一次（带环检测），命中则读目标文件。
 *
 * <p>只解析单跳链最多 {@link #MAX_DEPTH} 层，纯内存操作，便于单测。
 */
public final class IdentityMap {

	public static final String FILE_NAME = "_identities.json";
	public static final int MAX_DEPTH = 8;

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

	private IdentityMap() {
	}

	/** 沿别名链解析出最终 UUID；无别名返回入参本身。 */
	public static String resolve(Map<String, String> edges, String uuid) {
		if (edges == null || edges.isEmpty() || uuid == null) return uuid;
		String cur = uuid;
		for (int i = 0; i < MAX_DEPTH; i++) {
			String next = edges.get(cur);
			if (next == null || next.isBlank() || next.equals(cur)) return cur;
			cur = next;
		}
		return cur; // 环或超深：停在第 MAX_DEPTH 跳，由调用方日志告警
	}

	/** 环检测：链长超过 {@link #MAX_DEPTH} 视为异常数据。 */
	public static boolean isCyclic(Map<String, String> edges, String uuid) {
		if (edges == null || uuid == null) return false;
		String cur = uuid;
		for (int i = 0; i < MAX_DEPTH; i++) {
			String next = edges.get(cur);
			if (next == null || next.isBlank() || next.equals(cur)) return false;
			cur = next;
		}
		return true;
	}

	/** 读别名表；文件不存在返回空表（不创建文件，避免污染干净目录）。 */
	public static Map<String, String> load(Path accountsDir) {
		Map<String, String> out = new HashMap<>();
		if (accountsDir == null) return out;
		Path f = accountsDir.resolve(FILE_NAME);
		if (!Files.isRegularFile(f)) return out;
		try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
			Map<String, JsonObject> raw = GSON.fromJson(r, new TypeToken<Map<String, JsonObject>>() {}.getType());
			if (raw == null) return out;
			for (Map.Entry<String, JsonObject> e : raw.entrySet()) {
				JsonObject v = e.getValue();
				if (v != null && v.has("to")) {
					String to = v.get("to").getAsString();
					if (to != null && !to.isBlank()) out.put(e.getKey(), to);
				}
			}
		} catch (Exception e) {
			return out;
		}
		return out;
	}

	/**
	 * 记录一条别名并落盘（tmp + 原子 rename）。
	 *
	 * @return 是否写入成功
	 */
	public static boolean put(Path accountsDir, String from, String to, String name) {
		if (accountsDir == null || from == null || to == null || from.equals(to)) return false;
		Path f = accountsDir.resolve(FILE_NAME);
		try {
			Files.createDirectories(accountsDir);
			Map<String, JsonObject> raw = new HashMap<>();
			if (Files.isRegularFile(f)) {
				try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
					Map<String, JsonObject> old = GSON.fromJson(r, new TypeToken<Map<String, JsonObject>>() {}.getType());
					if (old != null) raw.putAll(old);
				} catch (Exception ignored) {
					// 坏文件：用空表重建（原文件已在下方备份）
				}
			}
			JsonObject v = new JsonObject();
			v.addProperty("to", to);
			if (name != null) v.addProperty("name", name);
			v.addProperty("at", java.time.LocalDate.now().toString());
			raw.put(from, v);

			Path tmp = accountsDir.resolve(FILE_NAME + ".tmp");
			try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
				GSON.toJson(raw, w);
			}
			try {
				Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (IOException atomicFail) {
				Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
			}
			return true;
		} catch (IOException | RuntimeException e) {
			return false;
		}
	}
}
