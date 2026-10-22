package com.akira.redtale.economy.module.impl;

import com.akira.redtale.economy.RedtaleEconomy;
import com.akira.redtale.economy.command.TaskCommands;
import com.akira.redtale.economy.config.ConfigManager;
import com.akira.redtale.economy.config.ConfigSchema;
import com.akira.redtale.economy.config.ConfigStore;
import com.akira.redtale.economy.config.StorageConfig;
import com.akira.redtale.economy.economy.BalanceService;
import com.akira.redtale.economy.module.RedtaleModule;
import com.akira.redtale.economy.util.DataRoot;
import com.akira.redtale.economy.util.PathResolver;
import com.akira.redtale.economy.util.TaskMath;
import com.akira.redtale.economy.util.TimeUtil;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.server.command.ServerCommandSource;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * 每日任务模块（spec §5 + v0.2 修订算法）。
 *
 * <p>生成算法：随机抽 k 个不同物品（各自带 weight），求 {@code c_i ≥ 1} 使
 * {@code Σ c_i·w_i = T}，其中 {@code T ∈ [B-r, B+r]}（flex 关闭时 T=B）。
 * 无解 → 重掷（≤ maxRetries）→ 仍无解则取最接近的可行解并 WARN。
 *
 * <p>实例落盘带 {@code rewardSpec} 快照：后续改配置不影响已生成任务的奖励。
 */
public final class TaskModule implements RedtaleModule {

	public static final String SETTINGS_FILE = "tasks/settings.json";
	public static final String POOLS_DIR = "tasks/pools";
	public static final String DEFINITIONS_DIR = "tasks/definitions";

	// ---------------------------------------------------------------- T06 周期（每日 / 每周）

	/** 周期 id。落盘、协议、定义文件共用这一套字符串，别另造同义词。 */
	public static final String PERIOD_DAILY = "daily";
	public static final String PERIOD_WEEKLY = "weekly";
	/**
	 * 周任务的 slot 起点。
	 *
	 * <p>日任务 slot 从 0 开始、周任务从 100 开始 ⇒ 同一个 {@code /tasks claim <slot>}
	 * 能唯一定位到一条任务，不用给命令加第二个参数（老命令与老截图照样有效）。
	 */
	public static final int WEEKLY_SLOT_BASE = 100;
	/** 兼容旧版平铺文件（同样按新结构解析，只是名字不带目录层级）。 */
	private static final String LEGACY_POOLS = "tasks/pools.json";
	private static final String LEGACY_DAILY = "tasks/daily.json";
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();


	/** 池用「对象 map」而非数组：{@code {"pools":{"food":{"items":[...]}}}}。 */
	public static final ConfigSchema SETTINGS_SCHEMA = ConfigSchema.obj("每日任务")
			.field("tasksPerDay", ConfigSchema.integer(3, 1, 20, "每天任务数"))
			.field("tasksPerWeek", ConfigSchema.integer(3, 0, 20,
					"每周任务数（周任务定义用 period=weekly；0=关闭周任务）"))
			.field("weeklyResetDay", ConfigSchema.integer(1, 1, 7,
					"周任务重置的星期（1=周一 … 7=周日）"))
			.field("weeklyRewardScale", ConfigSchema.integer(250, 1, 10_000,
					"周任务奖励倍率（百分比，250=2.5 倍；生成时套用，改配置不影响已生成的）"))
			.field("refreshPerDay", ConfigSchema.integer(3, 0, 100, "每人每天手动刷新次数"))
			.field("resetHour", ConfigSchema.integer(0, 0, 23, "每日重置小时"))
			.field("allowWeightFlex", ConfigSchema.bool(true, "允许实际预算在 budget±range 内浮动"))
			.field("weightFlexRange", ConfigSchema.integer(1, 0, 1000, "浮动范围"))
			.field("maxRetries", ConfigSchema.integer(20, 0, 200, "无整数解时最多重掷次数"))
			.field("defaultBudget", ConfigSchema.integer(32, 1, 100000,
					"定义未写 budget 时用的默认预算"))
			.field("defaultKinds", ConfigSchema.integer(1, 1, 32,
					"定义未写 kinds 时用几种物品"))
			.field("conflictPolicy", ConfigSchema.choice("outer", List.of("outer", "merge"),
					"同 id 任务池冲突策略：outer=路径浅优先，merge=合并物品"))
			.field("defaultReward", ConfigSchema.obj("默认奖励（模板未指定时用）")
					.field("money", ConfigSchema.integer(100, 0, 1_000_000_000_000L, "奖励铜币")))
			.field("containerPolicy", ConfigSchema.choice("ignore", List.of("ignore", "shulker", "all"),
					"任务缴纳时是否解析容器内容：ignore=只看背包格（默认，零开销）；"
							+ "shulker=计入潜影盒内容；all=任何带 BlockEntityTag.Items 的容器物品（含旅行背包等）"))
			.field("refreshTicket", ConfigSchema.obj("刷新券（R29）")
					.field("item", ConfigSchema.string("redtale_economy:refresh_ticket",
							"刷新次数用完后自动消耗的物品 id；留空=不启用票据"))
					.field("fromWarehouse", ConfigSchema.bool(true,
							"背包没有券时是否去仓库找")));

	// ---------------------------------------------------------------- 池 / 定义缓存

	/** poolId → 物品列表（已合并冲突）。 */
	private static final Map<String, List<JsonObject>> POOLS = new LinkedHashMap<>();
	/** poolId → 池显示名（任务卡标题用；没写就退回 poolId）。 */
	private static final Map<String, String> POOL_LABELS = new LinkedHashMap<>();
	private static final List<JsonObject> DEFS = new ArrayList<>();
	private static volatile long catalogsLoadedAt;

