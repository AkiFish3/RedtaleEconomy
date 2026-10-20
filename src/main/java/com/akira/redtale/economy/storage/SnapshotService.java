package com.akira.redtale.economy.storage;

import com.akira.redtale.economy.RedtaleEconomy;
import com.akira.redtale.economy.config.StorageConfig;
import com.akira.redtale.economy.economy.BalanceService;
import com.akira.redtale.economy.util.DataRoot;
import com.akira.redtale.economy.util.PathResolver;
import com.akira.redtale.economy.util.TimeUtil;
import com.google.gson.JsonObject;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * 余额快照（spec v0.6 §13.3）。
 *
 * <p>规则：同一玩家两次快照至少相隔 {@code snapshotIntervalMinutes}，且<b>余额有变动才写</b>；
 * 每天 0 点后的第一次 tick 对在线玩家做一次全量快照（{@code "full": true}）。
 */
public final class SnapshotService {

	private static final DateTimeFormatter DAY = DateTimeFormatter.ISO_LOCAL_DATE;
	private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyy-MM");

	private static final Map<UUID, Long> LAST_AT = new ConcurrentHashMap<>();
	private static final Map<UUID, Long> LAST_BALANCE = new ConcurrentHashMap<>();
	private static String lastFullDate = "";
	private static Path root;
	private static volatile boolean ready;
	private static int tickCounter;

	private SnapshotService() {
	}

	public static void init() {
		PathResolver.Resolved r = DataRoot.resolve();
		try {
			Path dir = r.path().resolve(StorageConfig.snapshotsDir().replace("/", java.io.File.separator));
			Files.createDirectories(dir);
			root = dir;
			ready = true;
			RedtaleEconomy.LOGGER.info("[snapshot] 快照目录：{}（间隔 {} 分钟）", dir,
					StorageConfig.snapshotIntervalMinutes());
		} catch (Exception e) {
			ready = false;
			RedtaleEconomy.LOGGER.error("[snapshot] 目录不可用：{}", e.getMessage());
		}
		cleanupOldFiles();
	}

