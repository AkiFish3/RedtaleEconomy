package com.akira.redtale.economy.config;

import com.akira.redtale.economy.RedtaleEconomy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 单个 JSON 配置文件（带 schema 校验 / 自动补默认 / 备份 / 原子写）。
 *
 * <p>保存流程固定为 <b>校验 → 备份 → tmp 写入 → 原子 rename</b>，
 * 任何一步失败都保留磁盘上的上一份有效文件。
 * 加载失败不会崩溃：坏文件改名成 {@code xxx.broken.<时间戳>.json}，
 * 内存里退回默认值并留一条错误记录给 OP 看。
 */
public final class ConfigStore {

	private static final Gson GSON = new GsonBuilder()
			.setPrettyPrinting()
			.disableHtmlEscaping()
			.create();
	private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

	private final String relativePath;
	private final ConfigSchema schema;
	private final Path baseDir;
	private volatile Path file;

	private JsonObject root = new JsonObject();
	private List<String> readmeLines = new ArrayList<>();
	private boolean loaded;
	private String lastError;

	ConfigStore(String relativePath, ConfigSchema schema, Path baseDir) {
		this.relativePath = relativePath;
		this.schema = schema;
		this.baseDir = baseDir;
		this.file = baseDir.resolve(relativePath);
	}

	// ---------------------------------------------------------------- 加载

	public boolean load() {
		lastError = null;
		try {
			Files.createDirectories(file.getParent());
		} catch (IOException e) {
			lastError = "无法创建目录 " + file.getParent() + "：" + e.getMessage();
			RedtaleEconomy.LOGGER.error("[config] {}", lastError);
			return false;
		}

		List<String> notes = new ArrayList<>();
		JsonObject parsed = null;

		final boolean wasPresent = Files.isRegularFile(file);
		if (wasPresent) {
			try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
				JsonElement el = GSON.fromJson(r, JsonElement.class);
				if (el != null && el.isJsonObject()) {
					parsed = el.getAsJsonObject();
				} else if (el != null) {
					lastError = relativePath + "：顶层不是 JSON 对象";
				}
			} catch (Exception e) {
				lastError = relativePath + "：解析失败 " + e.getMessage();
			}
		}

		boolean existed = parsed != null;
		if (parsed == null) {
			if (loaded) {
				// 重载时读到坏文件：保留内存里上一份有效配置，不覆盖磁盘
				RedtaleEconomy.LOGGER.error("[config] {} 重载失败，继续使用上一份有效配置", relativePath);
				return false;
			}
			if (Files.isRegularFile(file)) {
				quarantine();
				lastError = "文件损坏，已隔离并重建默认值";
			}
			parsed = new JsonObject();
			notes.add("已重建默认文件");
		} else {
			JsonElement rawVersion = parsed.get("_version");
			long ver = (rawVersion != null && rawVersion.isJsonPrimitive() && rawVersion.getAsJsonPrimitive().isNumber())
					? rawVersion.getAsLong() : 0L;
			if (ver < ConfigStore.CURRENT_VERSION) {
				int reached = ConfigMigrations.migrate(parsed, (int) ver, (int) CURRENT_VERSION);
				if (reached < (int) CURRENT_VERSION) {
					notes.add("_version " + ver + " → 目标 " + CURRENT_VERSION
							+ "：缺迁移步骤，停在 v" + reached + "（按默认值补齐剩余字段）");
				} else {
					notes.add("_version " + ver + " → " + CURRENT_VERSION + "（已按迁移链升级）");
				}
			}
		}