	// ---------------------------------------------------------------- 模型

	public static final class ItemCount {
		public String id;
		public int count;

		public ItemCount() {
		}

		public ItemCount(String id, int count) {
			this.id = id;
			this.count = count;
		}
	}

	public static final class Task {
		public int slot;
		/** T06：{@code daily} / {@code weekly}；旧存档没有该字段，读回时按 daily 处理。 */
		public String period = PERIOD_DAILY;
		public String pool = "";
		/** 任务卡标题（池的 displayName；旧存档没有这个字段，读取时按 pool 回填）。 */
		public String label = "";
		public List<ItemCount> items = new ArrayList<>();
		public long budget;
		public long appliedBudget;
		public String completion = "submit";
		/**
		 * T05：事件型任务的触发源（{@code kill}/{@code mine}/{@code travel}），空串 = 缴纳型。
		 * 事件型一律是 {@code completion=detect}：由服务端记账，领取时只校验不扣物。
		 */
		public String event = "";
		/** 事件目标 id（实体 / 方块）；{@code travel} 为空串 = 任何位移都算。 */
		public String eventTarget = "";
		public long reward = 0;
		public boolean claimed;
		public String claimedAt = "";
	}

	public static final class Daily {
		/** 周期键：日任务 = 业务日 {@code 2026-10-22}；周任务 = {@code W2026-10-19}（本周起始日）。 */
		public String date = "";
		/** T06：{@code daily} / {@code weekly}。旧存档没有该字段 ⇒ 读回后按文件所在目录回填。 */
		public String period = PERIOD_DAILY;
		public List<Task> tasks = new ArrayList<>();
		public int refreshesLeft;
	}

	/**
	 * T05 事件型任务的当日进度：{@code key = "<slot>|<target>"}，value 是已累计数（封顶 need）。
	 *
	 * <p>和任务本身分文件存：任务是「今天该做什么」，进度是「做到哪了」，两者写入频率差两个数量级
	 * （挖矿每秒几条 vs 任务一天生成一次），合并会让任务文件被高频重写。
	 */
	public static final class Progress {
		public String date = "";
		public Map<String, Integer> counts = new LinkedHashMap<>();
	}

	/**
	 * T06：缓存键 = {@code "<uuid>|<period>"}。
	 *
	 * <p>日/周两套任务必须分桶：同一个 UUID 同时有「今天的」和「本周的」两份状态，
	 * 用 {@code Map<UUID,…>} 会互相覆盖（周任务一生成就把日任务挤出缓存，界面就变成「今天没任务」）。
	 */
	private static final Map<String, Progress> PROGRESS = new LinkedHashMap<>();
	/** 任务驻留（高频事件路径不再读盘）；save/claim/刷新都写回同一对象。 */
	private static final Map<String, Daily> CACHE = new LinkedHashMap<>();
	/** 脏进度同样按 {@code uuid|period} 记。 */
	private static final java.util.Set<String> DIRTY = new java.util.LinkedHashSet<>();
	private static final Map<UUID, TravelMark> TRAVEL = new LinkedHashMap<>();
	private static Path progressDir;
	private static boolean hooksReady;
	private static long lastFlushAt;

	// ---------------------------------------------------------------- 模块

	@Override
	public String id() {
		return "tasks";
	}

	@Override
	public void defineConfig(ConfigManager cfg) {
		cfg.define(SETTINGS_FILE, SETTINGS_SCHEMA);
	}

	@Override
	public void onLoad(ConfigManager cfg) {
		resolveDir();
		loadCatalogs();
		installHooks();
		// T56：Hub 任务页的收包注册（幂等；放在模块里而不是根类，模块关闭时页面本来也看不到）
		com.akira.redtale.economy.net.TaskNetworking.register();
	}

	@Override
	public void onReload(ConfigManager cfg) {
		resolveDir();
		loadCatalogs();
		// 管理员可能手改了 definitions/pools：清驻留缓存，下次 load 重新读盘
		CACHE.clear();
	}

	@Override
	public void registerCommands(CommandDispatcher<ServerCommandSource> dispatcher) {
		TaskCommands.register(dispatcher);
	}

	/** 全局参数（tasks/settings.json）。 */
	public static ConfigStore settings() {
		return ConfigManager.get().store(SETTINGS_FILE);
	}

	// ---------------------------------------------------------------- T05 事件型任务

	/** 位移记账用的锚点：换维度或重生都要重置，否则会被算成一次「超长旅行」。 */
	private static final class TravelMark {
		String world = "";
		double x, y, z;
		double rem;
	}

	/**
	 * 登记事件订阅。只在模块首次加载时装一次（reload 不重复装，否则同一事件会记两遍）。
	 *
	 * <p>craft 类暂无订阅源：Fabric 没有「玩家合成完成」事件，需要 mixin（归 B 队列的
	 * {@code mixin/**}），所以 {@code event=craft} 的定义会被跳过并在加载时告警。
	 */
	private static void installHooks() {
		if (hooksReady) return;
		hooksReady = true;

		com.akira.redtale.economy.module.ModuleEvents.onDeath("tasks", (victim, source, killer) -> {
			if (victim == null || killer == null || victim == killer) return;
			String id = net.minecraft.registry.Registries.ENTITY_TYPE.getId(victim.getType()).toString();
			addEvent(killer.getUuid(), "kill", id, 1);
		});

		com.akira.redtale.economy.module.ModuleEvents.onBlockBreak("tasks",
				(player, world, pos, state) -> {
					if (player == null || state == null) return;
					String id = net.minecraft.registry.Registries.BLOCK.getId(state.getBlock()).toString();
					addEvent(player.getUuid(), "mine", id, 1);
				});

		com.akira.redtale.economy.module.ModuleEvents.onSecond("tasks", TaskModule::tickTravel);

		com.akira.redtale.economy.module.ModuleEvents.onQuit("tasks", player -> {
			TRAVEL.remove(player.getUuid());
			flushOne(player.getUuid());
		});
	}

