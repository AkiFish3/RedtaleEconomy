package com.akira.redtale.economy.legacy.data;

import com.akira.redtale.economy.config.ConfigManager;
import com.akira.redtale.economy.legacy.LegacyInit;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * 包裹子系统配置（{@code config/redtale/package.json}），见 {@code _PACKAGE_SPEC.md} §五 / §十。
 *
 * <pre>{@code
 * {
 *   "particles": { "enabled": true, "density": 1 }
 * }
 * }</pre>
 *
 * <p>读盘口径与 {@link com.akira.redtale.economy.legacy.bound.BoundItemsConfig} 一致：
 * 路径取 {@link ConfigManager#baseDir()}，缓存用 {@link ConfigManager#lastReloadAt()} 当版本号
 * ⇒ {@code /redtale reload} 之后下一次访问就生效，不需要 tick 轮询。
 *
 * <p>★density 语义★：{@code 0}=关，{@code 1}=标准（规格表数量），{@code 2}=华丽（×1.6）。
 * 越界值钳到 0~2，不报错 —— 手改配置写错一个数字不该让包裹直接没特效（也不该让它炸掉）。
 */
public final class PackageConfig {
    private static final String FILE = "package.json";
    private static final int DEFAULT_DENSITY = 1;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static boolean enabled = true;
    private static int density = DEFAULT_DENSITY;

    private static long loadedAt = Long.MIN_VALUE;
    private static long loadedMtime = Long.MIN_VALUE;
    /** mtime 探测节流：粒子每 5 tick 查一次，500ms 足够，别每次 stat。 */
    private static long lastFreshCheckMs = 0L;
    private static final long FRESH_CHECK_INTERVAL_MS = 500L;

    private PackageConfig() {
    }

    public static Path file() {
        ConfigManager cm = ConfigManager.get();
        Path dir = cm != null ? cm.baseDir()
                : FabricLoader.getInstance().getConfigDir().resolve("redtale");
        return dir.resolve(FILE);
    }

    private static long reloadStamp() {
        ConfigManager cm = ConfigManager.get();
        return cm == null ? Long.MIN_VALUE : cm.lastReloadAt();
    }

    private static long mtimeOf(Path f) {
        try {
            return Files.isRegularFile(f) ? Files.getLastModifiedTime(f).toMillis() : -1L;
        } catch (IOException e) {
            return -1L;
        }
    }

    public static void load() {
        Path f = file();
        enabled = true;
        density = DEFAULT_DENSITY;
        try {
            if (!Files.isRegularFile(f)) {
                saveDefault(f);
            } else {
                parse(Files.readString(f, StandardCharsets.UTF_8));
            }
            LegacyInit.LOGGER.info("[legacy] package.json 载入（particles.enabled={} density={}）", enabled, density);
        } catch (Exception e) {
            // 坏档只回默认值，不清空包裹功能
            LegacyInit.LOGGER.error("[legacy] package.json 读取失败，粒子按默认密度 1 处理", e);
        }
        loadedAt = reloadStamp();
        loadedMtime = mtimeOf(f);
    }

    private static void ensureFresh() {
        long stamp = reloadStamp();
        if (loadedAt != stamp) {
            load();
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastFreshCheckMs < FRESH_CHECK_INTERVAL_MS) return;
        lastFreshCheckMs = now;
        if (loadedMtime != mtimeOf(file())) {
            load();
        }
    }

    private static void parse(String text) {
        if (text == null || text.isBlank()) return;
        JsonElement root = JsonParser.parseString(text);
        if (root == null || !root.isJsonObject()) return;
        JsonElement particles = root.getAsJsonObject().get("particles");
        if (particles == null || !particles.isJsonObject()) return;
        JsonObject p = particles.getAsJsonObject();
        if (p.has("enabled") && p.get("enabled").isJsonPrimitive()) {
            enabled = p.get("enabled").getAsBoolean();
        }
        if (p.has("density") && p.get("density").isJsonPrimitive()) {
            density = Math.max(0, Math.min(2, p.get("density").getAsInt()));
        }
    }

    private static void saveDefault(Path f) {
        JsonObject root = new JsonObject();
        JsonObject particles = new JsonObject();
        particles.addProperty("enabled", true);
        particles.addProperty("density", DEFAULT_DENSITY);
        root.add("particles", particles);
        root.addProperty("_doc", "particles.density: 0=关 / 1=标准 / 2=华丽(×1.6)");
        try {
            Files.createDirectories(f.getParent());
            Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
            Files.writeString(tmp, GSON.toJson(root), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicFailed) {
                Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
            }
            loadedMtime = mtimeOf(f);
        } catch (IOException e) {
            LegacyInit.LOGGER.error("[legacy] package.json 写入失败", e);
        }
    }

    /** 粒子总开关（{@code particles.enabled}）。 */
    public static boolean particlesEnabled() {
        ensureFresh();
        return enabled;
    }

    /** 0 / 1 / 2。 */
    public static int density() {
        ensureFresh();
        return density;
    }

    /** 数量倍率：0 档返回 0（调用方据此直接跳过），1 档 1.0，2 档 1.6。 */
    public static double densityScale() {
        return switch (density()) {
            case 0 -> 0.0;
            case 2 -> 1.6;
            default -> 1.0;
        };
    }
}