	public static void onServerTick(MinecraftServer server) {
		if (!ready) return;
		if (++tickCounter % 20 != 0) return; // 每秒检查一次

		long now = System.currentTimeMillis();
		long intervalMs = Math.max(1, StorageConfig.snapshotIntervalMinutes()) * 60_000L;
		String today = LocalDate.now(TimeUtil.zone()).format(DAY);

		boolean dailyFull = StorageConfig.store().getBool("dailyFullSnapshot", true)
				&& !today.equals(lastFullDate)
				&& LocalDateTime.now(TimeUtil.zone()).getHour() == 0;

		for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
			long bal = BalanceService.get(p.getUuid());
			if (bal < 0) continue;
			Long last = LAST_AT.get(p.getUuid());
			Long lastBal = LAST_BALANCE.get(p.getUuid());

			if (dailyFull) {
				write(p.getUuid(), bal, true);
				LAST_AT.put(p.getUuid(), now);
				LAST_BALANCE.put(p.getUuid(), bal);
			} else if ((last == null || now - last >= intervalMs) && (lastBal == null || lastBal != bal)) {
				write(p.getUuid(), bal, false);
				LAST_AT.put(p.getUuid(), now);
				LAST_BALANCE.put(p.getUuid(), bal);
			}
		}
		if (dailyFull) {
			lastFullDate = today;
			RedtaleEconomy.LOGGER.info("[snapshot] {} 全量快照完成（{} 人）", today,
					server.getPlayerManager().getPlayerList().size());
		}
	}

	private static void write(UUID uuid, long balance, boolean full) {
		if (MysqlBackend.available()) {
			MysqlBackend.appendSnapshotAsync(uuid, balance, full);
			return;
		}
		Path file = fileFor(TimeUtil.businessDate(0));
		if (file == null) return;
		JsonObject o = new JsonObject();
		o.addProperty("ts", TimeUtil.nowIso());
		o.addProperty("uuid", uuid.toString());
		o.addProperty("balance", balance);
		if (full) o.addProperty("full", true);
		o.addProperty("server", StorageConfig.store().getPathString("mysql.serverId", "local"));
		try {
			Files.createDirectories(file.getParent());
			Files.writeString(file, o + System.lineSeparator(), StandardCharsets.UTF_8,
					StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		} catch (IOException e) {
			RedtaleEconomy.LOGGER.error("[snapshot] 写入失败：{}", e.getMessage());
		}
	}

	private static Path fileFor(LocalDate date) {
		if (root == null) return null;
		if ("monthly".equals(StorageConfig.granularity())) {
			return root.resolve(YearMonth.from(date).format(MONTH) + ".jsonl");
		}
		return root.resolve(date.format(MONTH)).resolve(date.format(DAY) + ".jsonl");
	}

	/**
	 * 关服前强制快照一次（B25）：不等 30 分钟间隔，把在线玩家当前余额落盘。
	 * 调用顺序：本方法 → BalanceService.shutdown()（余额落盘）→ Ledger.shutdown()（排空流水）。
	 */
	public static void flushOnline(MinecraftServer server) {
		if (server == null || !StorageConfig.snapshotsEnabled()) return;
		try {
			for (net.minecraft.server.network.ServerPlayerEntity p
					: server.getPlayerManager().getPlayerList()) {
				long bal = com.akira.redtale.economy.economy.BalanceService.get(p.getUuid());
				if (bal >= 0) write(p.getUuid(), bal, true);
			}
			RedtaleEconomy.LOGGER.info("[snapshot] 关服快照完成");
		} catch (Exception e) {
			RedtaleEconomy.LOGGER.warn("[snapshot] 关服快照失败：{}", e.getMessage());
		}
	}

	public static void forget(UUID uuid) {
		LAST_AT.remove(uuid);
		LAST_BALANCE.remove(uuid);
	}

	/** 按 retentionDays 清理旧流水 / 快照；0 = 永久保留。 */
	public static void cleanupOldFiles() {
		int days = StorageConfig.retentionDays();
		if (days <= 0) return;
		LocalDate cutoff = LocalDate.now(TimeUtil.zone()).minusDays(days);
		cleanupDir(root, cutoff);
	}

	private static void cleanupDir(Path dir, LocalDate cutoff) {
		if (dir == null || !Files.isDirectory(dir)) return;
		try (Stream<Path> s = Files.walk(dir)) {
			s.filter(Files::isRegularFile)
					.filter(f -> f.getFileName().toString().endsWith(".jsonl"))
					.filter(f -> {
						LocalDate end = periodEnd(f.getFileName().toString());
						return end != null && end.isBefore(cutoff);
					})
					.forEach(f -> {
						try {
							Files.deleteIfExists(f);
						} catch (IOException ignored) {
							// 单文件删不掉不影响其它
						}
					});
		} catch (IOException e) {
			RedtaleEconomy.LOGGER.warn("[snapshot] 清理过期文件失败：{}", e.getMessage());
		}
		// 顺手删掉清空后的月份目录（否则每年留 12 个空目录）
		try (Stream<Path> s = Files.walk(dir)) {
			s.filter(Files::isDirectory)
					.filter(p -> !p.equals(dir))
					.sorted(Comparator.reverseOrder())
					.forEach(p -> {
						try (Stream<Path> inner = Files.list(p)) {
							if (inner.findAny().isEmpty()) Files.deleteIfExists(p);
						} catch (IOException ignored) {
							// 非空/占用就留着
						}
					});
		} catch (IOException ignored) {
			// 清理空目录失败不影响数据
		}
	}

	/**
	 * 文件覆盖周期的最后一天。
	 * <ul>
	 *   <li>{@code 2026-10-20.jsonl}（daily）→ 2026-10-20</li>
	 *   <li>{@code 2026-10.jsonl}（monthly）→ 2026-10-31</li>
	 * </ul>
	 * 其它命名返回 null（不参与清理，避免误删）。
	 */
	private static LocalDate periodEnd(String name) {
		try {
			if (name.length() >= 10 && name.charAt(4) == '-' && name.charAt(7) == '-') {
				return LocalDate.parse(name.substring(0, 10));
			}
			if (name.length() >= 7 && name.charAt(4) == '-') {
				return YearMonth.parse(name.substring(0, 7)).atEndOfMonth();
			}
		} catch (Exception ignored) {
			return null;
		}
		return null;
	}
}
