package com.akira.redtale.economy.module.impl;

import com.akira.redtale.economy.RedtaleEconomy;
import com.akira.redtale.economy.command.PassCommands;
import com.akira.redtale.economy.config.BattlePassConfig;
import com.akira.redtale.economy.config.ConfigManager;
import com.akira.redtale.economy.config.StorageConfig;
import com.akira.redtale.economy.economy.BalanceService;
import com.akira.redtale.economy.lang.Lang;
import com.akira.redtale.economy.module.RedtaleModule;
import com.akira.redtale.economy.storage.Ledger;
import com.akira.redtale.economy.util.DataRoot;
import com.akira.redtale.economy.util.PathResolver;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 战令 / 通行证（R20，spec §21）。
 *
 * <p>每人每赛季一份数据：{@code redtale-data/battlepass/<season>/<uuid>.json}
 * （xp / premium / claimed.free / claimed.premium）。等级由 xp 现算，
 * 奖励表与经验系数全在 {@code battlepass.json}。
 */
public final class BattlePassModule implements RedtaleModule {

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
	private static final Map<UUID, PassData> CACHE = new ConcurrentHashMap<>();
	private static Path baseDir;
	private static volatile boolean hooksInstalled;
	private static final Map<UUID, Integer> PLAY_TICKS = new ConcurrentHashMap<>();

	/** 单个玩家单赛季数据。 */
	public static final class PassData {
		public String season = "";
		public long xp;
		public boolean premium;
		public final Set<Integer> claimedFree = new LinkedHashSet<>();
		public final Set<Integer> claimedPremium = new LinkedHashSet<>();
		/** R22：已经发过 FTBQ 经验的任务 code（去重键）。 */
		public final Set<String> ftbqQuests = new LinkedHashSet<>();
		public boolean dirty;

		String toJson() {
			JsonObject o = new JsonObject();
			o.addProperty("_version", 1);
			o.addProperty("season", season);
			o.addProperty("xp", xp);
			o.addProperty("premium", premium);
			JsonObject claimed = new JsonObject();
			claimed.add("free", arr(claimedFree));
			claimed.add("premium", arr(claimedPremium));
			o.add("claimed", claimed);
			JsonArray q = new JsonArray();
			for (String c : ftbqQuests) q.add(c);
			o.add("ftbqQuests", q);
			return GSON.toJson(o);
		}

		static PassData from(String json) {
			PassData d = new PassData();
			try {
				JsonObject o = GSON.fromJson(json, JsonObject.class);
				if (o == null) return d;
				d.season = o.has("season") ? o.get("season").getAsString() : "";
				d.xp = o.has("xp") ? Math.max(0, o.get("xp").getAsLong()) : 0;
				d.premium = o.has("premium") && o.get("premium").getAsBoolean();
				if (o.has("ftbqQuests") && o.get("ftbqQuests").isJsonArray()) {
					for (var e : o.getAsJsonArray("ftbqQuests")) d.ftbqQuests.add(e.getAsString());
				}
				if (o.has("claimed") && o.get("claimed").isJsonObject()) {
					JsonObject c = o.getAsJsonObject("claimed");
					if (c.has("free")) for (var e : c.getAsJsonArray("free")) d.claimedFree.add(e.getAsInt());
					if (c.has("premium")) for (var e : c.getAsJsonArray("premium")) d.claimedPremium.add(e.getAsInt());
				}
			} catch (Exception e) {
				RedtaleEconomy.LOGGER.warn("[pass] 解析失败，按空数据：{}", e.getMessage());
			}
			return d;
		}

		private static JsonArray arr(Set<Integer> set) {
			JsonArray a = new JsonArray();
			for (int i : set) a.add(i);
			return a;
		}
	}

	@Override
	public String id() {
		return "battlepass";
	}

	@Override
	public boolean defaultEnabled() {
		return false;
	}

	@Override
	public void defineConfig(ConfigManager cfg) {
		cfg.define(BattlePassConfig.FILE, BattlePassConfig.SCHEMA);
	}

	@Override
	public void onLoad(ConfigManager cfg) {
		resolveDir();
		installHooks();
		// T02：把当前赛季登记进 seasons.json（服主第一次开服就有一份清单）
		PassSeasons.ensureCurrent(BattlePassConfig.seasonId(), BattlePassConfig.seasonName());
		// T04：开服补跑定时结算（停机期间到期的赛季不会漏关账）
		PassSeasons.autoCloseExpired(System.currentTimeMillis(), java.time.LocalDate.now());
	}

	@Override
	public void onReload(ConfigManager cfg) {
		flushAll();
		CACHE.clear();
		resolveDir();
		PassSeasons.ensureCurrent(BattlePassConfig.seasonId(), BattlePassConfig.seasonName());
	}

