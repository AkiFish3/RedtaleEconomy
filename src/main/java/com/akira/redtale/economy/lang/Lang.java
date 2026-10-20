package com.akira.redtale.economy.lang;

import com.akira.redtale.economy.RedtaleEconomy;
import com.akira.redtale.economy.config.CoreConfig;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 简易语言系统（spec v0.4 §10，仿 Spigot 插件）。
 *
 * <p>来源优先级：{@code config/redtale/lang/<code>.json} → jar 内置同名文件 → key 原样返回。
 * 首次启动把 jar 内置文件复制到 config，运营可直接改；缺失 key 只汇总一条日志，不刷屏。
 * 完整版（热切语言、全区广播）在 R18，这里先保证「文案不写死在代码里」。
 */
public final class Lang {

	private static final Gson GSON = new Gson();
	private static final Map<String, String> VALUES = new LinkedHashMap<>();
	private static final Set<String> MISSING = new HashSet<>();
	private static String code = "zh_cn";

	private Lang() {
	}

	public static void init() {
		code = CoreConfig.language();
		reload();
	}

	public static void reload() {
		VALUES.clear();
		MISSING.clear();
		code = CoreConfig.language();

		Path dir = FabricLoader.getInstance().getGameDir().resolve("config").resolve("redtale").resolve("lang");
		Path file = dir.resolve(code + ".json");
		try {
			Files.createDirectories(dir);
			if (!Files.isRegularFile(file)) {
				copyFromJar(code, file);
			}
		} catch (IOException e) {
			RedtaleEconomy.LOGGER.warn("[lang] 无法准备 {}：{}", file, e.getMessage());
		}

		JsonObject jar = readJar(code);
		JsonObject disk = readFile(file);
		if (jar != null) jar.entrySet().forEach(e -> put(e.getKey(), e.getValue()));
		if (disk != null) disk.entrySet().forEach(e -> put(e.getKey(), e.getValue()));

		if (VALUES.isEmpty()) {
			RedtaleEconomy.LOGGER.error("[lang] {} 与 jar 内置均不可用，将直接显示 key", code);
		} else {
			RedtaleEconomy.LOGGER.info("[lang] 已加载 {} 条文本（{}）", VALUES.size(), code);
		}
	}

	private static void put(String key, JsonElement v) {
		if (v != null && v.isJsonPrimitive()) {
			VALUES.put(key, v.getAsString());
		}
	}

	private static void copyFromJar(String code, Path dst) {
		try (InputStream in = Lang.class.getResourceAsStream("/assets/redtale_economy/lang/" + code + ".json")) {
			if (in == null) return;
			Files.copy(in, dst);
			RedtaleEconomy.LOGGER.info("[lang] 已从 jar 复制 {} 到 config", dst);
		} catch (IOException e) {
			RedtaleEconomy.LOGGER.warn("[lang] 复制语言文件失败：{}", e.getMessage());
		}
	}

	private static JsonObject readJar(String code) {
		try (InputStream in = Lang.class.getResourceAsStream("/assets/redtale_economy/lang/" + code + ".json")) {
			if (in == null) return null;
			try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
				return GSON.fromJson(r, JsonObject.class);
			}
		} catch (Exception e) {
			return null;
		}
	}

	private static JsonObject readFile(Path file) {
		if (!Files.isRegularFile(file)) return null;
		try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			return GSON.fromJson(r, JsonObject.class);
		} catch (Exception e) {
			RedtaleEconomy.LOGGER.error("[lang] {} 解析失败：{}", file.getFileName(), e.getMessage());
			return null;
		}
	}

	/** 取文案；缺失时返回 key 本身并记一条待汇总日志。 */
	public static String t(String key, Object... args) {
		String raw = VALUES.get(key);
		if (raw == null) {
			if (MISSING.add(key)) {
				RedtaleEconomy.LOGGER.warn("[lang] 缺少 key：{}（当前 {}）", key, code);
			}
			return key;
		}
		if (args == null || args.length == 0) return raw;
		try {
			return String.format(raw, args);
		} catch (Exception e) {
			return raw;
		}
	}

	public static String prefix() {
		// 优先用 lang 里的 chat.prefix；没配再退回 core.json
		String v = VALUES.get("chat.prefix");
		if (v != null && !v.isBlank()) return v;
		return CoreConfig.prefix();
	}

	/** 带前缀的完整消息。 */
	public static String msg(String key, Object... args) {
		return prefix() + t(key, args);
	}

	public static String code() {
		return code;
	}

	public static int missingCount() {
		return MISSING.size();
	}
}