	/**
	 * 每秒结算一次水平位移（单位：格）。单秒位移 > 128 视为传送/回城，不计入，
	 * 否则一个 {@code /tp} 就能刷满旅行任务。
	 */
	private static void tickTravel(net.minecraft.server.MinecraftServer server) {
		if (server == null) return;
		try {
			for (net.minecraft.server.network.ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
				if (p == null || p.isRemoved()) continue;
				String world;
				try {
					world = p.getWorld().getRegistryKey().getValue().toString();
				} catch (Exception e) {
					continue;
				}
				double x = p.getX(), y = p.getY(), z = p.getZ();
				TravelMark m = TRAVEL.get(p.getUuid());
				if (m == null) {
					m = new TravelMark();
					m.world = world;
					m.x = x;
					m.y = y;
					m.z = z;
					TRAVEL.put(p.getUuid(), m);
					continue;
				}
				if (!world.equals(m.world)) {
					m.world = world;
					m.x = x;
					m.y = y;
					m.z = z;
					m.rem = 0;
					continue;
				}
				double dx = x - m.x, dy = y - m.y, dz = z - m.z;
				double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
				m.x = x;
				m.y = y;
				m.z = z;
				if (dist <= 0.05 || dist > 128) continue;
				m.rem += dist;
				int meters = (int) Math.floor(m.rem);
				if (meters <= 0) continue;
				m.rem -= meters;
				addEvent(p.getUuid(), "travel", "", meters);
			}
			if (!DIRTY.isEmpty() && System.currentTimeMillis() - lastFlushAt > 5000) flushProgress();
		} catch (Exception e) {
			RedtaleEconomy.LOGGER.error("[tasks] 位移结算失败：{}", e.getMessage());
		}
	}

	/** 两套周期。遍历顺序固定（daily 先），保证事件进度的累加顺序可预测。 */
	private static final List<String> PERIODS = List.of(PERIOD_DAILY, PERIOD_WEEKLY);

	/** 缓存键：{@code "<uuid>|<period>"}；所有按周期分桶的 map 都走它。 */
	private static String ck(UUID uuid, String period) {
		return uuid + "|" + (period == null || period.isBlank() ? PERIOD_DAILY : period);
	}

	/**
	 * 给该玩家的所有未领取事件任务加进度。{@code target} 空串表示「任意目标」。
	 *
	 * <p>只写内存 + 标脏：挖矿是高频事件，每条都落盘会把磁盘打满；真正的 flush 在
	 * {@link #tickTravel} 的 5 秒节流里，以及下线/领取时。
	 *
	 * <p>T06：日/周两套任务都要涨 —— 只扫日任务的话，周任务（如「本周挖 512 块石头」）
	 * 会永远停在 0，而界面上看不出是「没记」还是「没做」，玩家会当成 bug。
	 */
	public static synchronized void addEvent(UUID uuid, String type, String target, int amount) {
		if (uuid == null || type == null || type.isBlank() || amount <= 0) return;
		String t = target == null ? "" : target;
		boolean changed = false;
		for (String period : PERIODS) {
			Daily d = load(uuid, period);
			Progress p = progress(uuid, d);
			boolean touched = false;
			for (Task task : d.tasks) {
				if (task == null || task.claimed) continue;
				if (!type.equalsIgnoreCase(task.event)) continue;
				if (task.eventTarget != null && !task.eventTarget.isBlank()
						&& !task.eventTarget.equalsIgnoreCase(t)) continue;
				int need = needOf(task);
				if (need <= 0) continue;
				String key = key(task.slot, task.eventTarget);
				int cur = p.counts.getOrDefault(key, 0);
				if (cur >= need) continue;
				p.counts.put(key, Math.min(need, cur + amount));
				touched = true;
			}
			if (touched) {
				DIRTY.add(ck(uuid, period));
				changed = true;
			}
		}
	}

	/** 事件型任务的当前进度；非事件型返回 -1（调用方据此退回背包计数）。 */
	public static int eventHave(UUID uuid, Daily d, Task task) {
		if (uuid == null || d == null || task == null) return -1;
		if (task.event == null || task.event.isBlank()) return -1;
		Progress p = progress(uuid, d);
		int v = p.counts.getOrDefault(key(task.slot, task.eventTarget), 0);
		return Math.min(v, needOf(task));
	}

	/** T06：按任务自己的 {@code period} 找对那一桶进度（命令侧拿到的 task 可能属周任务）。 */
	public static int eventHave(UUID uuid, Task task) {
		if (uuid == null || task == null) return -1;
		if (task.event == null || task.event.isBlank()) return -1;
		return eventHave(uuid, load(uuid, task.period), task);
	}