		JsonObject beforeContent = new JsonObject();
		parsed.entrySet().stream()
				.filter(e -> !e.getKey().startsWith("_"))
				.forEach(e -> beforeContent.add(e.getKey(), e.getValue()));
		String before = GSON.toJson(beforeContent);
		final JsonObject raw = new JsonObject();
		parsed.entrySet().stream()
				.filter(e -> !e.getKey().startsWith("_"))
				.forEach(e -> raw.add(e.getKey(), e.getValue()));
		JsonObject wrapper = wrapRoot(raw);
		schema.applyTo(wrapper, "__content__", "", notes);
		notes.replaceAll(n -> n.startsWith("__content__.") ? n.substring("__content__.".length()) : n);
		// 日志降噪：首次生成不再逐字段刷屏；已有文件最多列 8 条 + 汇总
		final int noteCount = notes.size();
		if (!wasPresent) {
			notes.clear();
			notes.add("首次生成，写入默认值 " + noteCount + " 项");
		} else if (noteCount > 8) {
			List<String> head = new ArrayList<>(notes.subList(0, 8));
			notes.clear();
			notes.addAll(head);
			notes.add("共 " + noteCount + " 处自动修正（其余省略）");
		}
		final JsonObject content = wrapper.getAsJsonObject("__content__");

		// 说明一律写进 config/redtale/README.md（spec v0.11：配置里不留 _doc/_readme 这类字段）
		List<String> docs = new ArrayList<>();
		schema.defaults(docs, relativePath);
		this.readmeLines = docs;
		boolean hadDocs = parsed.has("_readme") || parsed.has("_file");

		JsonObject withMeta = new JsonObject();
		withMeta.addProperty("_version", CURRENT_VERSION);
		content.entrySet().forEach(e -> withMeta.add(e.getKey(), e.getValue()));

		this.root = withMeta;
		this.loaded = true;

		if (!existed || hadDocs || !GSON.toJson(content).equals(before) || !notes.isEmpty()) {
			save(false);
		}

