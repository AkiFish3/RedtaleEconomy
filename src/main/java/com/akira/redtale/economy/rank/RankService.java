package com.akira.redtale.economy.rank;

import com.akira.redtale.economy.RedtaleEconomy;
import com.akira.redtale.economy.config.StorageConfig;
import com.akira.redtale.economy.economy.BalanceService;
import com.akira.redtale.economy.util.DataRoot;
import com.akira.redtale.economy.util.PathResolver;
import com.akira.redtale.economy.util.TimeUtil;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 排行榜数据源（U9b / spec §43.4 / §43.6）。
 *
 * <p>设计取舍：
 * <ul>
 *   <li><b>不在请求时算</b>：167 玩家 × 多榜的排序不能挂在包里做；
 *       每 60 秒在服务端算一次，结果放内存，请求只读缓存</li>
 *   <li><b>不轮询 tick</b>：用独立的 60 秒定时器（{@link ScheduledExecutorService}），
 *       到点再 {@code server.execute} 回主线程读余额</li>
 *   <li><b>活跃过滤在计算时做</b>（§43.6）：{@code lastSeen} 超过 activeDays 的不进榜，
 *       否则前 10 全是僵尸号</li>
 *   <li>离线玩家保留上次余额（不删数据），超过 activeDays 只是不进榜</li>
 * </ul>
 */
public final class RankService {

	public static final String FILE = "rank/players.json";

	/** 一行榜。{@code rank} 从 1 开始；{@code self=false} 表示"自己那行"来自榜外。 */
	public record Row(int rank, String name, long balance) {
	}

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

	private static final Map<UUID, Entry> ENTRIES = new LinkedHashMap<>();
	private static volatile List<Row> top = List.of();
	private static volatile int total = 0;
	private static volatile long computedAt = 0L;

	private static ScheduledExecutorService exec;
	private static Path file;

	private RankService() {
	}

	private static final class Entry {
		String name = "";
		long balance = 0L;
		String lastSeen = "";
	}

	// ---------------------------------------------------------------- 生命周期

	/** 配置 schema：rank/settings.json（/redtale edit rank/settings.json 可改）。 */
	public static final com.akira.redtale.economy.config.ConfigSchema SCHEMA =
			com.akira.redtale.economy.config.ConfigSchema.obj("排行榜（U9b）")
					.field("cacheSeconds",
							com.akira.redtale.economy.config.ConfigSchema.integer(60, 15, 3600,
									"重算间隔（秒）；服务端定时算，不在请求里排序"))
					.field("activeDays",
							com.akira.redtale.economy.config.ConfigSchema.integer(30, 0, 3650,
									"只统计最近 N 天上线过的玩家（0=不过滤）；过滤在计算时做"))
					.field("showOfflineDays",
							com.akira.redtale.economy.config.ConfigSchema.integer(7, 0, 3650,
									"榜上显示\"几天前在线\"的阈值（0=不显示）"))
					.field("topSize",
							com.akira.redtale.economy.config.ConfigSchema.integer(10, 1, 50,
									"下发前 N 名（另有自己那一行）"));

	public static void init() {
		try {
			com.akira.redtale.economy.config.ConfigManager.get().define("rank/settings.json", SCHEMA);
		} catch (Exception ignored) {
			// 配置已定义 / 管理器未就绪都不影响算榜
		}
		PathResolver.Resolved r = DataRoot.resolve();
		try {
			Path dir = r.path().resolve("rank");
			Files.createDirectories(dir);
			file = dir.resolve("players.json");
		} catch (Exception e) {
			file = null;
			RedtaleEconomy.LOGGER.error("[rank] 目录不可用，排行榜只在内存里维护：{}", e.getMessage());
		}
		load();
	}

	public static void start(MinecraftServer server) {
		if (exec != null) return;
		exec = Executors.newSingleThreadScheduledExecutor(r -> {
			Thread t = new Thread(r, "redtale-rank");
			t.setDaemon(true);
			return t;
		});
		int period = Math.max(15, cacheSeconds());
		// 先算一次（打开界面时不用等 60 秒），之后固定间隔
		exec.scheduleAtFixedRate(() -> {
			try {
				recompute(server);
			} catch (Throwable t) {
				RedtaleEconomy.LOGGER.warn("[rank] 重算失败：{}", t.getMessage());
			}
		}, 0, period, TimeUnit.SECONDS);
		RedtaleEconomy.LOGGER.info("[rank] 排行榜定时器已启动（每 {} 秒重算，缓存 {} 行）", period, top.size());
	}

	public static void stop() {
		if (exec != null) {
			exec.shutdownNow();
			exec = null;
		}
		save();
	}

	// ---------------------------------------------------------------- 计算

	/** 手动触发一次（登录 / 命令用，避免等定时器）。 */
	public static void refreshNow(MinecraftServer server) {
		try {
			recompute(server);
		} catch (Exception e) {
			RedtaleEconomy.LOGGER.warn("[rank] 手动重算失败：{}", e.getMessage());
		}
	}