	/** 读进度（内存优先；文件里的周期键不是当前周期就当空的，不继承上一周期的进度）。 */
	private static Progress progress(UUID uuid, Daily d) {
		String period = d.period == null || d.period.isBlank() ? PERIOD_DAILY : d.period;
		String date = d.date == null ? "" : d.date;
		String ckey = ck(uuid, period);
		Progress p = PROGRESS.get(ckey);
		if (p != null && date.equals(p.date)) return p;
		p = new Progress();
		p.date = date;
		Path f = progressFile(uuid, period);
		if (f != null && Files.isRegularFile(f)) {
			try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
				Progress onDisk = GSON.fromJson(r, Progress.class);
				if (onDisk != null && date.equals(onDisk.date) && onDisk.counts != null) p = onDisk;
			} catch (Exception e) {
				RedtaleEconomy.LOGGER.error("[tasks] 读进度 {} 失败：{}", uuid, e.getMessage());
			}
		}
		PROGRESS.put(ckey, p);
		return p;
	}

	private static Path progressFile(UUID uuid, String period) {
		if (progressDir == null || uuid == null) return null;
		return PERIOD_WEEKLY.equals(period)
				? progressDir.resolve("weekly").resolve(uuid + ".json")
				: progressDir.resolve(uuid + ".json");
	}

	/** 只落一个玩家的进度（下线 / 领取时调用）；日、周两桶一起刷。 */
	public static synchronized void flushOne(UUID uuid) {
		if (uuid == null) return;
		boolean any = false;
		for (String period : PERIODS) {
			String ckey = ck(uuid, period);
			if (!DIRTY.contains(ckey)) continue;
			Progress p = PROGRESS.get(ckey);
			if (p != null) saveProgress(uuid, period, p);
			DIRTY.remove(ckey);
			any = true;
		}
		if (any) lastFlushAt = System.currentTimeMillis();
	}

	/** 把脏进度批量落盘（5 秒节流一次）。 */
	public static synchronized void flushProgress() {
		if (DIRTY.isEmpty()) return;
		for (String ckey : new ArrayList<>(DIRTY)) {
			int i = ckey.lastIndexOf('|');
			if (i <= 0) continue;
			UUID u;
			try {
				u = UUID.fromString(ckey.substring(0, i));
			} catch (Exception e) {
				continue;
			}
			Progress p = PROGRESS.get(ckey);
			if (p != null) saveProgress(u, ckey.substring(i + 1), p);
		}
		DIRTY.clear();
		lastFlushAt = System.currentTimeMillis();
	}

	private static void saveProgress(UUID uuid, String period, Progress p) {
		Path f = progressFile(uuid, period);
		if (f == null) return;
		try {
			Files.createDirectories(f.getParent());
		} catch (IOException e) {
			RedtaleEconomy.LOGGER.error("[tasks] 建进度目录失败：{}", e.getMessage());
			return;
		}
		Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
		try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
			w.write(GSON.toJson(p));
		} catch (IOException e) {
			RedtaleEconomy.LOGGER.error("[tasks] 写进度 {} 失败：{}", uuid, e.getMessage());
			return;
		}
		try {
			Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			try {
				Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
			} catch (IOException e2) {
				RedtaleEconomy.LOGGER.error("[tasks] 落盘进度 {} 失败：{}", uuid, e2.getMessage());
			}
		}
	}

	/** 任务需求量 = 第一个条目（事件型只有一个目标）。 */
	public static int needOf(Task task) {
		if (task == null || task.items == null || task.items.isEmpty()) return 0;
		ItemCount ic = task.items.get(0);
		return ic == null ? 0 : Math.max(0, ic.count);
	}

	private static String key(int slot, String target) {
		return slot + "|" + (target == null ? "" : target.toLowerCase(java.util.Locale.ROOT));
	}

	/** 容器检测策略（R26，默认 ignore 时完全跳过解析）。 */
	public static String containerPolicy() {
		return settings().getString("containerPolicy", "ignore");
	}

	public static long catalogsLoadedAt() {
		return catalogsLoadedAt;
	}

	public static int poolCount() {
		return POOLS.size();
	}

	public static int definitionCount() {
		return DEFS.size();
	}

	/** 递归扫描 pools/ 与 definitions/（{@code _} 开头跳过），并兼容旧平铺文件。 */
	private static synchronized void loadCatalogs() {
		POOLS.clear();
		POOL_LABELS.clear();
		DEFS.clear();
		Path base = ConfigManager.get().baseDir();
		boolean merge = "merge".equalsIgnoreCase(settings().getString("conflictPolicy", "outer"));

		List<Path> poolFiles = scan(base.resolve(POOLS_DIR));
		Path legacyPools = base.resolve(LEGACY_POOLS);
		if (Files.isRegularFile(legacyPools)) poolFiles.add(0, legacyPools);
		for (Path f : poolFiles) readPools(f, merge);

		List<Path> defFiles = scan(base.resolve(DEFINITIONS_DIR));
		Path legacyDaily = base.resolve(LEGACY_DAILY);
		if (Files.isRegularFile(legacyDaily)) defFiles.add(0, legacyDaily);
		for (Path f : defFiles) readDefinitions(f);

		RedtaleEconomy.LOGGER.info("[tasks] 已加载 {} 个池 / {} 条任务模板", POOLS.size(), DEFS.size());
		catalogsLoadedAt = System.currentTimeMillis();
	}

	private static List<Path> scan(Path dir) {
		List<Path> out = new ArrayList<>();
		if (!Files.isDirectory(dir)) return out;
		try (var stream = Files.walk(dir)) {
			stream.filter(Files::isRegularFile)
					.filter(f -> f.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".json"))
					.filter(f -> !isDisabled(dir, f))
					.forEach(out::add);
		} catch (Exception e) {
			RedtaleEconomy.LOGGER.warn("[tasks] 扫描 {} 失败：{}", dir, e.getMessage());
		}
		out.sort(java.util.Comparator.comparingInt(f -> dir.relativize(f).getNameCount()));
		return out;
	}

	private static boolean isDisabled(Path base, Path file) {
		for (Path part : base.relativize(file)) {
			if (part.toString().startsWith("_")) return true;
		}
		return false;
	}

	private static JsonObject readJson(Path file) {
		try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			return GSON.fromJson(r, JsonObject.class);
		} catch (Exception e) {
			RedtaleEconomy.LOGGER.error("[tasks] 解析 {} 失败：{}", file.getFileName(), e.getMessage());
			return null;
		}
	}

	private static void readPools(Path file, boolean merge) {
		JsonObject root = readJson(file);
		if (root == null) return;
		JsonElement pools = root.get("pools");
		if (pools == null) return;
		if (pools.isJsonObject()) {
			for (var e : pools.getAsJsonObject().entrySet()) {
				if (!e.getValue().isJsonObject()) {
					RedtaleEconomy.LOGGER.warn("[tasks] 池 {} 不是对象，已跳过（{}）", e.getKey(),
							file.getFileName());
					continue;
				}
				appendPool(e.getKey(), e.getValue().getAsJsonObject(), merge, file);
			}
		} else if (pools.isJsonArray()) {
			// 旧版数组写法：[{id, items}]
			for (JsonElement el : pools.getAsJsonArray()) {
				if (!el.isJsonObject()) continue;
				JsonObject p = el.getAsJsonObject();
				String id = p.has("id") ? p.get("id").getAsString() : "";
				if (id.isBlank()) continue;
				appendPool(id, p, merge, file);
			}
		}
	}

	private static void appendPool(String id, JsonObject def, boolean merge, Path file) {
		List<JsonObject> items = POOLS.get(id);
		if (items != null && !merge) {
			RedtaleEconomy.LOGGER.info("[tasks] 池 {} 重复，按 outer 策略忽略 {}", id, file.getFileName());
			return;
		}
		if (items == null) {
			items = new ArrayList<>();
			POOLS.put(id, items);
		}
		String label = def.has("displayName") ? def.get("displayName").getAsString() : "";
		if (!label.isBlank()) POOL_LABELS.putIfAbsent(id, label);
		JsonElement arr = def.get("items");
		if (arr == null || !arr.isJsonArray()) return;
		for (JsonElement ie : arr.getAsJsonArray()) {
			if (!ie.isJsonObject()) continue;
			JsonObject item = ie.getAsJsonObject();
			String itemId = item.has("id") ? item.get("id").getAsString() : "";
			if (itemId.indexOf(':') < 0) {
				RedtaleEconomy.LOGGER.warn("[tasks] 池 {} 的物品 id 非法：{}", id, itemId);
				continue;
			}
			boolean dup = items.stream().anyMatch(o -> itemId.equals(o.has("id") ? o.get("id").getAsString() : ""));
			if (!dup) items.add(item);
		}
	}

	private static void readDefinitions(Path file) {
		JsonObject root = readJson(file);
		if (root == null) return;
		JsonElement el = root.get("definitions");
		if (el == null || !el.isJsonArray()) return;
		for (JsonElement e : el.getAsJsonArray()) {
			if (!e.isJsonObject()) continue;
			JsonObject def = e.getAsJsonObject();
			String id = def.has("id") ? def.get("id").getAsString() : "";
			if (!id.isBlank()) {
				boolean dup = DEFS.stream().anyMatch(o -> id.equals(o.has("id") ? o.get("id").getAsString() : ""));
				if (dup) {
					RedtaleEconomy.LOGGER.info("[tasks] 模板 {} 重复，保留先加载的（{}）", id, file.getFileName());
					continue;
				}
			}
			DEFS.add(def);
		}
	}

	private static Path dataDir;

	/** R36：开服后数据目录重绑（{@code DataRoot.onWorldReady} 调用，%world% 这时才可用）。 */
	@Override
	public void rebindDataDir() {
		resolveDir();
	}

	private static void resolveDir() {
		PathResolver.Resolved r = DataRoot.resolve();
		try {
			Path dir = r.path().resolve("tasks");
			Files.createDirectories(dir);
			dataDir = dir;
			Path pd = dir.resolve("progress");
			Files.createDirectories(pd);
			progressDir = pd;
		} catch (Exception e) {
			dataDir = null;
			progressDir = null;
			RedtaleEconomy.LOGGER.error("[tasks] 数据目录不可用：{}", e.getMessage());
		}
	}

	// ---------------------------------------------------------------- 状态

	/**
	 * 周期键：日任务 = 业务日；周任务 = {@code W<本周起始日>}（受 {@code weeklyResetDay} 控制）。
	 *
	 * <p>周键用「本周起始日的日期」而不是 ISO 周号：跨年那周 ISO 号会跳（2026-12-28 是 2027-W01），
	 * 用日期字符串既单调、又能直接看出来这一周从哪天算起。
	 */
	public static String periodKey(String period) {
		int resetHour = (int) settings().getLong("resetHour", 0);
		java.time.LocalDate day = TimeUtil.businessDate(resetHour);
		if (!PERIOD_WEEKLY.equals(period)) return day.toString();
		int startDow = (int) settings().getLong("weeklyResetDay", 1);
		startDow = Math.max(1, Math.min(7, startDow));
		int back = (day.getDayOfWeek().getValue() - startDow + 7) % 7;
		return "W" + day.minusDays(back);
	}

	/** 每日任务（兼容老调用点）。 */
	public static Daily load(UUID uuid) {
		return load(uuid, PERIOD_DAILY);
	}

	/** T06：周任务（数据、进度、刷新次数与日任务完全分开存）。 */
	public static Daily loadWeekly(UUID uuid) {
		return load(uuid, PERIOD_WEEKLY);
	}

	public static Daily load(UUID uuid, String period) {
		String p = PERIOD_WEEKLY.equals(period) ? PERIOD_WEEKLY : PERIOD_DAILY;
		String key = periodKey(p);
		String ckey = ck(uuid, p);
		// T05：挖矿/击杀是高频事件，每条都读盘会把磁盘打满 ⇒ 任务驻留内存
		Daily cached = CACHE.get(ckey);
		if (cached != null && key.equals(cached.date)) return cached;
		Path f = file(uuid, p);
		if (f != null && Files.isRegularFile(f)) {
			try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
				Daily d = GSON.fromJson(r, Daily.class);
				if (d != null && key.equals(d.date)) {
					d.period = p;
					for (Task t : d.tasks) {
						// 旧存档没存 label：按池回填，界面才不会只剩 "#0"
						if (t.label == null || t.label.isBlank()) t.label = labelOf(t.pool);
						if (t.period == null || t.period.isBlank()) t.period = p;
					}
					CACHE.put(ckey, d);
					return d;
				}
			} catch (Exception e) {
				RedtaleEconomy.LOGGER.error("[tasks] 读取 {} 失败：{}", uuid, e.getMessage());
			}
		}
		Daily d = generate(uuid, p, key);
		save(uuid, d);
		return d;
	}

	public static void save(UUID uuid, Daily d) {
		if (d == null) return;
		String p = d.period == null || d.period.isBlank() ? PERIOD_DAILY : d.period;
		CACHE.put(ck(uuid, p), d);
		Path f = file(uuid, p);
		if (f == null) return;
		try {
			Files.createDirectories(f.getParent());
		} catch (IOException e) {
			RedtaleEconomy.LOGGER.error("[tasks] 建数据目录失败：{}", e.getMessage());
			return;
		}
		Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
		try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
			w.write(GSON.toJson(d));
		} catch (IOException e) {
			RedtaleEconomy.LOGGER.error("[tasks] 写入 {} 失败：{}", uuid, e.getMessage());
			return;
		}
		try {
			Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			try {
				Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
			} catch (IOException e2) {
				RedtaleEconomy.LOGGER.error("[tasks] 落盘 {} 失败：{}", uuid, e2.getMessage());
			}
		}
	}

	/** 日任务落在 {@code tasks/}，周任务落在 {@code tasks/weekly/}（互不覆盖，回滚只删一个目录）。 */
	private static Path file(UUID uuid, String period) {
		if (dataDir == null) return null;
		return PERIOD_WEEKLY.equals(period)
				? dataDir.resolve("weekly").resolve(uuid.toString() + ".json")
				: dataDir.resolve(uuid.toString() + ".json");
	}

	// ---------------------------------------------------------------- 生成

	private static Daily generate(UUID uuid, String period, String key) {
		String p = PERIOD_WEEKLY.equals(period) ? PERIOD_WEEKLY : PERIOD_DAILY;
		Daily d = new Daily();
		d.date = key;
		d.period = p;
		d.refreshesLeft = PERIOD_DAILY.equals(p) ? (int) settings().getLong("refreshPerDay", 3) : 0;

		// T05：换批 / 换天 ⇒ 事件进度归零（进度按周期键作废，绝不继承上一周期的数字）
		String ckey = ck(uuid, p);
		Progress fresh = new Progress();
		fresh.date = key;
		PROGRESS.put(ckey, fresh);
		DIRTY.remove(ckey);

		List<JsonObject> defs = new ArrayList<>(definitionsFor(p));
		int slots = (int) settings().getLong(PERIOD_DAILY.equals(p) ? "tasksPerDay" : "tasksPerWeek", 3);
		long defaultReward = settings().getPathLong("defaultReward.money", 100);
		Random rnd = new Random(uuid.getMostSignificantBits() ^ key.hashCode());
		int base = PERIOD_WEEKLY.equals(p) ? WEEKLY_SLOT_BASE : 0;

		// 同一批里**不重复模板**：以前是每次从池里随机抽一条（有放回），
		// 抽到同一条就会给玩家两条一模一样的任务（周任务只有 2 条模板时实际每次都撞）。
		// 现在先洗牌、逐个尝试，坏模板（池空/事件缺 target）跳过时**不占槽位**，
		// 槽位仍然从 base 连续编号 —— 否则会出现「#100 不存在、只有 #101/#102」的空洞。
		Collections.shuffle(defs, rnd);
		int want = Math.min(slots, defs.size());
		if (slots > defs.size()) {
			RedtaleEconomy.LOGGER.warn("[tasks] {} 模板不足：需要 {} 条{}任务，只有 {} 条模板 ⇒ 只生成 {} 条",
					uuid, slots, PERIOD_WEEKLY.equals(p) ? "周" : "日", defs.size(), want);
		}
		for (int i = 0; i < defs.size() && d.tasks.size() < want; i++) {
			JsonObject def = defs.get(i);
			int slot = base + d.tasks.size();
			Task t = def.has("event") ? buildEvent(def, slot) : build(def, slot, rnd);
			if (t == null) continue;
			t.period = p;
			t.reward = rewardOf(def, defaultReward);
			// 周任务的奖励按「周奖励倍率」放大（配置改了下周生效，已生成的实例不回溯）
			if (PERIOD_WEEKLY.equals(p)) t.reward = (long) Math.floor(t.reward * weeklyRewardScale());
			d.tasks.add(t);
		}
		if (d.tasks.isEmpty() && slots > 0) {
			RedtaleEconomy.LOGGER.warn("[tasks] {} 未生成任何{}任务（池为空或配置不足）", uuid,
					PERIOD_WEEKLY.equals(p) ? "周" : "日");
		}
		return d;
	}

	private static double weeklyRewardScale() {
		double v = settings().getPathLong("weeklyRewardScale", 100) / 100.0;
		return v <= 0 ? 1.0 : v;
	}

	/** 按周期取定义：定义了 {@code period} 的只进对应周期，没写的默认归日任务。 */
	private static List<JsonObject> definitionsFor(String period) {
		List<JsonObject> out = new ArrayList<>();
		for (JsonObject def : DEFS) {
			if (periodOf(def).equals(period)) out.add(def);
		}
		return out;
	}

	public static String periodOf(JsonObject def) {
		if (def != null && def.has("period") && def.get("period").isJsonPrimitive()) {
			String v = def.get("period").getAsString();
			if (PERIOD_WEEKLY.equalsIgnoreCase(v)) return PERIOD_WEEKLY;
		}
		return PERIOD_DAILY;
	}

	private static List<JsonObject> definitions() {
		return new ArrayList<>(DEFS);
	}

	/** 池显示名：池配置写了 {@code displayName} 就用它，否则退回池 id（不编造中文名）。 */
	public static String labelOf(String poolId) {
		if (poolId == null || poolId.isBlank()) return "";
		String v = POOL_LABELS.get(poolId);
		return v == null || v.isBlank() ? poolId : v;
	}

	/** 按池 id 取物品表；缺池 / 空池返回空列表。 */
	private static List<JsonObject> poolItems(String poolId) {
		List<JsonObject> items = POOLS.get(poolId);
		return items == null ? new ArrayList<>() : new ArrayList<>(items);
	}

	/** 模板奖励：优先 {@code reward.money}（新格式），再退 {@code rewardMoney}（旧），最后用默认值。 */
	private static long rewardOf(JsonObject def, long fallback) {
		JsonElement reward = def.get("reward");
		if (reward != null && reward.isJsonObject()) {
			JsonElement money = reward.getAsJsonObject().get("money");
			if (money != null && money.isJsonPrimitive()) return Math.max(0, money.getAsLong());
		}
		JsonElement legacy = def.get("rewardMoney");
		if (legacy != null && legacy.isJsonPrimitive() && legacy.getAsLong() >= 0) {
			return legacy.getAsLong();
		}
		return fallback;
	}

	/**
	 * T05：事件型任务（不计物品，只记事件次数）。
	 *
	 * <p>定义格式：
	 * <pre>{@code
	 * { "label": "清剿僵尸", "event": { "type": "kill", "target": "minecraft:zombie", "amount": 10 },
	 *   "reward": { "money": 200 } }
	 * }</pre>
	 * {@code type} 支持 {@code kill} / {@code mine} / {@code travel}；{@code travel} 不需要 target。
	 */
	private static Task buildEvent(JsonObject def, int slot) {
		JsonElement raw = def.get("event");
		if (raw == null || !raw.isJsonObject()) return null;
		JsonObject ev = raw.getAsJsonObject();
		String type = ev.has("type") ? ev.get("type").getAsString().trim().toLowerCase(java.util.Locale.ROOT) : "";
		if (type.isBlank()) return null;
		if ("craft".equals(type)) {
			RedtaleEconomy.LOGGER.warn("[tasks] 事件定义用了 craft，但 Fabric 没有合成完成事件（需 mixin，归 B 队列），已跳过：{}",
					def.has("label") ? def.get("label").getAsString() : def.toString());
			return null;
		}
		if (!"kill".equals(type) && !"mine".equals(type) && !"travel".equals(type)) {
			RedtaleEconomy.LOGGER.warn("[tasks] 未知事件类型 {}，已跳过", type);
			return null;
		}
		String target = ev.has("target") ? ev.get("target").getAsString().trim() : "";
		if (!"travel".equals(type) && target.isBlank()) {
			RedtaleEconomy.LOGGER.warn("[tasks] {} 事件缺少 target，已跳过", type);
			return null;
		}
		int amount = ev.has("amount") ? Math.max(1, ev.get("amount").getAsInt()) : 1;

		Task t = new Task();
		t.slot = slot;
		t.pool = def.has("pool") ? def.get("pool").getAsString() : "";
		String label = def.has("label") ? def.get("label").getAsString().trim() : "";
		if (label.isBlank() && !t.pool.isBlank()) label = labelOf(t.pool);
		t.label = label;
		t.completion = "detect";
		t.event = type;
		t.eventTarget = target;
		t.budget = amount;
		t.appliedBudget = amount;
		// 展示用：图标取目标本身（kill 的实体 id 不是物品 ⇒ 界面只画数字，这是预期的）
		t.items.add(new ItemCount(target.isBlank() ? "minecraft:compass" : target, amount));
		return t;
	}

	private static Task build(JsonObject def, int slot, Random rnd) {
		String poolId = def.has("pool") ? def.get("pool").getAsString() : "";
		int budget = def.has("budget") ? def.get("budget").getAsInt()
				: (int) settings().getLong("defaultBudget", 32);
		int kinds = def.has("kinds") ? def.get("kinds").getAsInt()
				: (int) settings().getLong("defaultKinds", 1);
		boolean flex = settings().getPathBool("allowWeightFlex", true);
		int range = (int) settings().getLong("weightFlexRange", 1);
		int retries = (int) settings().getLong("maxRetries", 20);

		List<JsonObject> items = poolItems(poolId);
		if (items.isEmpty()) return null;
		kinds = Math.max(1, Math.min(kinds, items.size()));

		for (int attempt = 0; attempt <= retries; attempt++) {
			List<JsonObject> picked = new ArrayList<>(items);
			Collections.shuffle(picked, rnd);
			picked = picked.subList(0, kinds);

			int[] w = new int[kinds];
			long sumW = 0;
			for (int i = 0; i < kinds; i++) {
				w[i] = Math.max(1, picked.get(i).has("weight") ? picked.get(i).get("weight").getAsInt() : 1);
				sumW += w[i];
			}

			// 目标额由近到远：B, B-1, B+1, ...
			int limit = flex ? Math.max(0, range) : 0;
			for (int delta = 0; delta <= limit; delta++) {
				int[] ts = delta == 0 ? new int[]{budget} : new int[]{budget - delta, budget + delta};
				for (int T : ts) {
					if (T < sumW) continue;
					int[] counts = TaskMath.solve(T, w);
					if (counts == null) continue;

					Task t = new Task();
					t.slot = slot;
					t.pool = poolId;
					t.label = labelOf(poolId);
					t.budget = budget;
					t.appliedBudget = T;
					t.completion = def.has("completion") ? def.get("completion").getAsString() : "submit";
					for (int i = 0; i < kinds; i++) {
						t.items.add(new ItemCount(picked.get(i).get("id").getAsString(), counts[i]));
					}
					return t;
				}
			}
		}


		// 真无解：按权重比例粗分，实际预算可能偏离，明确告警
		RedtaleEconomy.LOGGER.warn("[tasks] 池 {} 无法凑出 budget={}（kinds={}），改用近似分配", poolId, budget, kinds);
		List<JsonObject> picked = new ArrayList<>(items);
		Collections.shuffle(picked, rnd);
		picked = picked.subList(0, kinds);
		long sumW = 0;
		for (JsonObject o : picked) sumW += Math.max(1, o.has("weight") ? o.get("weight").getAsInt() : 1);
		Task t = new Task();
		t.slot = slot;
		t.pool = poolId;
		t.label = labelOf(poolId);
		t.budget = budget;
		t.completion = def.has("completion") ? def.get("completion").getAsString() : "submit";
		long applied = 0;
		for (JsonObject o : picked) {
			int w = Math.max(1, o.has("weight") ? o.get("weight").getAsInt() : 1);
			int c = Math.max(1, (int) Math.round((double) budget * w / sumW / w));
			t.items.add(new ItemCount(o.get("id").getAsString(), c));
			applied += (long) c * w;
		}
		t.appliedBudget = applied;
		return t;
	}

	// ---------------------------------------------------------------- 刷新 / 领取

	public enum Action { OK, NO_REFRESH }

	public static Action refresh(UUID uuid) {
		Daily d = load(uuid);
		if (d.refreshesLeft <= 0) return Action.NO_REFRESH;
		d.refreshesLeft--;
		String date = d.date;
		Daily fresh = generate(uuid, PERIOD_DAILY, date);
		fresh.refreshesLeft = d.refreshesLeft;
		save(uuid, fresh);
		return Action.OK;
	}

	/** R29：用刷新券刷新一次，不消耗每日次数。 */
	public static Action refreshByTicket(UUID uuid) {
		Daily d = load(uuid);
		String date = d.date;
		Daily fresh = generate(uuid, PERIOD_DAILY, date);
		fresh.refreshesLeft = d.refreshesLeft;
		save(uuid, fresh);
		return Action.OK;
	}

	/** 刷新券物品 id；未配置返回空串。 */
	public static String refreshTicketItem() {
		return settings().getPathString("refreshTicket.item", "redtale_economy:refresh_ticket").trim();
	}

	/** 背包没券时是否去仓库找。 */
	public static boolean refreshTicketFromWarehouse() {
		return settings().getPathBool("refreshTicket.fromWarehouse", true);
	}

	/** 周期 id 由 slot 区间决定（{@code >=100} 是周任务）。命令与界面都只认 slot。 */
	public static String periodOfSlot(int slot) {
		return slot >= WEEKLY_SLOT_BASE ? PERIOD_WEEKLY : PERIOD_DAILY;
	}

	public static Task task(UUID uuid, int slot) {
		Daily d = load(uuid, periodOfSlot(slot));
		return d.tasks.stream().filter(x -> x.slot == slot).findFirst().orElse(null);
	}

	/** 标记完成 + 发奖 + 落盘；返回奖励，任务不存在或已领返回 -1。 */
	// Q3：领取是「读-改-写」，必须串行化，否则同一任务可能被并发领两次（GUI 连点 / FTBQ 自动领）
	public static synchronized long claimAndSave(UUID uuid, int slot) {
		Daily d = load(uuid, periodOfSlot(slot));
		Task t = d.tasks.stream().filter(x -> x.slot == slot).findFirst().orElse(null);
		if (t == null || t.claimed) return -1L;
		t.claimed = true;
		t.claimedAt = TimeUtil.nowIso();
		save(uuid, d);
		// T05：领取时把进度落盘，避免刚领完就崩服导致「进度回退成未完成」
		flushOne(uuid);
		BalanceService.add(uuid, t.reward, com.akira.redtale.economy.storage.Ledger.TYPE_TASK,
				"slot:" + slot + "/budget:" + t.appliedBudget);
		// T63：任务奖励属系统发放，计入支出统计（不扣资金池）
		com.akira.redtale.economy.storage.SystemAccount.spend(t.reward,
				com.akira.redtale.economy.storage.Ledger.TYPE_TASK,
				"slot:" + slot + "/budget:" + t.appliedBudget);
		return t.reward;
	}
}
