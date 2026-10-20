package com.akira.redtale.economy.util;

import com.akira.redtale.economy.RedtaleEconomy;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalTime;
import java.util.List;

/**
 * 开发用：文件触发的命令队列（供自动化测试用，避免依赖窗口焦点）。
 *
 * <p>启用方式：在 {@code config/redtale/} 下建一个空文件 {@code devcmd.enabled}。
 * 没有这个文件时本类完全不做事（生产环境默认不启用）。
 *
 * <p>用法：把要执行的命令逐行写进 {@code config/redtale/devcmd.txt}（可带或不带前导 {@code /}），
 * 保存后下一 tick 会自动执行，结果追加到 {@code config/redtale/devcmd.log}。
 *
 * <p>为什么需要它：单人游戏在窗口失去焦点时会暂停，且 Roblox 之外的键盘注入在 Java 窗口上不可靠，
 * 自动化测试无法稳定输入指令。走文件通道可以完全绕开焦点问题。
 */
public final class DevCommandFile {

	private static Path cmdFile;
	private static Path logFile;
	private static Path enabledMarker;
	private static long lastModified = -1L;

	private DevCommandFile() {
	}

	public static void init() {
		Path dir = FabricLoader.getInstance().getConfigDir().resolve("redtale");
		cmdFile = dir.resolve("devcmd.txt");
		logFile = dir.resolve("devcmd.log");
		enabledMarker = dir.resolve("devcmd.enabled");
		lastModified = -1L;
	}

	public static void tick(MinecraftServer server) {
		if (enabledMarker == null) return;
		if (!Files.isRegularFile(enabledMarker)) return;
		if (cmdFile == null || !Files.isRegularFile(cmdFile)) return;
		try {
			long m = Files.getLastModifiedTime(cmdFile).toMillis();
			if (m == lastModified) return;
			lastModified = m;

			List<String> lines = Files.readAllLines(cmdFile, StandardCharsets.UTF_8);
			log("===== run " + LocalTime.now() + " =====");
			for (String raw : lines) {
				String line = raw.trim();
				if (line.isEmpty() || line.startsWith("#")) continue;
				if (line.startsWith("/")) line = line.substring(1);
				try {
					// 优先以在线玩家身份执行：/bal /checkin 等需要玩家上下文，GUI 命令也需要玩家
					net.minecraft.server.command.ServerCommandSource src = server.getCommandSource();
					var players = server.getPlayerManager().getPlayerList();
					if (!players.isEmpty()) {
						src = players.get(0).getCommandSource();
					}
					int r = server.getCommandManager().executeWithPrefix(src, line);
					log("OK(" + r + ") " + line);
				} catch (Exception ex) {
					log("ERR " + line + " -> " + ex.getClass().getSimpleName() + ": " + ex.getMessage());
				}
			}
			log("---- done ----");
		} catch (Exception ex) {
			RedtaleEconomy.LOGGER.warn("[devcmd] 读取失败：{}", ex.toString());
		}
	}

	private static void log(String s) {
		RedtaleEconomy.LOGGER.info("[devcmd] {}", s);
		try {
			Files.writeString(logFile, s + System.lineSeparator(), StandardCharsets.UTF_8,
					StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		} catch (Exception ignored) {
			// 日志写失败不影响命令执行
		}
	}
}
