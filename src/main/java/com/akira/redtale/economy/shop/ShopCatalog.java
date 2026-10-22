package com.akira.redtale.economy.shop;

import com.akira.redtale.economy.RedtaleEconomy;
import com.akira.redtale.economy.config.ConfigManager;
import com.akira.redtale.economy.config.ShopConfig;
import com.akira.redtale.economy.net.ShopFilter;
import com.akira.redtale.economy.net.ShopProtocol;
import com.akira.redtale.economy.net.ShopSource;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 商店数据源（spec v0.8 §2.1/§2.2 定稿格式）。
 *
 * <pre>
 * shop/
 *   settings.json                 全局参数（pageSize / conflictPolicy ...）
 *   shops/**&#47;*.json              每个文件可含多个商店：{"shops": {"id": {...}}}
 * </pre>
 *
 * 规则：
 * <ul>
 *   <li>递归扫描所有层级的 {@code *.json}，{@code _} 开头的文件/目录跳过（临时禁用）</li>
 *   <li>{@code conflictPolicy=outer}：路径越浅越优先，深层同名商店忽略并 INFO</li>
 *   <li>{@code conflictPolicy=merge}：同名商店合并（同 id 商品只保留先加载的那条）</li>
 *   <li>不写死任何商品；单文件解析失败只影响该文件</li>
 * </ul>
 */
public final class ShopCatalog implements ShopSource {

	public static final String SHOPS_DIR = "shop/shops";
	private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

	/** 单个商品定义（文件侧，不可变）。 */
	/**
	 * 一件商品。{@code name} = 配置里写的显示名（可空，仅用于搜索索引；界面仍按客户端语言显示）。
	 */
	public record ItemDef(String id, String category, long buy, long sell, long stockInit,
			int dailyBuyLimit, boolean enabled, String name) {

		/** 显示名（永不为 null）。 */
		public String nameOrEmpty() {
			return name == null ? "" : name;
		}
	}

	/** 一个商店。{@code file} 用于管理命令回写。 */
	public static final class ShopDef {
		private final String id;
		private final String displayName;
		private final String icon;
		private final boolean requirePremium;
		private final Path file;
		private final Map<String, ItemDef> items = new LinkedHashMap<>();
		/** §43.2 商店声明过的分类（即使暂时没货也要出现在目录树里）。支持 `建材/木材` 路径写法。 */
		private final java.util.List<String> declared = new ArrayList<>();

		ShopDef(String id, String displayName, String icon, boolean requirePremium, Path file) {
			this.id = id;
			this.displayName = displayName;
			this.icon = icon;
			this.requirePremium = requirePremium;
			this.file = file;
		}

		public String id() {
			return id;
		}

		public String displayName() {
			return displayName;
		}

		public String icon() {
			return icon;
		}

		public boolean requirePremium() {
			return requirePremium;
		}

		public Path file() {
			return file;
		}

		public List<ItemDef> items() {
			return new ArrayList<>(items.values());
		}

		public ItemDef item(String itemId) {
			return items.get(itemId);
		}

		/** 声明过的分类路径（可能比实际有货的更多）。 */
		public java.util.List<String> declaredCategories() {
			return declared;
		}
	}

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
	private static final Map<String, ShopDef> SHOPS = new LinkedHashMap<>();
	/** U11：搜索键缓存（shopId/itemId → 索引），随 reload 一起清。 */
	private static final Map<String, ShopSearch> SEARCH = new java.util.concurrent.ConcurrentHashMap<>();
	private static volatile boolean ready;
	private static volatile long loadedAt;
	/** E1：加载时被钳制的商品数 + 首个样例（只读，供 /shop admin priceguard 展示）。 */
	private static volatile int priceViolations;
	private static volatile String priceViolationSample = "";
	/** T28：上次 reload 的规模（控制台要显示「N 店 / M 件」，别再遍历一遍 map）。 */
	private static volatile int shopCount;
	private static volatile int itemCount;

	private ShopCatalog() {
	}

	/** 供模块注册配置：settings 的 schema（shops 目录走递归扫描，不占 schema）。 */
	public static void defineConfig(ConfigManager cfg) {
		cfg.define(ShopConfig.FILE, ShopConfig.SCHEMA);
	}

	public static ShopCatalog create() {
		ShopCatalog c = new ShopCatalog();
		reload();
		return c;
	}

	public static long loadedAt() {
		return loadedAt;
	}

	// ---------------------------------------------------------------- 加载

