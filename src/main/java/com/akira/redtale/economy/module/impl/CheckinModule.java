package com.akira.redtale.economy.module.impl;

import com.akira.redtale.economy.RedtaleEconomy;
import com.akira.redtale.economy.command.CheckinCommands;
import com.akira.redtale.economy.config.ConfigManager;
import com.akira.redtale.economy.config.ConfigSchema;
import com.akira.redtale.economy.config.ConfigStore;
import com.akira.redtale.economy.config.StorageConfig;
import com.akira.redtale.economy.economy.BalanceService;
import com.akira.redtale.economy.module.RedtaleModule;
import com.akira.redtale.economy.util.PathResolver;
import com.akira.redtale.economy.util.TimeUtil;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 签到模块（spec §4 + v0.2 #6：断签<b>重置</b> + 提供补签）。
 *
 * <p>奖励按「连续签到第几天」落档；{@code loop} 天后档位循环（连续天数不清零）。
 * 补签只修复连续链，不补发那天奖励 —— 避免用补签刷奖励。
 */
public final class CheckinModule implements RedtaleModule {

	/** 最近一次补签补的是哪一天（给命令回显用）。 */
	public static volatile String LAST_MAKEUP_DATE = "";

	public static final String FILE = "checkin.json";

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

	public static final ConfigSchema SCHEMA = ConfigSchema.obj("签到")
			.field("resetHour", ConfigSchema.integer(0, 0, 23, "每日重置小时（此时间之前算前一天）"))
			.field("loop", ConfigSchema.integer(7, 1, 3650, "档位循环周期（天）；连续天数本身不清零"))
			.field("breakPolicy", ConfigSchema.choice("reset", List.of("reset", "keep"),
					"断签处理：reset=回到第 1 天；keep=保留连续天数（会让人断签后仍拿高档位）"))
			.field("tiers", ConfigSchema.rawList(defaultTiers(),
					"奖励档位：[{days:[1,2,3], reward:{money:100, items:[{id,count}]}}]；"
							+ "兼容旧写法 {from,to,money}。区间不重叠，越靠前优先"))
			.field("makeup", ConfigSchema.obj("补签")
					.field("enabled", ConfigSchema.bool(true, "是否允许补签"))
					.field("maxPerMonth", ConfigSchema.integer(3, 0, 31, "每人每月最多补签几次"))
					.field("ticketItem", ConfigSchema.string("",
							"补签消耗的物品 id（如 redtale_economy:makeup_ticket）；空=改用铜币"))
					.field("price", ConfigSchema.integer(500, 0, 1_000_000_000_000L,
							"ticketItem 为空时每次补签消耗的铜币（0=免费）"))
					.field("allowBackfillDays", ConfigSchema.integer(7, 1, 30,
							"最多补回几天前（1=只补昨天）")))
			.field("stateCacheSize", ConfigSchema.integer(1024, 16, 65536, "内存缓存的签到状态条数"));

	private static JsonArray defaultTiers() {
		JsonArray a = new JsonArray();
		a.add(tier(new int[]{1, 2, 3}, 100));
		a.add(tier(new int[]{4, 5, 6}, 200));
		a.add(tier(new int[]{7}, 500));
		return a;
	}

	private static JsonObject tier(int[] days, long money) {
		JsonObject o = new JsonObject();
		JsonArray ds = new JsonArray();
		for (int d : days) ds.add(d);
		o.add("days", ds);
		JsonObject reward = new JsonObject();
		reward.addProperty("money", money);
		o.add("reward", reward);
		return o;
	}

	// ---------------------------------------------------------------- 状态

	public static final class State {
		public String lastDate = "";
		public int streak;
		public String makeupDate = "";
		public int makeupUsed;
		public String makeupMonth = "";
		public int makeupUsedMonth;
		public int totalDays;

