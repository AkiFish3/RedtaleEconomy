package com.akira.redtale.economy.util;

import com.akira.redtale.economy.RedtaleEconomy;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;

/**
 * 配置里的路径解析（设计总则第 5 条：支持 D:/ E:/ 绝对路径与 {@code %server%} 占位）。
 *
 * <p>规则：
 * <ul>
 *   <li>{@code %server%} → 游戏/服务端根目录</li>
 *   <li>相对路径 → 相对 {@code %server%}</li>
 *   <li>含 {@code ..} → 直接拒绝（防目录穿越）</li>
 *   <li>目录不存在 → 自动创建；不可写 → 回落到 {@code %server%/redtale-data}</li>
 * </ul>
 */
public final class PathResolver {

	public static final String SERVER_TOKEN = "%server%";

	private PathResolver() {
	}

	public record Resolved(Path path, boolean fellBack, String note) {
	}

	public static Path serverRoot() {
		return FabricLoader.getInstance().getGameDir().toAbsolutePath().normalize();
	}

	/**
	 * 解析一个目录型配置。
	 *
	 * @param configured 配置原文，可为绝对路径 / {@code %server%/x} / 相对路径
	 * @param fallback   兜底值（同样走本方法解析）
	 */
	public static Resolved resolveDir(String configured, String fallback) {
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

	private static Resolved tryResolve(String configured) {
		return resolveUnder(configured, serverRoot());
	}

	/**
	 * 纯逻辑版本：{@code root} 替代 {@code %server%}，不碰 FabricLoader。
	 *
	 * <p>单独抽出来是为了能单测（B06）：绝对路径 / {@code %server%} / 相对路径 /
	 * {@code ..} 拒绝 四条规则都不依赖游戏运行时。
	 *
	 * @return null 表示该路径被拒绝或不可用（调用方回落到 fallback）
	 */
	public static Resolved resolveUnder(String configured, Path root) {
		if (configured == null || configured.isBlank()) return null;
		if (configured.contains("..")) {
			RedtaleEconomy.LOGGER.error("[path] 拒绝含 \"..\" 的路径：{}", configured);
			return null;
		}
		String expanded = configured.replace(SERVER_TOKEN, root.toString())
				.replace(SERVER_TOKEN.toUpperCase(Locale.ROOT), root.toString());
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
}
