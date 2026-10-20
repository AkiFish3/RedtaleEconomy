package com.akira.redtale.economy.config;

import com.akira.redtale.economy.RedtaleEconomy;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 配置总入口：注册所有 JSON 文件、统一加载 / 热重载 / 保存。
 *
 * <p>目录固定为 {@code <gameDir>/config/redtale/}。
 * 每个文件必须在启动时通过 {@link #define} 登记 schema，未登记的文件不会被读取。
 */
public final class ConfigManager {

	private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
	// 备份份数由 core.json 的 backup.maxBackups 决定，此处不再硬编码

	private static ConfigManager instance;

	private final Path baseDir;
	private final Map<String, ConfigStore> stores = new LinkedHashMap<>();
	private final Map<String, ConfigSchema> schemas = new LinkedHashMap<>();
	private final List<String> lastLoadErrors = new ArrayList<>();
	private long lastReloadAt;

	private ConfigManager(Path baseDir) {
		this.baseDir = baseDir;
	}

	public static ConfigManager create() {
		if (instance == null) {
			Path dir = FabricLoader.getInstance().getGameDir().resolve("config").resolve("redtale");
			instance = new ConfigManager(dir);
		}
		return instance;
	}

	public static ConfigManager get() {
		return instance;
	}

	/**
	 * 测试/命令行工具用：绑定任意基目录，不经过 FabricLoader。
	 *
	 * <p>正式启动仍走 {@link #create()}；这里只为让集成测试（如 MySQL 后端实连）
	 * 能在游戏外复现同一套配置加载逻辑。
	 */
	public static synchronized ConfigManager createAt(Path dir) {
		instance = new ConfigManager(dir);
		return instance;
	}

	public static synchronized void clearInstance() {
		instance = null;
	}

	public Path baseDir() {
		return baseDir;
	}

	// ---------------------------------------------------------------- 注册

	public ConfigManager define(String relativePath, ConfigSchema schema) {
		schemas.put(relativePath, schema);
		return this;
	}

	public ConfigStore store(String relativePath) {
		ConfigStore s = stores.get(relativePath);
		if (s == null) {
			ConfigSchema schema = schemas.get(relativePath);
			if (schema == null) {
				throw new IllegalStateException("未登记 schema 的配置文件：" + relativePath);
			}
			s = new ConfigStore(relativePath, schema, baseDir);
			stores.put(relativePath, s);
		}
		return s;
	}

	public boolean isDefined(String relativePath) {
		return schemas.containsKey(relativePath);
	}

	// ---------------------------------------------------------------- 加载

	public void loadAll() {
		lastLoadErrors.clear();
		for (String rel : schemas.keySet()) {
			ConfigStore s = store(rel);
			if (!s.load()) {
				lastLoadErrors.add(rel + "：" + (s.lastError() == null ? "未知错误" : s.lastError()));
			}
		}
		writeReadme();
		lastReloadAt = System.currentTimeMillis();
		if (!lastLoadErrors.isEmpty()) {
			RedtaleEconomy.LOGGER.error("[config] {} 个文件加载失败：", lastLoadErrors.size());
			lastLoadErrors.forEach(e -> RedtaleEconomy.LOGGER.error("[config]   {}", e));
		}
	}

	/**
	 * 热重载：重新读盘并套用 schema。
	 * 注册期资源（命令 / 包 / 界面）不随 reload 变更，只有数值与文案生效。
	 */
	public void reloadAll() {
		long t0 = System.currentTimeMillis();
		loadAll();
		RedtaleEconomy.LOGGER.info("[config] reload 完成，{} 个文件，耗时 {} ms", schemas.size(), System.currentTimeMillis() - t0);
	}

	public void saveAll(boolean backup) {
		for (ConfigStore s : stores.values()) {
			List<String> notes = s.validate();
			notes.forEach(n -> RedtaleEconomy.LOGGER.warn("[config] 保存前校验 {} {}", s.path(), n));
			s.save(backup);
		}
	}

	public List<String> lastLoadErrors() {
		return lastLoadErrors;
	}

	public long lastReloadAt() {
		return lastReloadAt;
	}

	public List<String> definedFiles() {
		return new ArrayList<>(schemas.keySet());
	}

	/** 把各 schema 的字段说明汇总成 {@code config/redtale/README.md}（内容不变则不重写）。 */
	private void writeReadme() {
		StringBuilder sb = new StringBuilder();
		sb.append("# Redtale Economy 配置说明\n\n");
		sb.append("> 本文件由 mod 根据配置 schema 自动生成，改配置不会改本文件。\n");
		sb.append("> 所有配置文件都是标准 JSON（无注释、无尾逗号）；改完执行 `/redtale reload` 热生效。\n\n");
		for (String rel : schemas.keySet()) {
			ConfigStore s = stores.get(rel);
			if (s == null) continue;
			sb.append("## ").append(rel).append("\n\n");
			List<String> lines = s.readmeLines();
			if (lines.isEmpty()) {
				sb.append("_（无字段说明）_\n");
			} else {
				for (String line : lines) sb.append("- ").append(line).append('\n');
			}
			sb.append('\n');
		}
		Path f = baseDir.resolve("README.md");
		String text = sb.toString();
		try {
			if (Files.isRegularFile(f) && text.equals(Files.readString(f, StandardCharsets.UTF_8))) return;
			Path tmp = baseDir.resolve("README.md.tmp");
			Files.createDirectories(baseDir);
			Files.writeString(tmp, text, StandardCharsets.UTF_8);
			try {
				Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (IOException atomicFail) {
				Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
			}
			RedtaleEconomy.LOGGER.info("[config] 已生成 {}", f);
		} catch (IOException e) {
			RedtaleEconomy.LOGGER.warn("[config] 生成 README.md 失败：{}", e.getMessage());
		}
	}

	// ---------------------------------------------------------------- 备份 / 日志

	/** 备份目录只保留最近 core.json 配置的份数（默认 20）。 */
	static void pruneBackups(Path baseDir) {
		Path backupRoot = baseDir.resolve("backup");
		if (!Files.isDirectory(backupRoot)) return;
		int keep = 20;
		try {
			keep = CoreConfig.maxBackups();
		} catch (Exception ignored) {
			// core.json 尚未就绪时用默认值
		}
		final int max = Math.max(1, keep);
		try (Stream<Path> s = Files.list(backupRoot)) {
			List<Path> dirs = s.filter(Files::isDirectory)
					.sorted(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed())
					.toList();
			for (int i = max; i < dirs.size(); i++) {
				deleteRecursively(dirs.get(i));
			}
		} catch (IOException e) {
			RedtaleEconomy.LOGGER.warn("[config] 清理备份失败：{}", e.getMessage());
		}
	}

	private static void deleteRecursively(Path p) {
		try (Stream<Path> s = Files.walk(p)) {
			s.sorted(Comparator.reverseOrder()).forEach(f -> {
				try {
					Files.deleteIfExists(f);
				} catch (IOException ignored) {
					// 单个文件删不掉不影响其余
				}
			});
		} catch (IOException ignored) {
			// 同上
		}
	}

	/** 所有配置写操作留痕（谁改的由命令层补充描述）。 */
	public static void logEdit(String file, String action, String detail) {
		Path log = FabricLoader.getInstance().getGameDir()
				.resolve("config").resolve("redtale").resolve("logs").resolve("edit.log");
		try {
			Files.createDirectories(log.getParent());
			String line = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
					+ " | " + file + " | " + action + " | " + detail + System.lineSeparator();
			Files.writeString(log, line, StandardCharsets.UTF_8,
					StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		} catch (IOException e) {
			RedtaleEconomy.LOGGER.warn("[config] edit.log 写入失败：{}", e.getMessage());
		}
	}

	/** 把当前时间戳串给调用方做备份目录名。 */
	public static String timestamp() {
		return LocalDateTime.now().format(STAMP);
	}
}
