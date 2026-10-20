package com.akira.redtale.economy.util;

import com.akira.redtale.economy.RedtaleEconomy;
import com.akira.redtale.economy.config.StorageConfig;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.WorldSavePath;

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
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * 玩家数据的根目录（R36 / spec §39.2 D7、§40.2 定案）。
 *
 * <pre>
 * &lt;world&gt;/redtale-data/     ← 账户/仓库/签到/战令/协会/邮件/委托……（跟存档走，换世界不串）
 * config/redtale/          ← 配置（服务器级，不随存档）
 * </pre>
 *
 * <p><b>为什么需要这一层</b>：{@code %world%} 在模组加载期不可用（没进存档），
 * 但各模块 {@code onLoad} 就会建目录。所以分两段：
 * <ol>
 *   <li>加载期：{@link PathResolver#resolveDir} 把 {@code %world%/*} 落到
 *       {@code %server%/redtale-data.pending}（暂存区）；</li>
 *   <li>{@code SERVER_STARTING}：{@link #onWorldReady} 设 worldRoot → 升级旧配置 →
 *       把 pending / 旧 {@code %server%/redtale-data} 合并进存档目录 → 通知各消费者重绑目录。</li>
 * </ol>
 *
 * <p><b>迁移只复制不删除</b>：旧目录原样保留（{@code .migration.json} 记一份来源清单），
 * 合并是「目标不存在才复制」，绝不覆盖新目录里已有的文件。
 */
public final class DataRoot {

	/** 数据根目录默认值（相对存档）。 */
	public static final String DEFAULT = "%world%/redtale-data";

	/** 旧默认值：服务端根目录下，换存档会串数据（R36 前的行为）。 */
	public static final String LEGACY_DEFAULT = "%server%/redtale-data";

	/** 加载期暂存目录名（在服务端根目录下）。 */
	public static final String PENDING_DIR = "redtale-data.pending";

	/** 迁移记录文件名（放在新根目录里）。 */
	public static final String MARKER = ".migration.json";
	/**
	 * R36a：旧的全局数据（{@code %server%/redtale-data}）只该迁进「第一次遇到的那个存档」。
	 * 原来只写 per-world 的 {@code .migration.json} ⇒ 单机每新建一个存档都会再复制一份旧数据，
	 * 等于新世界继承了上一份余额/签到。这个标记放在 server 根，跨存档唯一。
	 */
	public static final String ONCE_MARKER = "redtale-data.migrated";

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
	private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

	private static final List<Runnable> REBIND_HOOKS = new ArrayList<>();

	private DataRoot() {
	}

	// ---------------------------------------------------------------- 解析

	/** 数据根目录（解析 {@code storage.json → json.root}，默认 {@code %world%/redtale-data}）。 */
	public static PathResolver.Resolved resolve() {
		return PathResolver.resolveDir(StorageConfig.root(), DEFAULT);
	}

	public static Path path() {
		return resolve().path();
	}

	/** 取数据根下的子目录，顺手建好。 */
	public static Path dir(String sub) {
		Path p = path();
		if (sub == null || sub.isBlank()) return p;
		Path d = p.resolve(sub.replace("\\", "/"));
		try {
			Files.createDirectories(d);
		} catch (IOException e) {
			RedtaleEconomy.LOGGER.error("[data] 目录创建失败 {}：{}", d, e.getMessage());
		}
		return d;
	}

	/** 旧默认目录 {@code %server%/redtale-data}。 */
	public static Path legacyRoot() {
		return PathResolver.serverRoot().resolve("redtale-data");
	}

	public static Path pendingRoot() {
		return PathResolver.serverRoot().resolve(PENDING_DIR);
	}

	/** 配置里显式写了绝对路径 / {@code %server%} ⇒ 用户自己选的，不做自动迁移。 */
	public static boolean worldScoped() {
		return PathResolver.containsToken(StorageConfig.root(), PathResolver.WORLD_TOKEN);
	}

	// ---------------------------------------------------------------- 生命周期

	/** 各数据消费者注册「世界就绪后重绑目录」的回调（幂等：重复注册同一个 Runnable 会去重）。 */
	public static synchronized void onRebind(Runnable hook) {
		if (hook != null && !REBIND_HOOKS.contains(hook)) REBIND_HOOKS.add(hook);
	}

	/**
	 * {@code SERVER_STARTING} 调用（MinecraftServer 已建、存档目录已定、世界还没加载）。
	 *
	 * <p>顺序不能换：设 worldRoot → 升级旧配置 → 合并旧数据 → 重绑消费者（重绑会读盘）。
	 */
	public static void onWorldReady(MinecraftServer server) {
		if (server == null) return;
		Path world = server.getSavePath(WorldSavePath.ROOT);
		PathResolver.setWorldRoot(world);

		boolean upgraded = upgradeLegacyConfig();
		Path dst = path();
		RedtaleEconomy.LOGGER.info("[data] 数据根目录：{}{}", dst, upgraded ? "（旧配置已升级为 %world%）" : "");

		if (!worldScoped()) {
			// 用户显式指定了目录：只重绑，不迁移、不猜
			rebind();
			return;
		}
		// 先迁旧默认目录（有真实数据），再并 pending（只补空位、跳过 0 字节）
		if (StorageConfig.migrateLegacy()) {
			if (legacyMergeDone()) {
				RedtaleEconomy.LOGGER.info("[data] 旧全局数据已迁移过（{}），本存档跳过 legacy 合并", ONCE_MARKER);
			} else {
				Path src = legacyRoot();
				mergeOnce(src, dst, "legacy");
				// 源目录存在就算「尝试过」：防止每建一个新存档都重扫一遍上 GB 的旧数据
				if (Files.isDirectory(src)) markLegacyMergeDone(dst);
			}
		}
		mergeOnce(pendingRoot(), dst, "pending");
		rebind();
	}

	/** {@code SERVER_STOPPING} 调用：回主菜单后 {@code %world%} 重新不可用。 */
	public static void onServerStopping() {
		PathResolver.setWorldRoot(null);
	}

	/** 让所有数据消费者重新解析目录并重新读盘。 */
	public static void rebind() {
		synchronized (DataRoot.class) {
			for (Runnable hook : new ArrayList<>(REBIND_HOOKS)) {
				try {
					hook.run();
				} catch (Exception e) {
					RedtaleEconomy.LOGGER.error("[data] 目录重绑失败：{}", e.getMessage());
				}
			}
		}
	}

	// ---------------------------------------------------------------- 配置升级

	/**
	 * 把 {@code storage.json} 里遗留的旧默认值 {@code %server%/redtale-data} 改写成
	 * {@code %world%/redtale-data}（只在完全等于旧默认值时改，用户自定义值一律不动）。
	 *
	 * @return 是否发生了改写
	 */
	public static boolean upgradeLegacyConfig() {
		try {
			String raw = StorageConfig.root();
			if (!samePathText(raw, LEGACY_DEFAULT)) return false;
			StorageConfig.store().setPath("json.root", DEFAULT);
			StorageConfig.store().save(true);
			RedtaleEconomy.LOGGER.info("[data] storage.json 的 json.root 旧默认值 {} 已升级为 {}", LEGACY_DEFAULT, DEFAULT);
			return true;
		} catch (Exception e) {
			RedtaleEconomy.LOGGER.warn("[data] 升级 storage.json 失败（继续用原值）：{}", e.getMessage());
			return false;
		}
	}

	/** 路径文本比较：忽略大小写、正反斜杠、首尾空白与末尾斜杠。 */
	public static boolean samePathText(String a, String b) {
		return normalizeText(a).equals(normalizeText(b));
	}

	public static String normalizeText(String s) {
		if (s == null) return "";
		String out = s.trim().replace('\\', '/').toLowerCase(Locale.ROOT);
		while (out.endsWith("/") && out.length() > 1) {
			out = out.substring(0, out.length() - 1);
		}
		return out;
	}

	// ---------------------------------------------------------------- 迁移

	/** 合并报告。 */
	public record Report(String source, String target, int copied, int skipped, int emptied, List<String> failed) {
		public boolean moved() {
			return copied > 0;
		}
	}

	/**
	 * 同一个来源只迁一次（{@code .migration.json} 记录来源绝对路径）。
	 *
	 * @param id 标记里用的来源标识（legacy / pending），失败或已迁都只是打日志
	 */
	public static void mergeOnce(Path src, Path dst, String id) {
		if (src == null || dst == null) return;
		if (src.toAbsolutePath().normalize().equals(dst.toAbsolutePath().normalize())) return;
		if (!Files.isDirectory(src)) return;
		if (alreadyMigrated(dst, id)) return;

		Report r = merge(src, dst, "pending".equals(id));
		if (r == null) return;
		if (r.copied() == 0 && r.failed().isEmpty()) {
			// 没数据（全新安装只有空目录）：不写标记，下次真放了数据还能迁；也不打日志免得每次开服刷屏
			return;
		}
		markMigrated(dst, id);
		RedtaleEconomy.LOGGER.info("[data] 迁移 {} → {}：复制 {} 个文件，跳过 {} 个已存在{}",
				src, dst, r.copied(), r.skipped(),
				r.failed().isEmpty() ? "" : "，失败 " + r.failed().size() + " 个：" + r.failed().get(0));
	}

	/**
	 * 递归合并：目标不存在才复制；{@code skipEmpty} 时 0 字节文件不复制（加载期占位文件）。
	 *
	 * <p>不覆盖、不删除来源。
	 */
	public static Report merge(Path src, Path dst, boolean skipEmpty) {
		if (src == null || dst == null || !Files.isDirectory(src)) return null;
		List<String> failed = new ArrayList<>();
		int[] c = new int[3]; // copied / skipped / emptied
		try {
			Files.createDirectories(dst);
		} catch (IOException e) {
			RedtaleEconomy.LOGGER.error("[data] 目标目录不可写 {}：{}", dst, e.getMessage());
			return new Report(src.toString(), dst.toString(), 0, 0, 0, List.of(dst.toString()));
		}
		try (Stream<Path> walk = Files.walk(src)) {
			List<Path> all = walk.sorted(Comparator.naturalOrder()).toList();
			for (Path p : all) {
				if (p.equals(src)) continue;
				Path rel = src.relativize(p);
				Path target = dst.resolve(rel.toString());
				if (Files.isDirectory(p)) {
					try {
						Files.createDirectories(target);
						// 记录一次「只有目录没有文件」的条目，用于日志
						if (isEmptyDir(p)) c[2]++;
					} catch (IOException e) {
						failed.add(rel + "（建目录失败）");
					}
					continue;
				}
				if (!Files.isRegularFile(p)) continue;
				try {
					if (skipEmpty && Files.size(p) == 0) {
						c[1]++;
						continue;
					}
					if (Files.exists(target)) {
						c[1]++;
						continue;
					}
					Files.createDirectories(target.getParent());
					Files.copy(p, target, StandardCopyOption.COPY_ATTRIBUTES);
					c[0]++;
				} catch (IOException e) {
					failed.add(rel + "（" + e.getMessage() + "）");
				}
			}
		} catch (IOException e) {
			failed.add(src + "（遍历失败：" + e.getMessage() + "）");
		}
		return new Report(src.toString(), dst.toString(), c[0], c[1], c[2], failed);
	}

	private static boolean isEmptyDir(Path dir) {
		try (Stream<Path> s = Files.list(dir)) {
			return s.findAny().isEmpty();
		} catch (IOException e) {
			return false;
		}
	}

	// ---------------------------------------------------------------- 标记

	/** R36a：server 级「旧数据已迁过」标记的路径；serverRoot 不可用时返回 null。 */
	private static Path onceMarkerPath() {
		try {
			return PathResolver.serverRoot().resolve(ONCE_MARKER);
		} catch (Exception e) {
			return null;
		}
	}

	/** R36a：旧全局数据是否已经迁进过某个存档（跨存档唯一）。 */
	public static boolean legacyMergeDone() {
		Path p = onceMarkerPath();
		return p != null && Files.isRegularFile(p);
	}

	private static void markLegacyMergeDone(Path dst) {
		Path p = onceMarkerPath();
		if (p == null) return;
		try {
			Files.createDirectories(p.getParent());
			JsonObject o = new JsonObject();
			o.addProperty("at", LocalDateTime.now().format(
					DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
			o.addProperty("into", dst.toString());
			try (Writer w = Files.newBufferedWriter(p, StandardCharsets.UTF_8)) {
				GSON.toJson(o, w);
			}
			RedtaleEconomy.LOGGER.info("[data] 旧全局数据已迁入 {}（写入一次性标记 {}）", dst, ONCE_MARKER);
		} catch (IOException e) {
			RedtaleEconomy.LOGGER.warn("[data] 写一次性迁移标记失败：{}", e.getMessage());
		}
	}

	private static Path markerPath(Path dst) {
		return dst.resolve(MARKER);
	}

	/** 标记里按「来源 id」记（legacy / pending），不按绝对路径 —— 存档整体搬机器也还有效。 */
	public static boolean alreadyMigrated(Path dst, String id) {
		Path f = markerPath(dst);
		if (!Files.isRegularFile(f)) return false;
		try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
			JsonObject o = JsonParser.parseReader(r).getAsJsonObject();
			JsonArray arr = o.has("done") && o.get("done").isJsonArray()
					? o.getAsJsonArray("done") : new JsonArray();
			for (int i = 0; i < arr.size(); i++) {
				if (id.equals(arr.get(i).getAsString())) return true;
			}
		} catch (Exception ignored) {
			// 标记坏了就当没迁过：合并本身是「不覆盖」，重复跑也安全
		}
		return false;
	}

	public static void markMigrated(Path dst, String id) {
		Path f = markerPath(dst);
		JsonObject o = new JsonObject();
		JsonArray arr = new JsonArray();
		try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
			JsonObject old = JsonParser.parseReader(r).getAsJsonObject();
			if (old.has("done") && old.get("done").isJsonArray()) {
				JsonArray prev = old.getAsJsonArray("done");
				for (int i = 0; i < prev.size(); i++) arr.add(prev.get(i).getAsString());
			}
		} catch (Exception ignored) {
			// 首次写
		}
		boolean exists = false;
		for (int i = 0; i < arr.size(); i++) {
			if (id.equals(arr.get(i).getAsString())) exists = true;
		}
		if (!exists) arr.add(id);
		o.add("done", arr);
		o.addProperty("at", LocalDateTime.now().format(TS));
		o.addProperty("note", "迁移只复制不删除；旧目录仍保留原数据，确认无误后可手工清理");
		try (Writer w = Files.newBufferedWriter(f, StandardCharsets.UTF_8)) {
			GSON.toJson(o, w);
		} catch (IOException e) {
			RedtaleEconomy.LOGGER.warn("[data] 迁移标记写入失败：{}", e.getMessage());
		}
	}
}
