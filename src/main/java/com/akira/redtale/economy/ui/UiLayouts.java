package com.akira.redtale.economy.ui;

import com.akira.redtale.economy.RedtaleEconomy;
import com.akira.redtale.economy.config.ConfigManager;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 布局注册表（R15）。
 *
 * <p>布局文件放在 {@code config/redtale/ui/**.json5}，<b>首次运行把内置布局复制过去</b>，
 * 之后只读盘 —— 服主改完保存，下次打开界面即生效（客户端在开界面时扫盘，不用重启）。
 *
 * <p>文件名（去掉扩展名）= 布局 id，例如 {@code ui/menu/main.json5} ⇒ {@code menu/main}。
 * 以 {@code _} 开头的文件/目录跳过（临时禁用）。
 */
public final class UiLayouts {

	/** 内置布局，首次运行时释放到 config 目录。 */
	private static final List<String> BUNDLED = List.of("menu/main");

	private static final Map<String, UiLayout> LAYOUTS = new LinkedHashMap<>();
	private static long lastScan = -1L;

	private UiLayouts() {
	}

	public static Path dir() {
		ConfigManager cm = ConfigManager.get();
		Path base = cm != null ? cm.baseDir()
				: Path.of("config", "redtale");
		return base.resolve("ui");
	}

	/** 扫描磁盘（带 1 秒节流，避免每帧调）。 */
	public static synchronized void reload() {
		long now = System.currentTimeMillis();
		if (now - lastScan < 1000L) return;
		lastScan = now;
		purge();
		Path dir = dir();
		try {
			Files.createDirectories(dir);
		} catch (Exception e) {
			RedtaleEconomy.LOGGER.warn("[ui] 建目录失败 {}：{}", dir, e.getMessage());
			return;
		}
		copyBundled(dir);
		List<Path> files = new ArrayList<>();
		try (var s = Files.walk(dir)) {
			s.filter(Files::isRegularFile).forEach(files::add);
		} catch (Exception e) {
			RedtaleEconomy.LOGGER.warn("[ui] 扫描布局失败：{}", e.getMessage());
			return;
		}
		for (Path p : files) {
			String name = p.getFileName().toString();
			if (name.startsWith("_")) continue;
			if (!(name.endsWith(".json5") || name.endsWith(".json"))) continue;
			String rel = dir.relativize(p).toString().replace('\\', '/');
			String id = rel.substring(0, rel.lastIndexOf('.'));
			if (hasDisabledSegment(rel)) continue;
			try {
				String json = Files.readString(p, StandardCharsets.UTF_8);
				LAYOUTS.put(id, UiLayout.parse(id, json));
			} catch (Exception e) {
				RedtaleEconomy.LOGGER.warn("[ui] 布局 {} 解析失败（已跳过）：{}", rel, e.getMessage());
			}
		}
		RedtaleEconomy.LOGGER.info("[ui] 已加载 {} 份布局：{}", LAYOUTS.size(), LAYOUTS.keySet());
	}

	private static boolean hasDisabledSegment(String rel) {
		for (String seg : rel.split("/")) {
			if (seg.startsWith("_")) return true;
		}
		return false;
	}

	private static void purge() {
		LAYOUTS.clear();
	}

	/** 内置布局只在缺失时释放，不覆盖用户改过的文件。 */
	private static void copyBundled(Path dir) {
		for (String id : BUNDLED) {
			Path target = dir.resolve(id + ".json5");
			if (Files.isRegularFile(target)) continue;
			String res = "/assets/redtale_economy/ui/" + id + ".json5";
			try (InputStream in = UiLayouts.class.getResourceAsStream(res)) {
				if (in == null) continue;
				Files.createDirectories(target.getParent());
				Files.writeString(target, new String(in.readAllBytes(), StandardCharsets.UTF_8),
						StandardCharsets.UTF_8);
			} catch (Exception e) {
				RedtaleEconomy.LOGGER.warn("[ui] 释放内置布局 {} 失败：{}", id, e.getMessage());
			}
		}
	}

	public static UiLayout get(String id) {
		reload();
		return LAYOUTS.get(id);
	}

	public static List<String> ids() {
		reload();
		return new ArrayList<>(LAYOUTS.keySet());
	}

	/** 强制重扫（`/redtale reload` 或编辑器保存后调）。 */
	public static synchronized void invalidate() {
		lastScan = -1L;
	}
}