		for (String n : notes) {
			RedtaleEconomy.LOGGER.info("[config] {} {}", relativePath, n);
		}
		if (notes.size() > 3) {
			RedtaleEconomy.LOGGER.info("[config] {} 共 {} 处自动修正（详见上）", relativePath, notes.size());
		}
		return lastError == null;
	}

	private static final long CURRENT_VERSION = 1;

	private static JsonObject wrapRoot(JsonObject content) {
		JsonObject w = new JsonObject();
		w.add("__content__", content);
		return w;
	}

	private void quarantine() {
		try {
			Path broken = file.resolveSibling(file.getFileName() + ".broken." + LocalDateTime.now().format(STAMP) + ".json");
			Files.move(file, broken, StandardCopyOption.REPLACE_EXISTING);
			RedtaleEconomy.LOGGER.error("[config] {} 已重命名为 {}", relativePath, broken.getFileName());
		} catch (IOException e) {
			RedtaleEconomy.LOGGER.error("[config] {} 隔离失败：{}", relativePath, e.getMessage());
		}
	}

	// ---------------------------------------------------------------- 保存

	/**
	 * 保存。
	 * @param backup 是否先把旧文件复制到 backup/&lt;时间戳&gt;/
	 */
	public synchronized boolean save(boolean backup) {
		if (backup && Files.isRegularFile(file)) {
			try {
				Path dir = baseDir.resolve("backup").resolve(LocalDateTime.now().format(STAMP));
				Path dst = dir.resolve(relativePath);
				Files.createDirectories(dst.getParent());
				Files.copy(file, dst, StandardCopyOption.REPLACE_EXISTING);
				ConfigManager.pruneBackups(baseDir);
				ConfigManager.logEdit(relativePath, "backup + save", dst.toString());
			} catch (IOException e) {
				RedtaleEconomy.LOGGER.warn("[config] {} 备份失败，继续写：{}", relativePath, e.getMessage());
			}
		}

		Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
		try {
			Files.createDirectories(file.getParent());
			try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
				GSON.toJson(root, w);
			}
			try {
				Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (IOException atomicFail) {
				Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
			}
			return true;
		} catch (IOException e) {
			lastError = "写入 " + relativePath + " 失败：" + e.getMessage();
			RedtaleEconomy.LOGGER.error("[config] {}", lastError);
			try {
				Files.deleteIfExists(tmp);
			} catch (IOException ignored) {
				// tmp 残留不影响主文件
			}
			return false;
		}
	}

	// ---------------------------------------------------------------- 读取

	public JsonObject root() {
		return root;
	}

	public String path() {
		return relativePath;
	}

	public Path file() {
		return file;
	}

	/** schema 生成的字段说明（供 README.md 汇总）。 */
	public List<String> readmeLines() {
		return readmeLines;
	}

	public boolean isLoaded() {
		return loaded;
	}

	public String lastError() {
		return lastError;
	}

	public void clearError() {
		lastError = null;
	}

	public long getLong(String key, long def) {
		JsonElement e = root.get(key);
		return (e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber()) ? e.getAsLong() : def;
	}

	public int getInt(String key, int def) {
		return (int) getLong(key, def);
	}

	public double getDouble(String key, double def) {
		JsonElement e = root.get(key);
		return (e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber()) ? e.getAsDouble() : def;
	}

	public boolean getBool(String key, boolean def) {
		JsonElement e = root.get(key);
		return (e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean()) ? e.getAsBoolean() : def;
	}

	public String getString(String key, String def) {
		JsonElement e = root.get(key);
		return (e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isString()) ? e.getAsString() : def;
	}

	public JsonObject getObject(String key) {
		JsonElement e = root.get(key);
		return (e != null && e.isJsonObject()) ? e.getAsJsonObject() : new JsonObject();
	}

	/** 取嵌套值，key 用 "." 分隔。 */
	public JsonElement getPath(String dotPath) {
		String[] parts = dotPath.split("\\.");
		JsonElement cur = root;
		for (String p : parts) {
			if (cur == null || !cur.isJsonObject()) return null;
			cur = cur.getAsJsonObject().get(p);
		}
		return cur;
	}

	public long getPathLong(String dotPath, long def) {
		JsonElement e = getPath(dotPath);
		return (e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber()) ? e.getAsLong() : def;
	}

	public String getPathString(String dotPath, String def) {
		JsonElement e = getPath(dotPath);
		return (e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isString()) ? e.getAsString() : def;
	}

	public boolean getPathBool(String dotPath, boolean def) {
		JsonElement e = getPath(dotPath);
		return (e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean()) ? e.getAsBoolean() : def;
	}

	/** 运行时改值（管理命令 / 可视化编辑器用），改完需调用 {@link #save(boolean)}。 */
	public void setPath(String dotPath, JsonElement value) {
		String[] parts = dotPath.split("\\.");
		JsonObject cur = root;
		for (int i = 0; i < parts.length - 1; i++) {
			JsonElement next = cur.get(parts[i]);
			if (next == null || !next.isJsonObject()) {
				JsonObject created = new JsonObject();
				cur.add(parts[i], created);
				cur = created;
			} else {
				cur = next.getAsJsonObject();
			}
		}
		cur.add(parts[parts.length - 1], value);
	}

	public void setPath(String dotPath, long value) {
		setPath(dotPath, new JsonPrimitive(value));
	}

	public void setPath(String dotPath, boolean value) {
		setPath(dotPath, new JsonPrimitive(value));
	}

	public void setPath(String dotPath, String value) {
		setPath(dotPath, new JsonPrimitive(value));
	}

	/** 重新套用 schema（保存前校验，坏值会被拉回合法域）。 */
	public List<String> validate() {
		List<String> notes = new ArrayList<>();
		final JsonObject content = new JsonObject();
		root.entrySet().stream()
				.filter(e -> !e.getKey().startsWith("_"))
				.forEach(e -> content.add(e.getKey(), e.getValue()));
		JsonObject wrapper = wrapRoot(content);
		schema.applyTo(wrapper, "__content__", "", notes);
		notes.replaceAll(n -> n.startsWith("__content__.") ? n.substring("__content__.".length()) : n);
		JsonObject fixed = wrapper.getAsJsonObject("__content__");
		fixed.entrySet().forEach(e -> root.add(e.getKey(), e.getValue()));
		return notes;
	}
}
