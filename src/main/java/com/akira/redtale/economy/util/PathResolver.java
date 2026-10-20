package com.akira.redtale.economy.util;

import com.akira.redtale.economy.RedtaleEconomy;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;

/**
 * 配置里的路径解析（设计总则第 5 条：支持 D:/ E:/ 绝对路径与占位符）。
 *
 * <p>规则：
 * <ul>
 *   <li>{@code %server%} → 游戏/服务端根目录（{@code .minecraft} 或服务端目录）</li>
 *   <li>{@code %world%} → 当前存档目录（{@code level.dat} 所在目录）；只有服务器起来后才知道，
 *       见 {@link DataRoot} 的两段式时序</li>
 *   <li>相对路径 → 相对 {@code %server%}</li>
 *   <li>含 {@code ..} → 直接拒绝（防目录穿越）</li>
 *   <li>目录不存在 → 自动创建；不可写 → 回落到 {@code %server%/redtale-data}</li>
 * </ul>
 *
 * <p><b>{@code %world%} 时序（R36 / spec §40.2）</b>：模组加载期（{@code onInitialize}）还没选存档，
 * 拿不到存档目录。此时带 {@code %world%} 的路径一律解析到
 * {@code %server%/redtale-data.pending}（临时暂存），等 {@code SERVER_STARTING} 里
 * {@link DataRoot#onWorldReady} 设好 worldRoot 再把 pending 合并进真正的存档目录。
 * 这样任何模块在加载期都能安全建目录，既不会写进 config，也不会因为「world 未知」初始化失败。
 */
public final class PathResolver {

	public static final String SERVER_TOKEN = "%server%";
	public static final String WORLD_TOKEN = "%world%";

	/** 存档目录；只有服务器启动时才非 null。 */
	private static volatile Path worldRoot;

	private PathResolver() {
	}

	public record Resolved(Path path, boolean fellBack, String note) {
	}

	public static Path serverRoot() {
		return FabricLoader.getInstance().getGameDir().toAbsolutePath().normalize();
	}

	/** 当前存档目录；{@code null} = 还没进存档（模组加载期 / 回主菜单）。 */
	public static Path worldRoot() {
		return worldRoot;
	}

	public static boolean worldKnown() {
		return worldRoot != null;
	}

	/** 由 {@link DataRoot#onWorldReady} 调用；传 null 表示回主菜单，后续解析重新落到 pending。 */
	public static void setWorldRoot(Path dir) {
		worldRoot = dir == null ? null : dir.toAbsolutePath().normalize();
	}

	/** {@code %world%} 未就绪时的临时根目录。 */
	public static Path pendingRoot() {
		return serverRoot().resolve(DataRoot.PENDING_DIR);
	}

	/**
	 * 解析一个目录型配置。
	 *
	 * @param configured 配置原文，可为绝对路径 / {@code %server%/x} / {@code %world%/x} / 相对路径
	 * @param fallback   兜底值（同样走本方法解析）
	 */
	public static Resolved resolveDir(String configured, String fallback) {
		// ★配置带 %world% 且存档未知：先落 pending★ 模组加载期所有模块都会调一次，
		//   报 ERROR 会刷屏；真按 server 根目录写又会串档。
		//   （只看 configured：用户显式写了绝对路径/D: 或 %server% 就不该被推迟）
		if (containsToken(configured, WORLD_TOKEN) && worldRoot == null) {
			return syncPending();
		}

		Resolved first = tryResolve(configured);
		if (first != null) return first;

		RedtaleEconomy.LOGGER.warn("[path] \"{}\" 不可用，回落到 \"{}\"", configured, fallback);
		Resolved second = tryResolve(fallback);
		if (second != null) {
			return new Resolved(second.path(), true, "从 \"" + configured + "\" 回落");
		}
		// 连兜底都失败：用 server/redtale-data 硬兜底，不让模块起不来
		Path last = serverRoot().resolve("redtale-data");
		try {
			Files.createDirectories(last);
		} catch (IOException ignored) {
			// 只能返回未创建路径，调用方需自行处理写失败
		}
		return new Resolved(last, true, "两级回落均失败，使用内置默认");
	}

	private static Resolved syncPending() {
		Path p = pendingRoot();
		try {
			Files.createDirectories(p);
			return new Resolved(p, true, "world 未就绪，暂存 " + DataRoot.PENDING_DIR);
		} catch (IOException e) {
			RedtaleEconomy.LOGGER.error("[path] pending 目录创建失败：{}", e.getMessage());
			return new Resolved(p, true, "world 未就绪且 pending 不可写");
		}
	}

	private static Resolved tryResolve(String configured) {
		return resolveUnder(configured, serverRoot(), worldRoot);
	}

	/**
	 * 纯逻辑版本（单测用）：{@code root} 替代 {@code %server%}，不碰 FabricLoader。
	 *
	 * <p>两参重载视为「存档未知」，带 {@code %world%} 的路径一律返回 null。
	 *
	 * @return null 表示该路径被拒绝或不可用（调用方回落到 fallback）
	 */
	public static Resolved resolveUnder(String configured, Path root) {
		return resolveUnder(configured, root, null);
	}

	/**
	 * 纯逻辑版本：{@code root} 替代 {@code %server%}，{@code world} 替代 {@code %world%}。
	 *
	 * @return null 表示被拒绝或不可用；{@code %world%} 且 world 为 null 时同样返回 null
	 */
	public static Resolved resolveUnder(String configured, Path root, Path world) {
		if (configured == null || configured.isBlank()) return null;
		if (configured.contains("..")) {
			RedtaleEconomy.LOGGER.error("[path] 拒绝含 \"..\" 的路径：{}", configured);
			return null;
		}
		if (containsToken(configured, WORLD_TOKEN) && world == null) {
			return null;
		}
		String expanded = replaceToken(configured, SERVER_TOKEN, root.toString());
		if (world != null) {
			expanded = replaceToken(expanded, WORLD_TOKEN, world.toAbsolutePath().normalize().toString());
		}
		try {
			Path p = Paths.get(expanded);
			if (!p.isAbsolute()) {
				p = root.resolve(p);
			}
			p = p.normalize();
			// 归一化后仍跑出 root 之外（例如 root 本身是符号链接）也拒绝
			Files.createDirectories(p);
			if (!Files.isWritable(p)) {
				RedtaleEconomy.LOGGER.error("[path] 目录不可写：{}", p);
				return null;
			}
			return new Resolved(p, false, "ok");
		} catch (Exception e) {
			RedtaleEconomy.LOGGER.error("[path] 解析失败 \"{}\"：{}", configured, e.getMessage());
			return null;
		}
	}

	public static boolean containsToken(String s, String token) {
		return s != null && s.toLowerCase(Locale.ROOT).contains(token);
	}

	/** 大小写不敏感地替换占位符（{@code %SERVER%} 也认）。 */
	public static String replaceToken(String s, String token, String value) {
		if (s == null || !containsToken(s, token)) return s;
		String out = s;
		int at;
		while ((at = out.toLowerCase(Locale.ROOT).indexOf(token)) >= 0) {
			out = out.substring(0, at) + value + out.substring(at + token.length());
		}
		return out;
	}
}
