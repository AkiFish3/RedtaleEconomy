package com.akira.redtale.economy.module.impl;

import com.akira.redtale.economy.RedtaleEconomy;
import com.akira.redtale.economy.command.PremiumCommands;
import com.akira.redtale.economy.config.ConfigManager;
import com.akira.redtale.economy.config.PremiumConfig;
import com.akira.redtale.economy.config.StorageConfig;
import com.akira.redtale.economy.module.RedtaleModule;
import com.akira.redtale.economy.util.DataRoot;
import com.akira.redtale.economy.util.PathResolver;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.server.command.ServerCommandSource;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 赞助货币（spec v0.3 §6.1 / v0.4 修订）。
 *
 * <p>独立余额表，<b>不与普通货币互通</b>；默认隐藏（模块默认关闭 + premium.json enabled=false）。
 * 发放要求「OP + 白名单」双重成立。
 */
public final class PremiumModule implements RedtaleModule {

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
	private static final ConcurrentHashMap<UUID, Long> CACHE = new ConcurrentHashMap<>();
	private static Path dir;

	@Override
	public String id() {
		return "premium";
	}

	@Override
	public boolean defaultEnabled() {
		return false;
	}

	@Override
	public void defineConfig(ConfigManager cfg) {
		cfg.define(PremiumConfig.FILE, PremiumConfig.SCHEMA);
	}

	@Override
	public void onLoad(ConfigManager cfg) {
		resolveDir();
	}

	@Override
	public void onReload(ConfigManager cfg) {
		resolveDir();
		CACHE.clear();
	}

	@Override
	public void registerCommands(CommandDispatcher<ServerCommandSource> dispatcher) {
		if (!PremiumConfig.enabled()) {
			RedtaleEconomy.LOGGER.info("[premium] 未启用，命令不注册");
			return;
		}
		PremiumCommands.register(dispatcher);
	}

	/** R36：开服后数据目录重绑（{@code DataRoot.onWorldReady} 调用，%world% 这时才可用）。 */
	@Override
	public void rebindDataDir() {
		resolveDir();
	}

	private static void resolveDir() {
		PathResolver.Resolved r = DataRoot.resolve();
		try {
			Path d = r.path().resolve("premium");
			Files.createDirectories(d);
			dir = d;
		} catch (Exception e) {
			dir = null;
			RedtaleEconomy.LOGGER.error("[premium] 目录不可用：{}", e.getMessage());
		}
	}

	public static long get(UUID uuid) {
		Long c = CACHE.get(uuid);
		if (c != null) return c;
		long v = read(uuid);
		CACHE.put(uuid, v);
		return v;
	}

	private static long read(UUID uuid) {
		if (dir == null) return 0L;
		Path f = dir.resolve(uuid + ".json");
		if (!Files.isRegularFile(f)) return 0L;
		try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
			JsonObject o = GSON.fromJson(r, JsonObject.class);
			return o != null && o.has("premium") ? o.get("premium").getAsLong() : 0L;
		} catch (Exception e) {
			RedtaleEconomy.LOGGER.error("[premium] 读取 {} 失败：{}", uuid, e.getMessage());
			return 0L;
		}
	}

	/** 扣减；余额不足返回 false，不做部分扣款。 */
	public static boolean take(UUID uuid, long amount) {
		if (amount <= 0) return true;
		long cur = get(uuid);
		if (cur < amount) return false;
		CACHE.put(uuid, cur - amount);
		write(uuid, cur - amount);
		return true;
	}

	public static void add(UUID uuid, long delta) {
		long v = Math.max(0, get(uuid) + delta);
		CACHE.put(uuid, v);
		write(uuid, v);
	}

	private static void write(UUID uuid, long value) {
		if (dir == null) return;
		Path f = dir.resolve(uuid + ".json");
		Path tmp = dir.resolve(uuid + ".json.tmp");
		try {
			JsonObject o = new JsonObject();
			o.addProperty("_version", 1);
			o.addProperty("uuid", uuid.toString());
			o.addProperty("premium", value);
			try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
				GSON.toJson(o, w);
			}
			try {
				Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (IOException atomicFail) {
				Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (IOException e) {
			RedtaleEconomy.LOGGER.error("[premium] 写入 {} 失败：{}", uuid, e.getMessage());
		}
	}

	/** 白名单校验：requireWhitelist=false 时直接通过。 */
	public static boolean whitelisted(UUID uuid) {
		if (!PremiumConfig.store().getBool("requireWhitelist", true)) return true;
		var arr = PremiumConfig.store().root().get("whitelist");
		if (arr == null || !arr.isJsonArray()) return false;
		for (var el : arr.getAsJsonArray()) {
			if (el.isJsonPrimitive() && el.getAsString().equalsIgnoreCase(uuid.toString())) return true;
		}
		return false;
	}

	public static String displayName() {
		return PremiumConfig.displayName();
	}
}