		public String toJson() {
			JsonObject o = new JsonObject();
			o.addProperty("_version", 1);
			o.addProperty("lastDate", lastDate);
			o.addProperty("streak", streak);
			o.addProperty("makeupDate", makeupDate);
			o.addProperty("makeupUsed", makeupUsed);
			o.addProperty("makeupMonth", makeupMonth);
			o.addProperty("makeupUsedMonth", makeupUsedMonth);
			o.addProperty("totalDays", totalDays);
			return GSON.toJson(o);
		}

		public static State from(String json) {
			State s = new State();
			try {
				JsonObject o = GSON.fromJson(json, JsonObject.class);
				if (o == null) return s;
				s.lastDate = o.has("lastDate") ? o.get("lastDate").getAsString() : "";
				s.streak = o.has("streak") ? o.get("streak").getAsInt() : 0;
				s.makeupDate = o.has("makeupDate") ? o.get("makeupDate").getAsString() : "";
				s.makeupUsed = o.has("makeupUsed") ? o.get("makeupUsed").getAsInt() : 0;
				s.makeupMonth = o.has("makeupMonth") ? o.get("makeupMonth").getAsString() : "";
				s.makeupUsedMonth = o.has("makeupUsedMonth") ? o.get("makeupUsedMonth").getAsInt() : 0;
				s.totalDays = o.has("totalDays") ? o.get("totalDays").getAsInt() : 0;
			} catch (Exception e) {
				RedtaleEconomy.LOGGER.warn("[checkin] 状态解析失败，按空状态处理：{}", e.getMessage());
			}
			return s;
		}
	}

	private static final Map<String, State> CACHE = new ConcurrentHashMap<>();
	private static Path stateDir;

	public static ConfigStore store() {
		return ConfigManager.get().store(FILE);
	}

	public static int resetHour() {
		return (int) store().getLong("resetHour", 0);
	}

	public static int loopDays() {
		return (int) store().getLong("loop", 7);
	}

	public static String breakPolicy() {
		return store().getString("breakPolicy", "reset");
	}

	// ---------------------------------------------------------------- 模块

	@Override
	public String id() {
		return "checkin";
	}

	@Override
	public void defineConfig(ConfigManager cfg) {
		cfg.define(FILE, SCHEMA);
	}

	@Override
	public void onLoad(ConfigManager cfg) {
		resolveDir();
	}

	@Override
	public void onReload(ConfigManager cfg) {
		resolveDir();
		CACHE.clear();
	}

	@Override
	public void registerCommands(CommandDispatcher<ServerCommandSource> dispatcher) {
		CheckinCommands.register(dispatcher);
	}

	private static void resolveDir() {
		PathResolver.Resolved r = PathResolver.resolveDir(StorageConfig.root(), "%server%/redtale-data");
		try {
			Path dir = r.path().resolve("checkin");
			Files.createDirectories(dir);
			stateDir = dir;
		} catch (Exception e) {
			stateDir = null;
			RedtaleEconomy.LOGGER.error("[checkin] 状态目录不可用：{}", e.getMessage());
		}
	}

	// ---------------------------------------------------------------- 状态读写