	@Override
	public void registerCommands(CommandDispatcher<ServerCommandSource> dispatcher) {
		PassCommands.register(dispatcher);
	}

	/** R36：开服后数据目录重绑（{@code DataRoot.onWorldReady} 调用，%world% 这时才可用）。 */
	@Override
	public void rebindDataDir() {
		resolveDir();
		// T02：%world% 就绪后清单落在真正的数据根里，这里补登记一次
		PassSeasons.ensureCurrent(BattlePassConfig.seasonId(), BattlePassConfig.seasonName());
	}

	private static void resolveDir() {
		try {
			PathResolver.Resolved r = DataRoot.resolve();
			Path d = r.path().resolve("battlepass");
			Files.createDirectories(d);
			baseDir = d;
		} catch (Exception e) {
			baseDir = null;
			RedtaleEconomy.LOGGER.error("[pass] 目录不可用：{}", e.getMessage());
		}
	}

	public static boolean enabled() {
		return com.akira.redtale.economy.module.ModuleManager.get().isEnabled("battlepass")
				&& BattlePassConfig.enabled();
	}

	// ---------------------------------------------------------------- 等级

	/** 升到 level 级所需的累计经验。linear=每级固定；exp=l(l+1)/2 递增。 */
	public static long xpForLevel(int level) {
		if (level <= 1) return 0;
		long base = BattlePassConfig.xpPerLevel();
		if ("exp".equalsIgnoreCase(BattlePassConfig.curve())) {
			long l = level - 1;
			return base * l * (l + 1) / 2;
		}
		return base * (level - 1);
	}

	public static int levelFor(long xp) {
		int max = BattlePassConfig.maxLevel();
		int level = 1;
		while (level < max && xp >= xpForLevel(level + 1)) level++;
		return level;
	}

	/** 当前等级内的进度（0..1）。 */
	public static double progress(long xp) {
		int level = levelFor(xp);
		long cur = xpForLevel(level);
		long next = xpForLevel(Math.min(BattlePassConfig.maxLevel(), level + 1));
		if (next <= cur) return 1.0;
		return Math.max(0, Math.min(1, (xp - cur) / (double) (next - cur)));
	}

	// ---------------------------------------------------------------- 数据

	public static PassData data(UUID uuid) {
		PassData d = CACHE.get(uuid);
		if (d != null) return d;
		d = read(uuid);
		CACHE.put(uuid, d);
		return d;
	}

	private static String season() {
		return BattlePassConfig.seasonId();
	}