	public static synchronized void reload() {
		SHOPS.clear();
		SEARCH.clear();
		ItemLang.clearCache();
		// T08/T09：折扣与轮换表跟着商店数据一起重载，避免「改了 discounts.json 要重启」
		ShopDiscounts.reload();
		// T09：轮换配置本身带缓存（逐商品读盘会把 io 打满）⇒ 重载时必须显式清
		ShopRotation.reload();
		ROTATION.clear();
		priceViolations = 0;
		priceViolationSample = "";
		ready = false;
		Path base = ConfigManager.get().baseDir().resolve(SHOPS_DIR);
		if (!Files.isDirectory(base)) {
			try {
				Files.createDirectories(base);
				RedtaleEconomy.LOGGER.warn("[shop] {} 不存在，已创建空目录", base);
			} catch (Exception e) {
				RedtaleEconomy.LOGGER.error("[shop] 无法创建 {}：{}", base, e.getMessage());
			}
			ready = true;
			// 空目录也是「加载完成」：规模归零，别留着上一次的数字（控制台会当真）
			shopCount = 0;
			itemCount = 0;
			loadedAt = System.currentTimeMillis();
			return;
		}

		boolean merge = "merge".equalsIgnoreCase(ShopConfig.conflictPolicy());
		List<Path> files = new ArrayList<>();
		try (Stream<Path> s = Files.walk(base)) {
			s.filter(Files::isRegularFile)
					.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".json"))
					.filter(p -> !isDisabled(base, p))
					.forEach(files::add);
		} catch (Exception e) {
			RedtaleEconomy.LOGGER.error("[shop] 扫描 {} 失败：{}", base, e.getMessage());
		}
		// 浅目录优先：outer 策略下先加载的赢
		files.sort(Comparator.comparingInt(p -> base.relativize(p).getNameCount()));

		int fileCount = 0;
		for (Path f : files) {
			if (loadFile(f, merge)) fileCount++;
		}
		int items = 0;
		for (ShopDef s : SHOPS.values()) items += s.items.size();
		shopCount = SHOPS.size();
		itemCount = items;
		ready = true;
		loadedAt = System.currentTimeMillis();
		RedtaleEconomy.LOGGER.info("[shop] 已加载 {} 个商店 / {} 件商品（{} 个文件，策略 {}）",
				SHOPS.size(), items, fileCount, merge ? "merge" : "outer");
	}

	private static boolean isDisabled(Path base, Path file) {
		Path rel = base.relativize(file);
		for (Path part : rel) {
			if (part.toString().startsWith("_")) return true;
		}
		return false;
	}

	private static boolean loadFile(Path file, boolean merge) {
		JsonObject root;
		try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			root = GSON.fromJson(r, JsonObject.class);
		} catch (Exception e) {
			RedtaleEconomy.LOGGER.error("[shop] 解析 {} 失败：{}", file.getFileName(), e.getMessage());
			return false;
		}
		if (root == null || !root.has("shops") || !root.get("shops").isJsonObject()) {
			RedtaleEconomy.LOGGER.warn("[shop] {} 缺少 shops 对象，已跳过", file.getFileName());
			return false;
		}

		for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("shops").entrySet()) {
			String id = e.getKey();
			if (!e.getValue().isJsonObject()) continue;
			JsonObject o = e.getValue().getAsJsonObject();
			ShopDef existing = SHOPS.get(id);
			if (existing != null && !merge) {
				RedtaleEconomy.LOGGER.info("[shop] 商店 {} 重复定义，按 outer 策略忽略 {}",
						id, file.getFileName());
				continue;
			}
			ShopDef def = existing != null ? existing : new ShopDef(id,
					str(o, "displayName", id),
					str(o, "icon", "minecraft:chest"),
					bool(o, "requirePremium", false),
					file);
			parseItems(def, o, id);
			parseCategories(def, o);
			SHOPS.put(id, def);
		}
		return true;
	}

	/**
	 * 读商店级 {@code categories}（§43.2）：字符串或 {@code {"id": "建材/木材"}} 都收。
	 * 这些分类即使没有商品也会出现在目录树里，服主可以先摆好目录再上架。
	 */
	private static void parseCategories(ShopDef def, JsonObject shopObj) {
		if (!shopObj.has("categories") || !shopObj.get("categories").isJsonArray()) return;
		for (JsonElement el : shopObj.getAsJsonArray("categories")) {
			String c = "";
			if (el.isJsonPrimitive()) {
				c = el.getAsString();
			} else if (el.isJsonObject()) {
				c = str(el.getAsJsonObject(), "id", "");
			}
			c = c == null ? "" : c.trim();
			if (!c.isEmpty() && !def.declared.contains(c)) def.declared.add(c);
		}
	}

	private static void parseItems(ShopDef def, JsonObject shopObj, String shopId) {		if (!shopObj.has("items") || !shopObj.get("items").isJsonArray()) return;
		for (JsonElement el : shopObj.getAsJsonArray("items")) {
			if (!el.isJsonObject()) continue;
			JsonObject it = el.getAsJsonObject();
			String itemId = str(it, "id", "");
			if (itemId.isEmpty()) continue;
			if (itemId.indexOf(':') < 0) {
				RedtaleEconomy.LOGGER.warn("[shop] {} 的商品 id 缺命名空间，已跳过：{}", shopId, itemId);
				continue;
			}
			if (def.items.containsKey(itemId)) {
				RedtaleEconomy.LOGGER.info("[shop] {}/{} 重复商品，保留先加载的", shopId, itemId);
				continue;
			}
			def.items.put(itemId, new ItemDef(
					itemId,
					str(it, "category", ""),
					num(it, "buy", 0),
					num(it, "sell", 0),
					num(it, "stock", -1),
					(int) num(it, "dailyBuyLimit", -1),
					bool(it, "enabled", true),
					// U11：可选显示名，两种写法都收（SDM 迁移与手写都可能用），只进搜索索引
					str(it, it.has("name") ? "name" : "displayName", "")));
		}
		// E1：历史文件里可能已经写了 sell>=buy —— 不拒绝加载（会导致商品消失），
		// 只在内存里钳制并汇总一条日志，便于运营按 id 去改文件。
		int violations = 0;
		String sample = "";
		for (ItemDef it : def.items.values()) {
			if (!it.enabled()) continue;
			if (PricePolicy.check(it.buy(), it.sell()) != null) {
				if (violations == 0) sample = it.id() + "(buy=" + it.buy() + ",sell=" + it.sell() + ")";
				violations++;
			}
		}
		if (violations > 0) {
			priceViolations += violations;
			if (priceViolationSample.isEmpty()) priceViolationSample = shopId + "/" + sample;
			RedtaleEconomy.LOGGER.warn("[shop] {} 有 {} 件商品卖价不低于买价，已在内存中钳制（例：{}），"
					+ "建议改文件；关闭校验见 shop/settings.json limits.enforceSellBelowBuy",
					shopId, violations, sample);
		}
	}

	/** E1：本次加载被钳制的商品数（文件里 sell ≥ 允许上限）。0 = 干净。 */
	public static int priceViolations() {
		return priceViolations;
	}

	/** T28：上次 reload 加载到的商店数（控制台概览用，O(1)）。 */
	public static int shopCount() {
		return shopCount;
	}

	/** T28：上次 reload 加载到的商品总数（含被钳制/隐藏的，与日志里的数字同一口径）。 */
	public static int itemCount() {
		return itemCount;
	}

	/** E1：首个违规样例 {@code shopId/itemId(buy=..,sell=..)}；无违规时为空串。 */
	public static String priceViolationSample() {
		return priceViolationSample;
	}

	private static String str(JsonObject o, String k, String def) {
		JsonElement e = o.get(k);
		return e != null && e.isJsonPrimitive() ? e.getAsString() : def;
	}

	private static long num(JsonObject o, String k, long def) {
		JsonElement e = o.get(k);
		return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber() ? e.getAsLong() : def;
	}

	private static boolean bool(JsonObject o, String k, boolean def) {
		JsonElement e = o.get(k);
		return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean() ? e.getAsBoolean() : def;
	}

	// ---------------------------------------------------------------- 写回（管理命令）

	/**
	 * 改价 / 上下架 / 补货：直接改商品文件并原子写，然后重载目录。
	 *
	 * @param buy     {@code null}=不改
	 * @param sell    {@code null}=不改
	 * @param stock   {@code null}=不改（同时清掉库存覆盖值）
	 * @param enabled {@code null}=不改
	 * @return {@code null}=成功；否则为拒绝原因（E1 价格护栏 / 文件错误），可直接展示给管理员
	 */
	public static synchronized String updateItem(String shopId, String itemId, Long buy, Long sell,
			Long stock, Boolean enabled) {
		ShopDef def = SHOPS.get(shopId);
		if (def == null || def.file() == null) return "商店不存在或没有归属文件";
		Path file = def.file();
		JsonObject root;
		try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			root = GSON.fromJson(r, JsonObject.class);
		} catch (Exception e) {
			RedtaleEconomy.LOGGER.error("[shop] 回写前读取 {} 失败：{}", file.getFileName(), e.getMessage());
			return "读取 " + file.getFileName() + " 失败";
		}
		if (root == null || !root.has("shops")) return "商店文件结构异常";
		JsonElement shopEl = root.getAsJsonObject("shops").get(shopId);
		if (shopEl == null || !shopEl.isJsonObject()) return "商店不在该文件中";
		JsonElement itemsEl = shopEl.getAsJsonObject().get("items");
		if (itemsEl == null || !itemsEl.isJsonArray()) return "商品数组缺失";

		JsonObject target = null;
		for (JsonElement el : itemsEl.getAsJsonArray()) {
			if (el.isJsonObject() && itemId.equals(str(el.getAsJsonObject(), "id", ""))) {
				target = el.getAsJsonObject();
				break;
			}
		}
		if (target == null) return "商品不在该商店中";
		String bad = checkPrice(shopId, itemId, target, buy, sell);
		if (bad != null) {
			RedtaleEconomy.LOGGER.warn("[shop] 拒绝改价 {}/{}：{}", shopId, itemId, bad);
			return bad;
		}
		if (buy != null) target.addProperty("buy", buy);
		if (sell != null) target.addProperty("sell", sell);
		if (stock != null) target.addProperty("stock", stock);
		if (enabled != null) target.addProperty("enabled", enabled);

		if (!writeWithBackup(file, root)) return "写入 " + file.getFileName() + " 失败（详见日志）";
		reload();
		if (stock != null) ShopStock.set(shopId, itemId, stock);
		ConfigManager.logEdit("shop/" + file.getFileName(), "admin update " + shopId + "/" + itemId,
				"buy=" + buy + " sell=" + sell + " stock=" + stock + " enabled=" + enabled);
		return null;
	}

	/**
	 * 价格护栏（Q4）：越界或单次变动过大直接拒绝，返回原因；null = 通过。
	 *
	 * <p>注意：当前按「单次编辑」判定，尚未累计同一天的多次改价（见 `_MOD_TASKS.md` 遗留）。
	 */
	private static String checkPrice(String shopId, String itemId, JsonObject item, Long buy, Long sell) {
		long min = ShopConfig.minPrice();
		long max = ShopConfig.maxPrice();
		int maxPct = ShopConfig.maxChangePercentPerEdit();
		int dayPct = ShopConfig.maxChangePercentPerDay();
		String r = checkOne(item, "buy", buy, min, max, maxPct);
		if (r == null) r = checkOne(item, "sell", sell, min, max, maxPct);
		if (r == null) r = checkDaily(shopId, itemId, item, "buy", buy, dayPct);
		if (r == null) r = checkDaily(shopId, itemId, item, "sell", sell, dayPct);
		// E1：按「改完之后」的这一对价格判定，只改一边也要能拦住
		if (r == null) {
			long effBuy = buy != null ? buy : num(item, "buy", 0);
			long effSell = sell != null ? sell : num(item, "sell", 0);
			r = PricePolicy.check(effBuy, effSell);
		}
		return r;
	}

	/** 当日基准价：当日第一次改价前的价格；跨天自动重置。 */
	private static final Map<String, long[]> DAY_BASE = new java.util.concurrent.ConcurrentHashMap<>();
	private static final int DAY_BASE_DATE = 0;
	private static final int DAY_BASE_PRICE = 1;

	/** 纯函数：相对基准价的累计变动是否超限（pct<=0 或基准价<=0 一律放行）。 */
	public static boolean dailyExceeded(long base, long now, int pct) {
		if (pct <= 0 || base <= 0) return false;
		return Math.abs(now - base) * 100L > base * (long) pct;
	}

	private static String checkDaily(String shopId, String itemId, JsonObject item,
			String field, Long value, int dayPct) {
		if (dayPct <= 0 || value == null || value <= 0) return null;
		long cur = num(item, field, 0);
		if (cur <= 0) return null;
		String key = shopId + "|" + itemId + "|" + field;
		int today = (int) java.time.LocalDate.now().toEpochDay();
		long[] slot = DAY_BASE.get(key);
		if (slot == null || slot[DAY_BASE_DATE] != today) {
			slot = new long[]{today, cur};
			DAY_BASE.put(key, slot);
		}
		long base = slot[DAY_BASE_PRICE];
		if (dailyExceeded(base, value, dayPct)) {
			return field + " 相对当日基准价 " + base + " 累计变动超过 " + dayPct + "%";
		}
		return null;
	}

	private static String checkOne(JsonObject item, String field, Long value, long min, long max, int maxPct) {
		if (value == null) return null;
		if (value < 0) return field + " 不能为负";
		if (min > 0 && value != 0 && value < min) return field + "=" + value + " 低于下限 " + min;
		if (value > max) return field + "=" + value + " 超过上限 " + max;
		if (maxPct > 0) {
			long old = num(item, field, 0);
			if (old > 0 && value > 0) {
				long delta = Math.abs(value - old) * 100L;
				if (delta > old * maxPct) {
					return field + " 从 " + old + " 改到 " + value + " 超过单次上限 " + maxPct + "%";
				}
			}
		}
		return null;
	}

	// ---------------------------------------------------------------- 删除 → 回收站（R16b）

	/** 回收站目录（`_` 前缀 ⇒ 扫描时跳过，不会被当成商店加载）。 */
	public static final String TRASH_DIR = "shop/_trash";

	/**
	 * 删除商品：先把定义写进回收站，再从商品文件里移除。
	 *
	 * <p>顺序很重要 —— 回收站写失败就<b>不删</b>，避免「删了还找不回来」。
	 * 回收站文件是完整商品 JSON + 上下文（shopId/file/removedAt），手工还原时复制回
	 * 对应商店的 {@code items} 数组即可。
	 */
	public static synchronized boolean removeItem(String shopId, String itemId) {
		ShopDef def = SHOPS.get(shopId);
		if (def == null || def.file() == null) return false;
		Path file = def.file();
		JsonObject root;
		try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			root = GSON.fromJson(r, JsonObject.class);
		} catch (Exception e) {
			RedtaleEconomy.LOGGER.error("[shop] 删除前读取 {} 失败：{}", file.getFileName(), e.getMessage());
			return false;
		}
		if (root == null || !root.has("shops")) return false;
		JsonElement shopEl = root.getAsJsonObject("shops").get(shopId);
		if (shopEl == null || !shopEl.isJsonObject()) return false;
		JsonElement itemsEl = shopEl.getAsJsonObject().get("items");
		if (itemsEl == null || !itemsEl.isJsonArray()) return false;

		JsonArray items = itemsEl.getAsJsonArray();
		JsonObject target = null;
		int index = -1;
		for (int i = 0; i < items.size(); i++) {
			JsonElement el = items.get(i);
			if (el.isJsonObject() && itemId.equals(str(el.getAsJsonObject(), "id", ""))) {
				target = el.getAsJsonObject();
				index = i;
				break;
			}
		}
		if (target == null) return false;

		if (!writeTrash("item", shopId + "-" + itemId.replace(':', '_'), shopId, file, target)) {
			return false;
		}
		items.remove(index);
		if (!writeWithBackup(file, root)) return false;
		reload();
		ShopStock.forget(shopId, itemId);
		ConfigManager.logEdit("shop/" + file.getFileName(), "admin remove " + shopId + "/" + itemId,
				"moved to " + TRASH_DIR);
		return true;
	}

	/** 批量删除结果：{@code removed} 实际删掉数，{@code missing} 没找到的 id，{@code trash} 回收站文件。 */
	public record BatchRemoveReport(int removed, List<String> missing, Path trash) {
	}

	/** 批量上架结果：{@code added} 新增数，{@code existing} 已存在跳过，{@code rejected} 被价格护栏等拒绝。 */
	public record BatchAddReport(int added, List<String> existing, List<String> rejected) {
	}

	/**
	 * T11：批量删除商品 —— 一次读盘、一次写盘、一次 {@code reload()}。
	 *
	 * <p>与 {@link #removeItem} 的差别只有「批」：
	 * <ul>
	 *   <li>所有被删商品的完整定义写进<b>同一个</b>回收站文件（{@code items-…-<shop>-batch.json}），
	 *       手工还原时整批复制回去即可</li>
	 *   <li>某个 id 不存在只记进 {@code missing}，不中断整批</li>
	 *   <li>回收站写失败 ⇒ 整批一个都不删（同单件语义：先能还原，再动数据）</li>
	 * </ul>
	 */
	public static synchronized BatchRemoveReport removeItems(String shopId, Collection<String> ids) {
		LinkedHashSet<String> want = new LinkedHashSet<>();
		for (String id : ids) {
			if (id != null && !id.isBlank()) want.add(id.trim());
		}
		List<String> missing = new ArrayList<>();
		if (want.isEmpty()) return new BatchRemoveReport(0, missing, null);
		ShopDef def = SHOPS.get(shopId);
		if (def == null || def.file() == null) {
			missing.addAll(want);
			return new BatchRemoveReport(0, missing, null);
		}
		Path file = def.file();
		JsonObject root;
		try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			root = GSON.fromJson(r, JsonObject.class);
		} catch (Exception e) {
			RedtaleEconomy.LOGGER.error("[shop] 批量删除前读取 {} 失败：{}", file.getFileName(), e.getMessage());
			missing.addAll(want);
			return new BatchRemoveReport(0, missing, null);
		}
		if (root == null || !root.has("shops")) {
			missing.addAll(want);
			return new BatchRemoveReport(0, missing, null);
		}
		JsonElement shopEl = root.getAsJsonObject("shops").get(shopId);
		if (shopEl == null || !shopEl.isJsonObject()) {
			missing.addAll(want);
			return new BatchRemoveReport(0, missing, null);
		}
		JsonElement itemsEl = shopEl.getAsJsonObject().get("items");
		if (itemsEl == null || !itemsEl.isJsonArray()) {
			missing.addAll(want);
			return new BatchRemoveReport(0, missing, null);
		}
		JsonArray items = itemsEl.getAsJsonArray();
		JsonArray removedDefs = new JsonArray();
		LinkedHashSet<String> found = new LinkedHashSet<>();
		// 收集定义（先收集、后按倒序删，避免下标漂移）
		for (JsonElement el : items) {
			if (!el.isJsonObject()) continue;
			JsonObject o = el.getAsJsonObject();
			String id = str(o, "id", "");
			if (id.isEmpty() || !want.contains(id)) continue;
			if (found.add(id)) removedDefs.add(o.deepCopy());
		}
		for (String w : want) {
			if (!found.contains(w)) missing.add(w);
		}
		if (removedDefs.size() == 0) {
			return new BatchRemoveReport(0, missing, null);
		}
		JsonObject bundle = new JsonObject();
		bundle.addProperty("shopId", shopId);
		bundle.addProperty("removedAt", LocalDateTime.now().withNano(0).toString());
		bundle.addProperty("count", removedDefs.size());
		bundle.addProperty("restore", "把 items 数组整段复制回该商店的 items 即可");
		bundle.add("items", removedDefs);
		Path trash = trashPath("items", shopId + "-batch");
		if (!writeTrashFile(trash, shopId, file, bundle)) return new BatchRemoveReport(0, missing, null);
		for (int i = items.size() - 1; i >= 0; i--) {
			JsonElement el = items.get(i);
			if (el.isJsonObject() && found.contains(str(el.getAsJsonObject(), "id", ""))) items.remove(i);
		}
		if (!writeWithBackup(file, root)) return new BatchRemoveReport(0, missing, null);
		reload();
		for (String id : found) ShopStock.forget(shopId, id);
		int n = removedDefs.size();
		ConfigManager.logEdit("shop/" + file.getFileName(), "admin batch remove " + shopId,
				"n=" + n + " missing=" + missing.size() + " → " + TRASH_DIR);
		return new BatchRemoveReport(n, missing, trash);
	}

	/**
	 * T11：批量上架（一次读盘、一次写盘、一次 reload）。
	 *
	 * <p>已存在的商品<b>保持原样</b>（不覆盖价格/库存），只把缺失的追加进来 ——
	 * 这样「把背包整包上架」可以反复跑，不会把手工调过的价格冲回输入框里的值。
	 *
	 * @return 结果；{@code rejected} 非空说明该 id 被价格护栏或 id 格式拒绝（其余照常上架）
	 */
	public static synchronized BatchAddReport addItems(String shopId, Collection<String> ids, long buy, long sell) {
		List<String> existing = new ArrayList<>();
		List<String> rejected = new ArrayList<>();
		LinkedHashSet<String> want = new LinkedHashSet<>();
		for (String id : ids) {
			if (id == null || id.isBlank()) continue;
			String t = id.trim();
			if (t.indexOf(':') < 0) {
				rejected.add(t + "（缺命名空间）");
				continue;
			}
			// ★注册表校验★ 单件上架走 Brigadier 的 ItemIdArgument，天然只能是真物品；
			// 批量走 greedyString ⇒ 不做这一层就会把 "devprobe:__none__" 这种 id 静默写进商店，
			// 之后 GUI/图标解析全是空栈（2026-10-21 截图轮实测写入 testshop 并需手工还原）。
			try {
				if (!net.minecraft.registry.Registries.ITEM.containsId(new net.minecraft.util.Identifier(t))) {
					rejected.add(t + "（不是已注册物品）");
					continue;
				}
			} catch (Exception bad) {
				rejected.add(t + "（非法 id）");
				continue;
			}
			want.add(t);
		}
		if (want.isEmpty()) return new BatchAddReport(0, existing, rejected);
		String priceError = PricePolicy.check(buy, sell);
		if (priceError != null) {
			rejected.add(priceError);
			return new BatchAddReport(0, existing, rejected);
		}
		ShopDef def = SHOPS.get(shopId);
		if (def == null || def.file() == null) {
			rejected.add("商店不存在：" + shopId);
			return new BatchAddReport(0, existing, rejected);
		}
		Path file = def.file();
		JsonObject root;
		try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			root = GSON.fromJson(r, JsonObject.class);
		} catch (Exception e) {
			RedtaleEconomy.LOGGER.error("[shop] 批量上架前读取 {} 失败：{}", file.getFileName(), e.getMessage());
			rejected.add("读取商店文件失败");
			return new BatchAddReport(0, existing, rejected);
		}
		if (root == null || !root.has("shops")) {
			rejected.add("商店文件结构异常");
			return new BatchAddReport(0, existing, rejected);
		}
		JsonElement shopEl = root.getAsJsonObject("shops").get(shopId);
		if (shopEl == null || !shopEl.isJsonObject()) {
			rejected.add("商店不在该文件中");
			return new BatchAddReport(0, existing, rejected);
		}
		JsonObject shop = shopEl.getAsJsonObject();
		JsonElement itemsEl = shop.get("items");
		JsonArray items;
		if (itemsEl != null && itemsEl.isJsonArray()) {
			items = itemsEl.getAsJsonArray();
		} else {
			items = new JsonArray();
			shop.add("items", items);
		}
		LinkedHashSet<String> have = new LinkedHashSet<>();
		for (JsonElement el : items) {
			if (el.isJsonObject()) have.add(str(el.getAsJsonObject(), "id", ""));
		}
		int added = 0;
		for (String id : want) {
			if (have.contains(id)) {
				existing.add(id);
				continue;
			}
			JsonObject it = new JsonObject();
			it.addProperty("id", id);
			it.addProperty("category", "");
			it.addProperty("buy", buy);
			it.addProperty("sell", sell);
			it.addProperty("stock", -1);
			it.addProperty("dailyBuyLimit", -1);
			items.add(it);
			have.add(id);
			added++;
		}
		if (added == 0) return new BatchAddReport(0, existing, rejected);
		if (!writeWithBackup(file, root)) {
			rejected.add("写入商店文件失败");
			return new BatchAddReport(0, existing, rejected);
		}
		reload();
		ConfigManager.logEdit("shop/" + file.getFileName(), "admin batch add " + shopId,
				"n=" + added + " buy=" + buy + " sell=" + sell + " skipped=" + existing.size());
		return new BatchAddReport(added, existing, rejected);
	}

	/** 把 {@code body} 写到指定回收站路径（批量版；失败返回 false 让调用方放弃写入）。 */
	private static boolean writeTrashFile(Path trash, String shopId, Path source, JsonObject body) {
		try {
			Files.createDirectories(trash.getParent());
			JsonObject wrapper = new JsonObject();
			wrapper.addProperty("shopId", shopId);
			wrapper.addProperty("source", source.toString());
			wrapper.addProperty("removedAt", LocalDateTime.now().withNano(0).toString());
			wrapper.add("body", body);
			try (Writer w = Files.newBufferedWriter(trash, StandardCharsets.UTF_8)) {
				GSON.toJson(wrapper, w);
			}
			return true;
		} catch (Exception e) {
			RedtaleEconomy.LOGGER.error("[shop] 写回收站 {} 失败：{}", trash.getFileName(), e.getMessage());
			return false;
		}
	}

	/** 删除整家商店：定义进回收站；文件里没有别的商店了就把文件本身也移进回收站。 */
	public static synchronized boolean removeShop(String shopId) {
		ShopDef def = SHOPS.get(shopId);
		if (def == null || def.file() == null) return false;
		Path file = def.file();
		JsonObject root;
		try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			root = GSON.fromJson(r, JsonObject.class);
		} catch (Exception e) {
			RedtaleEconomy.LOGGER.error("[shop] 删除商店前读取 {} 失败：{}", file.getFileName(), e.getMessage());
			return false;
		}
		if (root == null || !root.has("shops")) return false;
		JsonObject shops = root.getAsJsonObject("shops");
		JsonElement shopEl = shops.get(shopId);
		if (shopEl == null || !shopEl.isJsonObject()) return false;

		if (!writeTrash("shop", shopId, shopId, file, shopEl.getAsJsonObject())) return false;
		shops.remove(shopId);
		if (shops.size() == 0) {
			// 整个文件都空了：文件本身也搬去回收站，避免留下空壳
			Path trashFile = trashPath("file", file.getFileName().toString().replace(".json", ""));
			try {
				Files.createDirectories(trashFile.getParent());
				Files.move(file, trashFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
			} catch (Exception e) {
				RedtaleEconomy.LOGGER.error("[shop] 移动空商店文件到回收站失败：{}", e.getMessage());
				return false;
			}
		} else if (!writeWithBackup(file, root)) {
			return false;
		}
		ShopStock.forgetShop(shopId);
		reload();
		ConfigManager.logEdit("shop/" + file.getFileName(), "admin removeshop " + shopId,
				"moved to " + TRASH_DIR);
		return true;
	}

	private static Path trashPath(String kind, String name) {
		String ts = java.time.LocalDateTime.now()
				.format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
		String safe = name.replaceAll("[^A-Za-z0-9_.-]", "_");
		return ConfigManager.get().baseDir().resolve(TRASH_DIR).resolve(kind + "-" + ts + "-" + safe + ".json");
	}

	/** 写回收站文件；任何一步失败返回 false（调用方据此放弃删除）。 */
	private static boolean writeTrash(String kind, String name, String shopId, Path source, JsonObject body) {
		Path target = trashPath(kind, name);
		try {
			Files.createDirectories(target.getParent());
			JsonObject wrapper = new JsonObject();
			wrapper.addProperty("_version", 1);
			wrapper.addProperty("kind", kind);
			wrapper.addProperty("shopId", shopId);
			wrapper.addProperty("sourceFile", source.getFileName().toString());
			wrapper.addProperty("removedAt", java.time.LocalDateTime.now().toString());
			wrapper.add("definition", body);
			return writeFile(target, wrapper);
		} catch (Exception e) {
			RedtaleEconomy.LOGGER.error("[shop] 写回收站失败 {}：{}", target.getFileName(), e.getMessage());
			return false;
		}
	}

	/**
	 * 新建空商店：写到 {@code shop/shops/<id>.json}（一个文件一个商店，便于手工分担）。
	 * 已存在则返回 false，不覆盖。
	 */
	public static synchronized boolean createShop(String shopId, String displayName) {
		if (SHOPS.containsKey(shopId)) return false;
		if (shopId.contains("/") || shopId.contains("\\") || shopId.contains("..")) return false;
		Path dir = ConfigManager.get().baseDir().resolve(SHOPS_DIR);
		Path file = dir.resolve(shopId + ".json");
		if (Files.exists(file)) return false;
		JsonObject root = new JsonObject();
		root.addProperty("_version", 1);
		JsonObject shops = new JsonObject();
		JsonObject shop = new JsonObject();
		shop.addProperty("displayName", displayName == null || displayName.isBlank() ? shopId : displayName);
		shop.addProperty("icon", "minecraft:chest");
		shop.add("categories", new JsonArray());
		shop.add("items", new JsonArray());
		shops.add(shopId, shop);
		root.add("shops", shops);
		try {
			Files.createDirectories(dir);
		} catch (Exception e) {
			RedtaleEconomy.LOGGER.error("[shop] 无法创建 {}：{}", dir, e.getMessage());
			return false;
		}
		if (!writeFile(file, root)) return false;
		reload();
		ConfigManager.logEdit("shop/" + file.getFileName(), "admin addshop " + shopId, displayName);
		return true;
	}

	/**
	 * 往已有商店追加商品（默认无限库存、不限购）。
	 *
	 * @return {@code null}=成功；否则为拒绝原因（含 E1 价格护栏）
	 */
	public static synchronized String addItem(String shopId, String itemId, long buy, long sell,
			int dailyBuyLimit) {
		ShopDef def = SHOPS.get(shopId);
		if (def == null || def.file() == null) return "商店不存在";
		if (itemId.indexOf(':') < 0) return "商品 id 缺命名空间：" + itemId;
		// 与 addItems 同口径：命令行走 ItemIdArgument 时这里是冗余，但配置文件/脚本/旧数据回灌不经过解析器。
		try {
			if (!net.minecraft.registry.Registries.ITEM.containsId(new net.minecraft.util.Identifier(itemId))) {
				return "不是已注册物品：" + itemId;
			}
		} catch (Exception e) {
			return "非法 id：" + itemId;
		}
		String priceError = PricePolicy.check(buy, sell);
		if (priceError != null) {
			RedtaleEconomy.LOGGER.warn("[shop] 拒绝新增 {}/{}：{}", shopId, itemId, priceError);
			return priceError;
		}
		Path file = def.file();
		JsonObject root;
		try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			root = GSON.fromJson(r, JsonObject.class);
		} catch (Exception e) {
			RedtaleEconomy.LOGGER.error("[shop] 追加商品前读取 {} 失败：{}", file.getFileName(), e.getMessage());
			return "读取商店文件失败";
		}
		if (root == null || !root.has("shops")) return "商店文件结构异常";
		JsonElement shopEl = root.getAsJsonObject("shops").get(shopId);
		if (shopEl == null || !shopEl.isJsonObject()) return "商店不在该文件中";
		JsonObject shop = shopEl.getAsJsonObject();
		JsonElement itemsEl = shop.get("items");
		JsonArray items;
		if (itemsEl != null && itemsEl.isJsonArray()) {
			items = itemsEl.getAsJsonArray();
		} else {
			items = new JsonArray();
			shop.add("items", items);
		}
		for (JsonElement el : items) {
			if (el.isJsonObject() && itemId.equals(str(el.getAsJsonObject(), "id", ""))) {
				return "商品已存在：" + itemId;
			}
		}
		JsonObject it = new JsonObject();
		it.addProperty("id", itemId);
		it.addProperty("category", "");
		it.addProperty("buy", buy);
		it.addProperty("sell", sell);
		it.addProperty("stock", -1);
		it.addProperty("dailyBuyLimit", dailyBuyLimit);
		items.add(it);
		if (!writeWithBackup(file, root)) return "写入商店文件失败";
		reload();
		ConfigManager.logEdit("shop/" + file.getFileName(), "admin add " + shopId + "/" + itemId,
				"buy=" + buy + " sell=" + sell);
		return null;
	}

	private static boolean writeWithBackup(Path file, JsonObject root) {
		Path base = ConfigManager.get().baseDir();
		try {
			Path backup = base.resolve("backup").resolve(LocalDateTime.now().format(STAMP))
					.resolve(base.relativize(file));
			Files.createDirectories(backup.getParent());
			Files.copy(file, backup, StandardCopyOption.REPLACE_EXISTING);
		} catch (Exception e) {
			RedtaleEconomy.LOGGER.warn("[shop] 备份 {} 失败，继续写：{}", file.getFileName(), e.getMessage());
		}
		return writeFile(file, root);
	}

	private static boolean writeFile(Path file, JsonObject root) {
		Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
		try {
			try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
				GSON.toJson(root, w);
			}
			try {
				Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (Exception atomicFail) {
				Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
			}
			return true;
		} catch (Exception e) {
			RedtaleEconomy.LOGGER.error("[shop] 写入 {} 失败：{}", file.getFileName(), e.getMessage());
			try {
				Files.deleteIfExists(tmp);
			} catch (Exception ignored) {
				// tmp 留在磁盘也不影响读取
			}
			return false;
		}
	}

	// ---------------------------------------------------------------- ShopSource

	@Override
	public boolean ready() {
		return ready;
	}

	@Override
	public boolean has(String shopId) {
		return SHOPS.containsKey(shopId);
	}

	@Override
	public int count(String shopId, String query) {
		return filtered(shopId, query, ShopFilter.NONE).size();
	}

	@Override
	public List<ShopProtocol.Entry> slice(String shopId, String query, int offset, int limit) {
		return slice(shopId, query, ShopFilter.NONE, offset, limit);
	}

	// ---------------------------------------------------------------- U11b：多维筛选

	@Override
	public int count(String shopId, String query, ShopFilter filter) {
		return filtered(shopId, query, filter == null ? ShopFilter.NONE : filter).size();
	}

	@Override
	public List<ShopProtocol.Entry> slice(String shopId, String query, ShopFilter filter,
			int offset, int limit) {
		List<ShopProtocol.Entry> all = filtered(shopId, query, filter == null ? ShopFilter.NONE : filter);
		if (offset < 0 || offset >= all.size()) return List.of();
		int to = Math.min(all.size(), offset + Math.max(0, limit));
		return all.subList(offset, to);
	}

	@Override
	public List<String> mods(String shopId) {
		ShopDef def = SHOPS.get(shopId);
		if (def == null) return List.of();
		java.util.TreeSet<String> set = new java.util.TreeSet<>();
		for (ItemDef it : def.items.values()) {
			if (it.enabled()) set.add(namespaceOf(it.id()));
		}
		return new ArrayList<>(set);
	}

	@Override
	public List<String> categories(String shopId) {
		ShopDef def = SHOPS.get(shopId);
		if (def == null) return List.of();
		java.util.TreeSet<String> set = new java.util.TreeSet<>();
		for (ItemDef it : def.items.values()) {
			if (it.enabled() && !it.category().isBlank()) set.add(it.category());
		}
		// §43.2：声明过但暂时没货的分类也下发，目录树才不会因为下架而整块消失
		for (String c : def.declaredCategories()) {
			if (c != null && !c.isBlank()) set.add(c);
		}
		return new ArrayList<>(set);
	}

	private static String namespaceOf(String itemId) {
		int i = itemId.indexOf(':');
		return i < 0 ? "minecraft" : itemId.substring(0, i);
	}

	/** 搜索（空格分词 AND）+ 筛选（mod / 类型 / 价格 / 库存）叠加。 */
	private List<ShopProtocol.Entry> filtered(String shopId, String query, ShopFilter f) {
		ShopDef def = SHOPS.get(shopId);
		if (def == null) return List.of();
		ShopFilter filter = f == null ? ShopFilter.NONE : f;
		// 多关键词 AND：「木头 台阶」= 两个词都要命中（id 或 category）
		String[] tokens = query == null || query.isBlank()
				? new String[0]
				: query.trim().toLowerCase(Locale.ROOT).split("\\s+");
		String mod = filter.modOrEmpty().toLowerCase(Locale.ROOT);
		String cat = filter.categoryOrEmpty().toLowerCase(Locale.ROOT);
		List<ShopProtocol.Entry> out = new ArrayList<>();
		for (ItemDef it : def.items.values()) {
			if (!it.enabled()) continue;
			if (!mod.isEmpty() && !namespaceOf(it.id()).toLowerCase(Locale.ROOT).equals(mod)) continue;
			// U11：目录树选中某一级时要含整棵子树（木材 ⇒ 木材/台阶），分隔符整段对齐
			if (!cat.isEmpty() && !ShopTree.matches(it.category(), cat)) continue;
			long sell = sellPrice(it);
			// 价格按「能成交的价格」判断：能买看买价，只能卖看卖价
			long buy = buyPriceOf(shopId, it);
			long price = buy > 0 ? buy : sell;
			if (filter.minPrice() > 0 && price < filter.minPrice()) continue;
			if (filter.maxPrice() > 0 && price > filter.maxPrice()) continue;
			long stock = ShopStock.get(shopId, it.id(), it.stockInit());
			if (filter.inStockOnly() && stock == 0) continue;
			if (tokens.length > 0 && !search(shopId, it).matchesAll(tokens)) continue;
			out.add(new ShopProtocol.Entry(it.id(), buy, sell, stock, buy > 0, sell > 0));
		}
		return out;
	}

	/** U11：取（或建）一件商品的搜索键。显示名解析要走 jar/language，按条目缓存，reload 时清。 */
	private static ShopSearch search(String shopId, ItemDef it) {
		String key = shopId + '\u0000' + it.id();
		ShopSearch s = SEARCH.get(key);
		if (s != null) return s;
		s = ShopSearch.of(it.id(), it.category(), it.nameOrEmpty(), ItemLang.displayName(it.id()));
		SEARCH.put(key, s);
		return s;
	}

	/** 诊断（`/rt admin shop search`）：把一件商品的搜索索引打出来，排查「搜不到」。 */
	public static String describeSearch(String shopId, String itemId) {
		ShopDef def = SHOPS.get(shopId);
		if (def == null) return "商店不存在：" + shopId;
		ItemDef it = def.item(itemId);
		if (it == null) return "商品不存在：" + itemId;
		return search(shopId, it).describe();
	}

	public static List<ShopProtocol.Entry> allEntries(String shopId) {
		ShopDef def = SHOPS.get(shopId);
		if (def == null) return List.of();
		List<ShopProtocol.Entry> out = new ArrayList<>();
		for (ItemDef it : def.items.values()) {
			if (!it.enabled()) continue;
			long stock = ShopStock.get(shopId, it.id(), it.stockInit());
			long sell = sellPrice(it);
			long buy = buyPriceOf(shopId, it);
			out.add(new ShopProtocol.Entry(it.id(), buy, sell, stock,
					buy > 0, sell > 0));
		}
		return out;
	}

	public static ShopDef shop(String shopId) {
		return SHOPS.get(shopId);
	}

	/**
	 * T25 跨店搜索：一次扫全部商店，返回排序后的命中。
	 *
	 * <p>只读、不写盘；命中条目带上商店显示名与可成交价，玩家不用先猜在哪家店。
	 * 排序与去重口径在 {@link ShopCrossSearch#rank}（纯逻辑，有单测）。
	 *
	 * @param limit 返回条数上限（调用方给 8 之类的展示用值）
	 */
	public static List<ShopCrossSearch.Hit> searchAll(String query, int limit) {
		String[] tokens = ShopCrossSearch.tokens(query);
		if (tokens.length == 0 || limit <= 0) return List.of();
		List<ShopCrossSearch.Hit> hits = new ArrayList<>();
		for (String shopId : shopIds()) {
			ShopDef def = SHOPS.get(shopId);
			if (def == null) continue;
			String name = shopName(shopId);
			for (ItemDef it : def.items.values()) {
				if (!it.enabled()) continue;
				if (!ShopCrossSearch.matches(search(shopId, it), tokens)) continue;
				long stock = ShopStock.get(shopId, it.id(), it.stockInit());
				long buy = buyPriceOf(shopId, it);
				long sell = sellPrice(it);
				hits.add(new ShopCrossSearch.Hit(shopId, name, it.id(), ItemLang.displayName(it.id()),
						buy, sell, stock));
			}
		}
		return ShopCrossSearch.rank(hits, limit);
	}

	public static ItemDef item(String shopId, String itemId) {
		ShopDef def = SHOPS.get(shopId);
		return def == null ? null : def.item(itemId);
	}

	public static ShopProtocol.Entry entry(String shopId, String itemId) {
		ItemDef it = item(shopId, itemId);
		if (it == null || !it.enabled()) return null;
		long stock = ShopStock.get(shopId, itemId, it.stockInit());
		long sell = sellPrice(it);
		long buy = buyPriceOf(shopId, it);
		return new ShopProtocol.Entry(it.id(), buy, sell, stock, buy > 0, sell > 0);
	}

	/**
	 * T08：折后买入价 = 文件价 × (100 - 当前折扣%)。没有活动时就是文件价。
	 *
	 * <p>所有对外暴露价格的地方（{@link #entry}／{@link #allEntries}／{@link #slice}）都走这里，
	 * 否则会出现「界面显示打折、结算按原价扣」的双口径。
	 */
	public static long buyPriceOf(String shopId, ItemDef it) {
		if (it == null) return 0L;
		long now = System.currentTimeMillis();
		int pct = ShopDiscounts.percentFor(shopId, it.id(), now);
		// T09：每日轮换特价与 T08 折扣取「更大的那个」，不叠加（叠加会算出 1 折以下的黑洞价）
		int rot = rotationPercent(shopId, it.id(), now);
		int best = Math.max(pct, rot);
		return ShopDiscounts.priceWith(it.buy(), best);
	}

	/** 今日轮换缓存：key = shopId|dayIndex，reload 时清空。 */
	private static final Map<String, java.util.Set<String>> ROTATION = new HashMap<>();

	private static int rotationPercent(String shopId, String itemId, long nowMs) {
		if (!ShopRotation.enabled() || !ShopRotation.coversShop(shopId)) return 0;
		ShopDef def = SHOPS.get(shopId);
		if (def == null) return 0;
		long day = ShopRotation.dayIndex(nowMs, ShopRotation.refreshHour());
		String key = shopId + '|' + day;
		java.util.Set<String> picked = ROTATION.get(key);
		if (picked == null) {
			List<String> ids = new ArrayList<>();
			for (ItemDef x : def.items.values()) {
				if (x.enabled() && x.buy() > 0) ids.add(x.id());
			}
			picked = ShopRotation.pick(ids, day, ShopRotation.count(), ShopRotation.salt());
			ROTATION.put(key, picked);
			// 只留今天的：日期滚动后旧集合没有任何用处
			ROTATION.keySet().removeIf(k -> !k.equals(key) && k.startsWith(shopId + '|'));
		}
		return picked.contains(itemId) ? ShopRotation.percent() : 0;
	}

	/**
	 * 卖出价：文件里 sell>0 用它，否则按 buy × sellPriceRatio 兜底。
	 *
	 * <p>E1：两条路都过 {@link PricePolicy#clampSell}，历史文件里写错的
	 * （sell ≥ buy、ratio ≥ 1）在<b>读取时</b>就被压到合法区间，不写回文件。
	 */
	public static long sellPrice(ItemDef it) {
		if (it.sell() > 0) return PricePolicy.clampSell(it.buy(), it.sell());
		double ratio = PricePolicy.clampRatio(ShopConfig.sellPriceRatio());
		if (ratio <= 0 || it.buy() <= 0) return 0L;
		// +1e-6 抵消二进制浮点误差（如 100×0.29=28.999999999999996 会少给 1 铜），口径同 GuildModule.priceAt
		long v = (long) Math.floor(it.buy() * ratio + 1e-6);
		return PricePolicy.clampSell(it.buy(), v);
	}

	public static int dailyLimitOf(String shopId, String itemId) {
		ItemDef it = item(shopId, itemId);
		return it == null ? -1 : it.dailyBuyLimit();
	}

	public static List<String> shopIds() {
		return new ArrayList<>(SHOPS.keySet());
	}

	/** 商店显示名（U6：界面上要能看出是哪家店；没有就退回 id）。 */
	public static String shopName(String shopId) {
		ShopDef d = SHOPS.get(shopId);
		if (d == null) return shopId == null ? "" : shopId;
		String n = d.displayName();
		return n == null || n.isBlank() ? d.id() : n;
	}

	public static int pageSize() {
		return ShopConfig.pageSize();
	}
}
