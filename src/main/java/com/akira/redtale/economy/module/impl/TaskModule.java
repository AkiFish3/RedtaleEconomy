package com.akira.redtale.economy.module.impl;

import com.akira.redtale.economy.RedtaleEconomy;
import com.akira.redtale.economy.command.TaskCommands;
import com.akira.redtale.economy.config.ConfigManager;
import com.akira.redtale.economy.config.ConfigSchema;
import com.akira.redtale.economy.config.ConfigStore;
import com.akira.redtale.economy.config.StorageConfig;
import com.akira.redtale.economy.economy.BalanceService;
import com.akira.redtale.economy.module.RedtaleModule;
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
	/** 兼容旧版平铺文件（同样按新结构解析，只是名字不带目录层级）。 */
	private static final String LEGACY_POOLS = "tasks/pools.json";
	private static final String LEGACY_DAILY = "tasks/daily.json";

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

	private static final ConfigSchema POOL_ITEM = ConfigSchema.obj("池内物品")
			.field("id", ConfigSchema.string("minecraft:stone", "物品 id（必须带命名空间）"))
			.field("weight", ConfigSchema.integer(1, 1, 1000, "权重（越大需求越少，用于拆解预算）"));

	/** 池用「对象 map」而非数组：{@code {"pools":{"food":{"items":[...]}}}}。 */
	public static final ConfigSchema SETTINGS_SCHEMA = ConfigSchema.obj("每日任务")
			.field("tasksPerDay", ConfigSchema.integer(3, 1, 20, "每天任务数"))
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
		public String pool = "";
		public List<ItemCount> items = new ArrayList<>();
		public long budget;
		public long appliedBudget;
		public String completion = "submit";
		public long reward = 0;
		public boolean claimed;
		public String claimedAt = "";
	}

	public static final class Daily {
		public String date = "";
		public List<Task> tasks = new ArrayList<>();
		public int refreshesLeft;
	}

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
	}

	@Override
	public void onReload(ConfigManager cfg) {
		resolveDir();
		loadCatalogs();
	}

	@Override
	public void registerCommands(CommandDispatcher<ServerCommandSource> dispatcher) {
		TaskCommands.register(dispatcher);
	}

	/** 全局参数（tasks/settings.json）。 */
	public static ConfigStore settings() {
		return ConfigManager.get().store(SETTINGS_FILE);
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

	private static void resolveDir() {
		PathResolver.Resolved r = PathResolver.resolveDir(StorageConfig.root(), "%server%/redtale-data");
		try {
			Path dir = r.path().resolve("tasks");
			Files.createDirectories(dir);
			dataDir = dir;
		} catch (Exception e) {
			dataDir = null;
			RedtaleEconomy.LOGGER.error("[tasks] 数据目录不可用：{}", e.getMessage());
		}
	}

	// ---------------------------------------------------------------- 状态

	public static Daily load(UUID uuid) {
		String today = TimeUtil.today((int) settings().getLong("resetHour", 0));
		Path f = file(uuid);
		if (f != null && Files.isRegularFile(f)) {
			try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
				Daily d = GSON.fromJson(r, Daily.class);
				if (d != null && today.equals(d.date)) return d;
			} catch (Exception e) {
				RedtaleEconomy.LOGGER.error("[tasks] 读取 {} 失败：{}", uuid, e.getMessage());
			}
		}
		Daily d = generate(uuid, today);
		save(uuid, d);
		return d;
	}

	public static void save(UUID uuid, Daily d) {
		Path f = file(uuid);
		if (f == null) return;
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

	private static Path file(UUID uuid) {
		return dataDir == null ? null : dataDir.resolve(uuid.toString() + ".json");
	}

	// ---------------------------------------------------------------- 生成

	private static Daily generate(UUID uuid, String date) {
		Daily d = new Daily();
		d.date = date;
		d.refreshesLeft = (int) settings().getLong("refreshPerDay", 3);

		List<JsonObject> defs = definitions();
		int perDay = (int) settings().getLong("tasksPerDay", 3);
		long defaultReward = settings().getPathLong("defaultReward.money", 100);
		Random rnd = new Random(uuid.getMostSignificantBits() ^ date.hashCode());

		for (int slot = 0; slot < perDay && !defs.isEmpty(); slot++) {
			JsonObject def = defs.get(rnd.nextInt(defs.size()));
			Task t = build(def, slot, rnd);
			if (t == null) continue;
			t.reward = rewardOf(def, defaultReward);
			d.tasks.add(t);
		}
		if (d.tasks.isEmpty()) {
			RedtaleEconomy.LOGGER.warn("[tasks] {} 未生成任何任务（池为空或配置不足）", uuid);
		}
		return d;
	}

	private static List<JsonObject> definitions() {
		return new ArrayList<>(DEFS);
	}

	/** 按池 id 取物品表；缺池 / 空池返回空列表。 */
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
		Daily fresh = generate(uuid, date);
		fresh.refreshesLeft = d.refreshesLeft;
		save(uuid, fresh);
		return Action.OK;
	}

	/** R29：用刷新券刷新一次，不消耗每日次数。 */
	public static Action refreshByTicket(UUID uuid) {
		Daily d = load(uuid);
		String date = d.date;
		Daily fresh = generate(uuid, date);
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

	public static Task task(UUID uuid, int slot) {
		return load(uuid).tasks.stream().filter(x -> x.slot == slot).findFirst().orElse(null);
	}

	/** 标记完成 + 发奖 + 落盘；返回奖励，任务不存在或已领返回 -1。 */
	// Q3：领取是「读-改-写」，必须串行化，否则同一任务可能被并发领两次（GUI 连点 / FTBQ 自动领）
	public static synchronized long claimAndSave(UUID uuid, int slot) {
		Daily d = load(uuid);
		Task t = d.tasks.stream().filter(x -> x.slot == slot).findFirst().orElse(null);
		if (t == null || t.claimed) return -1L;
		t.claimed = true;
		t.claimedAt = TimeUtil.nowIso();
		save(uuid, d);
		BalanceService.add(uuid, t.reward, com.akira.redtale.economy.storage.Ledger.TYPE_TASK,
				"slot:" + slot + "/budget:" + t.appliedBudget);
		return t.reward;
	}
}