	private static PassData read(UUID uuid) {
		PassData d = new PassData();
		d.season = season();
		Path f = file(uuid);
		if (f == null || !Files.isRegularFile(f)) return d;
		try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
			JsonObject o = GSON.fromJson(r, JsonObject.class);
			PassData got = o == null ? new PassData() : PassData.from(GSON.toJson(o));
			if (!d.season.equals(got.season)) return d;   // 换赛季 = 新档
			return got;
		} catch (Exception e) {
			RedtaleEconomy.LOGGER.error("[pass] 读取 {} 失败：{}", uuid, e.getMessage());
			return d;
		}
	}

	public static void save(UUID uuid, PassData d) {
		CACHE.put(uuid, d);
		Path f = file(uuid);
		if (f == null) return;
		Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
		try {
			Files.createDirectories(f.getParent());
			try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
				w.write(d.toJson());
			}
			try {
				Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (IOException atomicFail) {
				Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
			}
			d.dirty = false;
		} catch (IOException e) {
			RedtaleEconomy.LOGGER.error("[pass] 写入 {} 失败：{}", uuid, e.getMessage());
		}
	}

	private static Path file(UUID uuid) {
		return baseDir == null ? null : baseDir.resolve(season()).resolve(uuid + ".json");
	}

	public static void flushAll() {
		for (Map.Entry<UUID, PassData> e : CACHE.entrySet()) {
			try {
				if (e.getValue().dirty) save(e.getKey(), e.getValue());
			} catch (Exception ignored) {
				// 单条失败不影响其余
			}
		}
	}

	// ---------------------------------------------------------------- 经验

	/** 按来源加经验（checkin / taskClaim）；付费轨有加成。 */
	public static synchronized void addXp(UUID uuid, String source) {
		long amount = BattlePassConfig.xpFor(source);
		if (uuid == null || amount <= 0 || !enabled()) return;
		addXp(uuid, source, amount);
	}

	public static synchronized void addXp(UUID uuid, String source, long amount) {
		if (uuid == null || amount <= 0 || !enabled()) return;
		if (!PassSeasons.writable(season())) return;   // T02：已归档赛季进度冻结
		PassData d = data(uuid);
		double bonus = d.premium ? 1.0 + Math.max(0, BattlePassConfig.premiumBonus()) : 1.0;
		long gain = Math.max(1, Math.round(amount * bonus));
		int before = levelFor(d.xp);
		d.xp += gain;
		d.dirty = true;
		save(uuid, d);
		int after = levelFor(d.xp);
		if (after > before) notifyLevel(uuid, after);
	}

	/** 商店消费换经验（每 1 铜币 × 系数）。 */
	public static void addSpendXp(UUID uuid, long spent) {
		if (spent <= 0) return;
		double rate = BattlePassConfig.xpPerCurrency();
		if (rate <= 0) return;
		long amount = (long) Math.floor(spent * rate);
		if (amount > 0) addXp(uuid, "shop", amount);
	}

	private static void notifyLevel(UUID uuid, int level) {
		try {
			ServerPlayerEntity p = RedtaleEconomy.server() == null ? null
					: RedtaleEconomy.server().getPlayerManager().getPlayer(uuid);
			if (p != null) p.sendMessage(Text.literal(Lang.msg("pass.levelUp", level)), true);
		} catch (Exception ignored) {
			// 提示失败不影响数据
		}
	}

	/**
	 * R22：FTBQ 任务完成 → 战令经验。
	 *
	 * <p>去重键是 (赛季, 任务 code, 玩家)：同一任务每人只拿一次；
	 * onlyOncePerPlayer=false 时按「每次完成都发」处理。
	 */
	public static synchronized void addQuestXp(UUID uuid, String questCode, long xp) {
		if (uuid == null || xp <= 0 || !enabled()) return;
		PassData d = data(uuid);
		if (BattlePassConfig.ftbqOnlyOnce()) {
			if (!d.ftbqQuests.add(questCode)) return;
		}
		d.dirty = true;
		save(uuid, d);
		addXp(uuid, "ftbq", xp);
	}

	// ---------------------------------------------------------------- 赛季切换（T04）

	/** 换赛季结果：干了什么、切换前后的 id、关账结果。 */
	public record Rollover(String from, String to, PassSeasons.ArchiveResult archive, boolean switched) {
	}

	/**
	 * T04 手动结算：归档当前赛季 → 写配置 {@code battlepass.json → seasonId} → 清缓存。
	 *
	 * <p>顺序不能反：先归档再换指针，否则换完指针就找不到"要归档的是谁"了。
	 * 配置写失败时**不切换**（只归档），返回 {@code switched=false} 让服主看到并重试。
	 */
	public static synchronized Rollover rolloverTo(String next) {
		String from = season();
		if (next == null || next.isBlank() || next.equals(from)) {
			return new Rollover(from, next, PassSeasons.ArchiveResult.NO_DATA, false);
		}
		flushAll();
		PassSeasons.ArchiveResult ar = PassSeasons.archive(from, System.currentTimeMillis());
		boolean wrote;
		try {
			com.akira.redtale.economy.config.ConfigStore store =
					com.akira.redtale.economy.config.ConfigManager.get().store(BattlePassConfig.FILE);
			store.setPath("seasonId", next);
			wrote = store.save(true);
		} catch (Exception e) {
			RedtaleEconomy.LOGGER.error("[pass] 写赛季指针失败：{}", e.getMessage());
			wrote = false;
		}
		if (wrote) {
			CACHE.clear();
			PassSeasons.ensureCurrent(next, next);
			RedtaleEconomy.LOGGER.info("[pass] 赛季 {} → {}（归档 {}）", from, next, ar);
		} else {
			RedtaleEconomy.LOGGER.error("[pass] 赛季指针未切换（仍为 {}），数据已归档 {}", from, ar);
		}
		return new Rollover(from, next, ar, wrote);
	}

	// ---------------------------------------------------------------- 领取 / 解锁

	public enum ClaimResult { OK, LOCKED, NOT_REACHED, ALREADY, DISABLED }

	/** 领取某等级奖励；track = free / premium。 */
	public static synchronized ClaimResult claim(UUID uuid, String track, int level) {
		if (!enabled()) return ClaimResult.DISABLED;
		if (!PassSeasons.writable(season())) return ClaimResult.DISABLED;   // T02
		PassData d = data(uuid);
		if (level < 1 || level > BattlePassConfig.maxLevel()) return ClaimResult.NOT_REACHED;
		if (level > levelFor(d.xp)) return ClaimResult.NOT_REACHED;
		boolean premiumTrack = "premium".equalsIgnoreCase(track);
		if (premiumTrack && !d.premium) return ClaimResult.LOCKED;
		Set<Integer> claimed = premiumTrack ? d.claimedPremium : d.claimedFree;
		if (!claimed.add(level)) return ClaimResult.ALREADY;
		BattlePassConfig.Reward reward = null;
		for (BattlePassConfig.Reward r : BattlePassConfig.track(premiumTrack ? "premium" : "free")) {
			if (r.level() == level) {
				reward = r;
				break;
			}
		}
		d.dirty = true;
		save(uuid, d);
		if (reward != null) grant(uuid, reward);
		return ClaimResult.OK;
	}

	private static void grant(UUID uuid, BattlePassConfig.Reward reward) {
		if (reward.money() > 0) {
			BalanceService.add(uuid, reward.money(), Ledger.TYPE_PASS, "pass:level:" + reward.level());
		}
		ServerPlayerEntity p = RedtaleEconomy.server() == null ? null
				: RedtaleEconomy.server().getPlayerManager().getPlayer(uuid);
		for (BattlePassConfig.ItemReward ir : reward.items()) {
			Item item = item(ir.id());
			if (item == null) continue;
			ItemStack stack = new ItemStack(item, ir.count());
			if (p == null || !p.getInventory().insertStack(stack)) {
				if (p != null) p.dropItem(stack, false);
			}
		}
	}

	private static Item item(String id) {
		try {
			Identifier ident = Identifier.tryParse(id);
			if (ident == null || !Registries.ITEM.containsId(ident)) return null;
			return Registries.ITEM.get(ident);
		} catch (Exception e) {
			return null;
		}
	}

	/** 解锁付费轨；返回是否成功。 */
	public static synchronized boolean unlockPremium(UUID uuid) {
		if (!enabled()) return false;
		PassData d = data(uuid);
		if (d.premium) return false;
		long amount = BattlePassConfig.unlockAmount();
		if ("money".equalsIgnoreCase(BattlePassConfig.unlockCurrency())) {
			if (!BalanceService.take(uuid, amount, Ledger.TYPE_PASS, "pass:unlock")) return false;
		} else {
			if (!com.akira.redtale.economy.module.ModuleManager.get().isEnabled("premium")) return false;
			if (!PremiumModule.take(uuid, amount)) return false;
		}
		d.premium = true;
		d.dirty = true;
		save(uuid, d);
		return true;
	}

	/**
	 * R42：管理员手动解锁付费轨 —— <b>不扣任何货币</b>，写一条 {@code battlepass.admin.unlock} 流水。
	 *
	 * <p>已解锁返回 false（幂等，不重复记流水）。
	 */
	public static synchronized boolean adminUnlock(UUID uuid, String operator, String season) {
		if (!enabled()) return false;
		PassData d = data(uuid);
		if (d.premium) return false;
		d.premium = true;
		d.dirty = true;
		save(uuid, d);
		com.akira.redtale.economy.storage.Ledger.record(uuid, 0, BalanceService.get(uuid),
				com.akira.redtale.economy.storage.Ledger.TYPE_PASS_ADMIN,
				"by:" + operator + "/season:" + (season == null || season.isBlank() ? currentSeasonId() : season));
		return true;
	}

	/** 当前赛季 id（配置 season.id）。 */
	public static String currentSeasonId() {
		return BattlePassConfig.store().getPathString("season.id", "S1");
	}

	public static List<BattlePassConfig.Reward> track(String name) {
		return BattlePassConfig.track(name);
	}

	// ---------------------------------------------------------------- 事件

	private static void installHooks() {
		if (hooksInstalled) return;
		hooksInstalled = true;

		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			UUID uuid = handler.getPlayer().getUuid();
			PassData d = CACHE.remove(uuid);
			if (d != null && d.dirty) save(uuid, d);
			PLAY_TICKS.remove(uuid);
		});

		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (!enabled()) return;
			for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
				int t = PLAY_TICKS.merge(p.getUuid(), 1, Integer::sum);
				if (t < 1200) continue;   // 20 tick = 1 秒 → 1200 tick = 1 分钟
				PLAY_TICKS.put(p.getUuid(), 0);
				addXp(p.getUuid(), "playMinute");
			}
		});
	}

	public static PassData snapshot(UUID uuid) {
		return data(uuid);
	}

	public static List<String> lines(UUID uuid) {
		PassData d = data(uuid);
		List<String> out = new ArrayList<>();
		int level = levelFor(d.xp);
		out.add(Lang.msg("pass.line", BattlePassConfig.seasonName(), level, d.xp,
				xpForLevel(Math.min(BattlePassConfig.maxLevel(), level + 1))));
		return out;
	}
}