	public static State state(java.util.UUID uuid) {
		String key = uuid.toString();
		State s = CACHE.get(key);
		if (s != null) return s;
		Path f = stateFile(uuid);
		if (f != null && Files.isRegularFile(f)) {
			try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
				s = State.from(GSON.toJson(GSON.fromJson(r, JsonObject.class)));
			} catch (Exception e) {
				RedtaleEconomy.LOGGER.error("[checkin] 读取 {} 失败：{}", key, e.getMessage());
				s = new State();
			}
		} else {
			s = new State();
		}
		CACHE.put(key, s);
		return s;
	}

	public static void saveState(java.util.UUID uuid, State s) {
		CACHE.put(uuid.toString(), s);
		Path f = stateFile(uuid);
		if (f == null) return;
		Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
		try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
			w.write(s.toJson());
		} catch (IOException e) {
			RedtaleEconomy.LOGGER.error("[checkin] 写入 {} 失败：{}", uuid, e.getMessage());
			return;
		}
		try {
			Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			try {
				Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
			} catch (IOException e2) {
				RedtaleEconomy.LOGGER.error("[checkin] 落盘 {} 失败：{}", uuid, e2.getMessage());
			}
		}
	}

	private static Path stateFile(java.util.UUID uuid) {
		return stateDir == null ? null : stateDir.resolve(uuid + ".json");
	}

	public static void flushAll() {
		CACHE.forEach((k, v) -> {
			try {
				saveState(java.util.UUID.fromString(k), v);
			} catch (Exception ignored) {
				// 单条失败不影响其余
			}
		});
	}

	// ---------------------------------------------------------------- 业务

	public enum Result { OK, ALREADY, NO_TIER, DISABLED }

	public record Outcome(Result result, int streak, long reward, String tierNote) {
	}

	/** 今日签到。奖励在服务端算好再入账，客户端无法影响。 */
	// Q3：同 Claim，签到也是读-改-写（state → save），类级锁防并发重复发奖
	public static synchronized Outcome checkin(java.util.UUID uuid) {
		int reset = resetHour();
		String today = TimeUtil.today(reset);
		State s = state(uuid);

		if (today.equals(s.lastDate)) {
			return new Outcome(Result.ALREADY, s.streak, 0, "");
		}

		java.time.LocalDate todayDate = TimeUtil.businessDate(reset);
		java.time.LocalDate last = TimeUtil.parseDate(s.lastDate);
		boolean continuous = !last.equals(java.time.LocalDate.MIN) && last.plusDays(1).equals(todayDate);
		if (!continuous) {
			s.streak = "keep".equals(breakPolicy()) ? Math.max(1, s.streak) : 1;
		} else {
			s.streak = Math.max(1, s.streak + 1);
		}

		s.lastDate = today;
		s.totalDays++;

		Tier tier = tierOf(s.streak);
		long reward = tier == null ? 0L : tier.money();
		saveState(uuid, s);
		if (reward > 0) {
			BalanceService.add(uuid, reward, com.akira.redtale.economy.storage.Ledger.TYPE_CHECKIN,
					"streak:" + s.streak);
		}
		if (tier != null) grantItems(uuid, tier.items());
		// R32：签到涨协会声望
		GuildModule.addRep(uuid, "checkin");
		// R20：签到给战令经验
		BattlePassModule.addXp(uuid, "checkin");
		boolean hasAny = reward > 0 || (tier != null && !tier.items().isEmpty());
		return new Outcome(hasAny ? Result.OK : Result.NO_TIER, s.streak, reward, "");
	}

	/** 单条物品奖励。 */
	public record ItemReward(String id, int count) {
	}

	/** 命中档位的完整奖励（钱 + 物品）。 */
	public record Tier(int from, int to, long money, List<ItemReward> items) {
	}

	/** 当前连续天数命中的档位；没有命中返回 null。 */
	/**
	 * 连续第 {@code streak} 天对应循环内的第几天（1..loop）。抽出来便于单测（B08）。
	 * 例：loop=7 时 streak 7→7、8→1、14→7、15→1。
	 */
	public static int loopDay(int streak, int loop) {
		int l = Math.max(1, loop);
		return ((Math.max(1, streak) - 1) % l) + 1;
	}

	public static Tier tierOf(int streak) {
		int day = loopDay(streak, loopDays());
		JsonArray tiers = store().root().has("tiers") && store().root().get("tiers").isJsonArray()
				? store().root().getAsJsonArray("tiers") : new JsonArray();
		for (com.google.gson.JsonElement el : tiers) {
			if (!el.isJsonObject()) continue;
			JsonObject t = el.getAsJsonObject();
			if (t.has("days") && t.get("days").isJsonArray()) {
				for (com.google.gson.JsonElement d : t.getAsJsonArray("days")) {
					if (d.isJsonPrimitive() && d.getAsJsonPrimitive().isNumber() && d.getAsInt() == day) {
						return parseTier(t, day, day);
					}
				}
				continue;
			}
			int from = t.has("from") ? t.get("from").getAsInt() : 1;
			int to = t.has("to") ? t.get("to").getAsInt() : from;
			if (day >= from && day <= to) return parseTier(t, from, to);
		}
		return null;
	}

	private static Tier parseTier(JsonObject t, int from, int to) {
		long money = 0L;
		JsonArray itemsArr = null;
		JsonObject reward = t.has("reward") && t.get("reward").isJsonObject()
				? t.getAsJsonObject("reward") : null;
		if (reward != null) {
			if (reward.has("money") && reward.get("money").isJsonPrimitive()) {
				money = reward.get("money").getAsLong();
			}
			if (reward.has("items") && reward.get("items").isJsonArray()) {
				itemsArr = reward.getAsJsonArray("items");
			}
		} else if (t.has("money") && t.get("money").isJsonPrimitive()) {
			money = t.get("money").getAsLong();
		}
		if (itemsArr == null && t.has("items") && t.get("items").isJsonArray()) {
			itemsArr = t.getAsJsonArray("items");
		}

		List<ItemReward> items = new ArrayList<>();
		if (itemsArr != null) {
			for (com.google.gson.JsonElement e : itemsArr) {
				if (!e.isJsonObject()) continue;
				JsonObject o = e.getAsJsonObject();
				String id = o.has("id") ? o.get("id").getAsString() : "";
				int count = o.has("count") ? o.get("count").getAsInt() : 1;
				if (id.indexOf(':') < 0 || count <= 0) continue;
				items.add(new ItemReward(id, count));
			}
		}
		return new Tier(from, to, Math.max(0, money), items);
	}

	/** 当前连续天数对应的铜奖励（物品另算）。 */
	public static long rewardFor(int streak) {
		Tier t = tierOf(streak);
		return t == null ? 0L : t.money();
	}

	/** 发物品奖励；玩家不在线只记日志（钱已经记账，物品属于额外奖励）。 */
	private static void grantItems(java.util.UUID uuid, List<ItemReward> items) {
		if (items.isEmpty()) return;
		net.minecraft.server.MinecraftServer srv = RedtaleEconomy.server();
		ServerPlayerEntity p = srv == null ? null : srv.getPlayerManager().getPlayer(uuid);
		if (p == null) {
			RedtaleEconomy.LOGGER.warn("[checkin] {} 不在线，跳过物品奖励 {}", uuid, items.size());
			return;
		}
		for (ItemReward r : items) {
			try {
				Identifier ident = Identifier.tryParse(r.id());
				Item item = ident == null ? null : Registries.ITEM.get(ident);
				if (item == null) continue;
				ItemStack stack = new ItemStack(item, r.count());
				p.getInventory().insertStack(stack);
				if (!stack.isEmpty()) p.dropItem(stack, false);
			} catch (Exception e) {
				RedtaleEconomy.LOGGER.warn("[checkin] 发物品 {} 失败：{}", r.id(), e.getMessage());
			}
		}
	}

	public enum MakeupResult { OK, DISABLED, LIMIT, NOT_NEEDED, NO_FUNDS, TOO_OLD, NO_TICKET }

	/**
	 * 补签：把「昨天」补上，连续链接回来，但不补发那天的奖励。
	 *
	 * <p>前提是昨天确实缺签，且未超出 lookback 与每日次数上限。
	 */
	public static MakeupResult makeup(java.util.UUID uuid) {
		ConfigStore cfg = store();
		if (!cfg.getPathBool("makeup.enabled", true)) return MakeupResult.DISABLED;

		int reset = resetHour();
		String today = TimeUtil.today(reset);
		java.time.LocalDate todayDate = TimeUtil.businessDate(reset);

		State s = state(uuid);
		java.time.LocalDate last = TimeUtil.parseDate(s.lastDate);
		int lookback = (int) cfg.getPathLong("makeup.allowBackfillDays", 7);
		int streak = Math.max(0, s.streak);

		// ★找最近一个"缺失日"★（不再只认昨天）
		// 已签区间是 [last - (streak-1), last]；从昨天往前扫，第一个不在区间内的就是缺口。
		java.time.LocalDate target = null;
		for (int i = 1; i <= lookback; i++) {
			java.time.LocalDate d = todayDate.minusDays(i);
			if (!last.equals(java.time.LocalDate.MIN) && d.isAfter(last)) continue;
			boolean signed = !last.equals(java.time.LocalDate.MIN)
					&& !d.isBefore(last.minusDays(streak - 1));
			if (!signed) {
				target = d;
				break;
			}
		}
		if (target == null) return MakeupResult.NOT_NEEDED;
		if (!last.equals(java.time.LocalDate.MIN) && target.isBefore(todayDate.minusDays(lookback))) {
			return MakeupResult.TOO_OLD;
		}

		String month = today.length() >= 7 ? today.substring(0, 7) : today;
		int usedMonth = month.equals(s.makeupMonth) ? s.makeupUsedMonth : 0;
		int maxMonth = (int) cfg.getPathLong("makeup.maxPerMonth", 3);
		if (usedMonth >= maxMonth) return MakeupResult.LIMIT;

		// 优先消耗补签券；券为空才扣铜币
		String ticket = cfg.getString("makeup.ticketItem", "");
		if (ticket != null && !ticket.isBlank()) {
			if (!consumeTicket(uuid, ticket)) return MakeupResult.NO_TICKET;
		} else {
			long price = cfg.getPathLong("makeup.price", 500);
			if (price > 0 && !BalanceService.take(uuid, price)) return MakeupResult.NO_FUNDS;
		}

		// 只有补的是昨天、且原链到前天，才把它接回链上；其它缺口只计总天数
		if (target.equals(todayDate.minusDays(1))
				&& (last.equals(java.time.LocalDate.MIN) || last.equals(todayDate.minusDays(2)))) {
			s.lastDate = target.toString();
			s.streak = streak + 1;
		}
		s.makeupDate = today;
		s.makeupMonth = month;
		s.makeupUsedMonth = usedMonth + 1;
		s.totalDays += 1;
		// 补签照常发奖（按当前档位算，和正常签到一致）
		Tier tier = tierOf(s.streak);
		long reward = tier == null ? 0L : tier.money();
		saveState(uuid, s);
		if (reward > 0) {
			BalanceService.add(uuid, reward, com.akira.redtale.economy.storage.Ledger.TYPE_CHECKIN,
					"makeup:" + target);
		}
		LAST_MAKEUP_DATE = target.toString();
		return MakeupResult.OK;
	}

	/** 扣 1 个补签券；背包不够返回 false（不扣部分）。 */
	private static boolean consumeTicket(java.util.UUID uuid, String ticketId) {
		net.minecraft.server.MinecraftServer srv = RedtaleEconomy.server();
		ServerPlayerEntity p = srv == null ? null : srv.getPlayerManager().getPlayer(uuid);
		if (p == null) return false;
		Identifier ident = Identifier.tryParse(ticketId);
		Item item = ident == null ? null : Registries.ITEM.get(ident);
		if (item == null) return false;
		for (int i = 0; i < p.getInventory().size(); i++) {
			ItemStack st = p.getInventory().getStack(i);
			if (st.isEmpty() || st.getItem() != item) continue;
			st.decrement(1);
			if (st.isEmpty()) p.getInventory().setStack(i, ItemStack.EMPTY);
			p.getInventory().markDirty();
			return true;
		}
		return false;
	}

	public static List<Path> debugFiles() {
		List<Path> out = new ArrayList<>();
		if (stateDir != null && Files.isDirectory(stateDir)) {
			try (var s = Files.list(stateDir)) {
				s.filter(Files::isRegularFile).forEach(out::add);
			} catch (IOException ignored) {
				// 调试用，失败返回空列表
			}
		}
		return out;
	}
}
