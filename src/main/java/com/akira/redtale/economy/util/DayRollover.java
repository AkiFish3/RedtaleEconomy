package com.akira.redtale.economy.util;

import com.akira.redtale.economy.RedtaleEconomy;
import com.akira.redtale.economy.config.CoreConfig;
import com.akira.redtale.economy.config.StorageConfig;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 跨天重置（R38b）：<b>一次性定时 + 登录兜底</b>，不轮询。
 *
 * <p>规则：
 * <ul>
 *   <li>开服时读出「上次真正跑完重置的业务日」，算出「下一个 resetHour 时刻」，用一个
 *       {@link ScheduledExecutorService} 定一次；触发后再定下一次（每次只定一次，不是周期任务）</li>
 *   <li>上次重置日 ≠ 今天（停机跨天）时，<b>开服立刻补一次</b>；玩家登录时再比一次日期 ——
 *       兜住「定时器被跳过 / 长卡顿」</li>
 *   <li>任何一次触发都先比对业务日，同一天内重复触发直接忽略（幂等）</li>
 *   <li>业务逻辑统一回到主线程执行（{@code server.execute}），避免并发改存档</li>
 * </ul>
 *
 * <p>为什么不用 {@code ServerTickEvents}：每 tick 查一次日期是纯浪费，而且日志里会看见
 * 「每秒重置检查」这类噪声；一次性定时器的日志只有真正跨天那一刻一条。
 */
public final class DayRollover {

	/** 跨天回调：在服务端主线程执行，抛异常只记录不中断其它回调。 */
	public interface Listener {
		void onNewDay(MinecraftServer server, String businessDate);
	}

	private static final List<Listener> LISTENERS = new ArrayList<>();
	private static ScheduledExecutorService exec;
	private static ScheduledFuture<?> pending;
	private static volatile String currentDate = "";
	private static volatile String lastReason = "";
	/** 上次真正跑完重置的业务日（落盘）。{@code null} = 还没有记录（首次安装）。 */
	private static volatile String persistedDate;
	/** {@code <data>/day/state.json}；不可用时为 null，退化为「开服即认今天」。 */
	private static Path stateFile;

	private DayRollover() {
	}

	/** 注册回调（模块 onLoad 时调用；重复注册会被忽略）。 */
	public static synchronized void addListener(Listener l) {
		if (l != null && !LISTENERS.contains(l)) LISTENERS.add(l);
	}

	public static synchronized void clearListeners() {
		LISTENERS.clear();
	}

	public static String currentDate() {
		return currentDate;
	}

	public static String lastReason() {
		return lastReason;
	}

	/** 上次真正跑完重置的业务日（磁盘记录）；{@code null} = 首次安装还没写盘。 */
	public static String persistedDate() {
		return persistedDate;
	}

	// ---------------------------------------------------------------- 定时

	/** 开服调用一次。 */
	public static synchronized void start(MinecraftServer server) {
		initStateFile();
		String today = businessDate();
		String last = loadPersisted();
		persistedDate = last;
		// 上次真正跑完重置的日期（磁盘）优先；没有记录（首次安装）就认今天为基线，不补跑
		currentDate = last == null ? today : last;
		if (exec == null) {
			exec = Executors.newSingleThreadScheduledExecutor(r -> {
				Thread t = new Thread(r, "redtale-dayroll");
				t.setDaemon(true);
				return t;
			});
		}
		if (needsRollover(currentDate, today)) {
			// 停机跨天：开服立刻补一次（此时各模块的监听器都已注册）
			RedtaleEconomy.LOGGER.info("[day] 检测到停机跨天（上次 {} → 今天 {}），开服补重置", currentDate, today);
			fire(server, "startup");
		} else {
			persist(today);
		}
		scheduleNext(server);
		RedtaleEconomy.LOGGER.info("[day] 跨天定时器已启动（resetHour={}，业务日 {}，上次重置 {}）",
				resetHour(), today, currentDate);
	}

	public static synchronized void stop() {
		if (pending != null) {
			pending.cancel(false);
			pending = null;
		}
		if (exec != null) {
			exec.shutdownNow();
			exec = null;
		}
	}

	private static void scheduleNext(MinecraftServer server) {
		if (exec == null || exec.isShutdown()) return;
		long delay = millisUntilNextBoundary();
		pending = exec.schedule(() -> {
			try {
				fire(server, "schedule");
			} catch (Throwable t) {
				RedtaleEconomy.LOGGER.error("[day] 定时重置失败：{}", t.getMessage(), t);
			} finally {
				// 无论成功失败都定下一次，否则彻底停摆
				synchronized (DayRollover.class) {
					scheduleNext(server);
				}
			}
		}, delay, TimeUnit.MILLISECONDS);
		RedtaleEconomy.LOGGER.info("[day] 下次跨天重置在 {} 后（{}）",
				Duration.ofMillis(delay).toString(), nextBoundary());
	}