	private static void recompute(MinecraftServer server) {
		if (server == null) return;
		// 必须在主线程读余额（provider 可能不是线程安全的）
		server.execute(() -> {
			long now = System.currentTimeMillis();
			String nowIso = TimeUtil.nowIso();
			for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
				UUID id = p.getUuid();
				Entry e = ENTRIES.computeIfAbsent(id, k -> new Entry());
				e.name = p.getGameProfile().getName();
				e.balance = BalanceService.get(id);
				e.lastSeen = nowIso;
			}
			List<Row> rows = rank();
			top = rows;
			computedAt = now;
			save();
		});
	}

	private static List<Row> rank() {
		int days = activeDays();
		LocalDateTime cutoff = days > 0 ? TimeUtil.now().minusDays(days) : null;
		List<Map.Entry<UUID, Entry>> alive = new ArrayList<>();
		for (Map.Entry<UUID, Entry> e : ENTRIES.entrySet()) {
			if (cutoff != null) {
				LocalDateTime seen = parse(e.getValue().lastSeen);
				if (seen == null || seen.isBefore(cutoff)) continue;
			}
			alive.add(e);
		}
		alive.sort(Comparator.comparingLong((Map.Entry<UUID, Entry> e) -> e.getValue().balance).reversed());
		List<Row> out = new ArrayList<>(alive.size());
		for (int i = 0; i < alive.size(); i++) {
			Entry e = alive.get(i).getValue();
			out.add(new Row(i + 1, e.name == null || e.name.isBlank()
					? alive.get(i).getKey().toString().substring(0, 8) : e.name, e.balance));
		}
		total = out.size();
		return List.copyOf(out);
	}

	private static LocalDateTime parse(String iso) {
		if (iso == null || iso.isBlank()) return null;
		try {
			return LocalDateTime.parse(iso);
		} catch (Exception e) {
			return null;
		}
	}

	// ---------------------------------------------------------------- 读取

	/** 前 10 行（缓存，不触发计算）。 */
	public static List<Row> top(int n) {
		List<Row> t = top;
		return t.size() <= n ? t : t.subList(0, n);
	}

	public static int total() {
		return total;
	}

	public static long computedAt() {
		return computedAt;
	}

	/** 自己那一行；不在榜内返回 rank=0 的行（客户端据此显示"未上榜"）。 */
	public static Row self(UUID uuid, String name) {
		Entry e = uuid == null ? null : ENTRIES.get(uuid);
		String display = name != null && !name.isBlank() ? name : (e != null ? e.name : "");
		// 按 uuid 反查 entry 名再匹配榜单行：玩家改名后榜上仍是旧名，按入参名匹配会漏
		String key = e != null && e.name != null && !e.name.isBlank() ? e.name : display;
		if (key != null && !key.isBlank()) {
			for (Row r : top) {
				if (r.name().equals(key)) {
					return name == null || name.equals(r.name()) ? r : new Row(r.rank(), display, r.balance());
				}
			}
		}
		long bal = e != null ? e.balance : (uuid == null ? 0L : BalanceService.get(uuid));
		return new Row(0, display == null ? "" : display, bal);
	}

	// ---------------------------------------------------------------- 落盘

	private static void save() {
		if (file == null) return;
		JsonObject root = new JsonObject();
		root.addProperty("_version", 1);
		root.addProperty("updatedAt", TimeUtil.nowIso());
		JsonObject players = new JsonObject();
		for (Map.Entry<UUID, Entry> e : ENTRIES.entrySet()) {
			JsonObject o = new JsonObject();
			o.addProperty("name", e.getValue().name);
			o.addProperty("balance", e.getValue().balance);
			o.addProperty("lastSeen", e.getValue().lastSeen);
			players.add(e.getKey().toString(), o);
		}
		root.add("players", players);
		Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
		try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
			GSON.toJson(root, w);
		} catch (Exception e) {
			RedtaleEconomy.LOGGER.warn("[rank] 写 {} 失败：{}", file.getFileName(), e.getMessage());
			return;
		}
		try {
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (Exception e) {
			try {
				Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
			} catch (Exception ignored) {
				// 上一份文件仍可用
			}
		}
	}

	private static void load() {
		ENTRIES.clear();
		if (file == null || !Files.isRegularFile(file)) return;
		try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			JsonObject root = GSON.fromJson(r, JsonObject.class);
			if (root == null || !root.has("players")) return;
			for (Map.Entry<String, com.google.gson.JsonElement> e : root.getAsJsonObject("players").entrySet()) {
				try {
					UUID id = UUID.fromString(e.getKey());
					JsonObject o = e.getValue().getAsJsonObject();
					Entry en = new Entry();
					en.name = o.has("name") ? o.get("name").getAsString() : "";
					en.balance = o.has("balance") ? o.get("balance").getAsLong() : 0L;
					en.lastSeen = o.has("lastSeen") ? o.get("lastSeen").getAsString() : "";
					ENTRIES.put(id, en);
				} catch (Exception ignored) {
					// 单条坏数据跳过
				}
			}
			top = rank();
			RedtaleEconomy.LOGGER.info("[rank] 已载入 {} 条历史榜单数据（缓存 {} 行）", ENTRIES.size(), top.size());
		} catch (Exception e) {
			RedtaleEconomy.LOGGER.warn("[rank] 读取 {} 失败：{}", file.getFileName(), e.getMessage());
		}
	}

	// ---------------------------------------------------------------- 配置

	private static int cacheSeconds() {
		try {
			return (int) com.akira.redtale.economy.config.ConfigManager.get()
					.store("rank/settings.json").getLong("cacheSeconds", 60);
		} catch (Exception e) {
			return 60;
		}
	}

	private static int activeDays() {
		try {
			return (int) com.akira.redtale.economy.config.ConfigManager.get()
					.store("rank/settings.json").getLong("activeDays", 30);
		} catch (Exception e) {
			return 30;
		}
	}
}