	/** 距下一个边界还有多少毫秒（纯计算，便于单测）。 */
	public static long millisUntilNextBoundary() {
		LocalDateTime now = LocalDateTime.now(TimeUtil.zone());
		LocalDateTime next = nextBoundary();
		long ms = Duration.between(now, next).toMillis();
		return Math.max(1000L, ms);
	}

	static LocalDateTime nextBoundary() {
		LocalDateTime now = LocalDateTime.now(TimeUtil.zone());
		LocalDateTime today = now.toLocalDate().atTime(resetHour(), 0);
		return now.isBefore(today) ? today : today.plusDays(1);
	}

	// ---------------------------------------------------------------- 登录兜底

	/** 玩家登录时调用：日期变了就补一次（补完再让玩家进）。 */
	public static void onPlayerJoin(ServerPlayerEntity player) {
		if (player == null) return;
		MinecraftServer server = player.getServer();
		if (server == null) return;
		if (!needsRollover(currentDate, businessDate())) return;
		fire(server, "join:" + player.getGameProfile().getName());
	}

	// ---------------------------------------------------------------- 触发

	/** 纯函数（可单测）：上次重置日与今天不同就需要跑一次。 */
	public static boolean needsRollover(String lastFired, String today) {
		if (today == null || today.isBlank()) return false;
		return !today.equals(lastFired);
	}

	/** 比对业务日 → 真跨天才执行回调；同一天重复调用是空操作。 */
	public static void fire(MinecraftServer server, String reason) {
		if (server == null) return;
		String today = businessDate();
		synchronized (DayRollover.class) {
			if (!needsRollover(currentDate, today)) return;
			currentDate = today;
			lastReason = reason;
		}
		RedtaleEconomy.LOGGER.info("[day] 业务日切到 {}（触发源 {}），开始重置", today, reason);
		server.execute(() -> {
			int ok = 0;
			for (Listener l : new ArrayList<>(LISTENERS)) {
				try {
					l.onNewDay(server, today);
					ok++;
				} catch (Exception e) {
					RedtaleEconomy.LOGGER.error("[day] 回调 {} 失败：{}",
							l.getClass().getSimpleName(), e.getMessage());
				}
			}
			// 只有回调全部跑完才落盘；中途崩溃则下次启动仍会补跑（回调需幂等）
			persist(today);
			RedtaleEconomy.LOGGER.info("[day] 重置完成：{}/{} 个回调", ok, LISTENERS.size());
		});
	}

	private static String businessDate() {
		return TimeUtil.today(resetHour());
	}

	private static int resetHour() {
		try {
			return CoreConfig.resetHour();
		} catch (Exception e) {
			return 0;
		}
	}

	// ---------------------------------------------------------------- 落盘

	/** {@code <data>/day/state.json}：记住「上次真正跑完重置的业务日」。 */
	private static synchronized void initStateFile() {
		if (stateFile != null) return;
		try {
			var r = com.akira.redtale.economy.util.PathResolver
					.resolveDir(StorageConfig.root(), "%server%/redtale-data");
			Path dir = r.path().resolve("day");
			Files.createDirectories(dir);
			stateFile = dir.resolve("state.json");
		} catch (Exception e) {
			stateFile = null;
			RedtaleEconomy.LOGGER.warn("[day] 状态目录不可用，停机跨天只能靠玩家登录兜底：{}", e.getMessage());
		}
	}

	private static String loadPersisted() {
		if (stateFile == null || !Files.isRegularFile(stateFile)) return null;
		try (Reader r = Files.newBufferedReader(stateFile, StandardCharsets.UTF_8)) {
			var root = com.google.gson.JsonParser.parseReader(r);
			if (root == null || !root.isJsonObject()) return null;
			var o = root.getAsJsonObject();
			String d = o.has("date") && o.get("date").isJsonPrimitive() ? o.get("date").getAsString() : "";
			return d.isBlank() ? null : d;
		} catch (Exception e) {
			RedtaleEconomy.LOGGER.warn("[day] 读取 {} 失败（按首次处理）：{}", stateFile.getFileName(), e.getMessage());
			return null;
		}
	}

	private static void persist(String date) {
		persistedDate = date;
		if (stateFile == null || date == null || date.isBlank()) return;
		var o = new com.google.gson.JsonObject();
		o.addProperty("date", date);
		o.addProperty("at", TimeUtil.nowIso());
		Path tmp = stateFile.resolveSibling("state.json.tmp");
		try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
			new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(o, w);
		} catch (Exception e) {
			RedtaleEconomy.LOGGER.warn("[day] 写 {} 失败：{}", tmp.getFileName(), e.getMessage());
			return;
		}
		try {
			Files.move(tmp, stateFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (Exception atomicFail) {
			try {
				Files.move(tmp, stateFile, StandardCopyOption.REPLACE_EXISTING);
			} catch (Exception e) {
				RedtaleEconomy.LOGGER.warn("[day] 保存业务日失败：{}", e.getMessage());
			}
		}
	}
}
