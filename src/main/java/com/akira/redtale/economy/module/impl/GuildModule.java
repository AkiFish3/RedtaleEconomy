package com.akira.redtale.economy.module.impl;

import com.akira.redtale.economy.module.ModuleEvents;
import com.akira.redtale.economy.RedtaleEconomy;
import com.akira.redtale.economy.command.GuildCommands;
import com.akira.redtale.economy.config.ConfigManager;
import com.akira.redtale.economy.config.GuildConfig;
import com.akira.redtale.economy.guild.GuildData;
import com.akira.redtale.economy.lang.Lang;
import com.akira.redtale.economy.module.RedtaleModule;
import com.akira.redtale.economy.util.DataRoot;
import com.akira.redtale.economy.util.PathResolver;
import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 冒险者协会等级（R32）：永久声望，F→S。
 *
 * <p>声望只由 {@link #addRep(UUID, String)} 增加（来源与数值全在 {@code guild.json → rep}）；
 * 等级由阈值现算，所以调高阈值会立刻降级、调低会立刻升级，不写迁移代码。
 * 特权对外只有三个纯函数：{@link #shopMultiplier}、{@link #bonusBountySlots}、{@link #teleportMultiplier}。
 */
public final class GuildModule implements RedtaleModule {

	private static volatile boolean hooksInstalled;
	private static Path dir;
	private static int biomeCounter;
	private static final Map<UUID, String> LAST_BIOME = new HashMap<>();

	@Override
	public String id() {
		return "guild";
	}

	@Override
	public void defineConfig(ConfigManager cfg) {
		cfg.define(GuildConfig.FILE, GuildConfig.SCHEMA);
	}

	@Override
	public void onLoad(ConfigManager cfg) {
		resolveDir();
		installHooks();
	}

	@Override
	public void onReload(ConfigManager cfg) {
		GuildData.flushAll();
		GuildData.clearCache();
		resolveDir();
	}

	@Override
	public void registerCommands(CommandDispatcher<ServerCommandSource> dispatcher) {
		GuildCommands.register(dispatcher);
	}

	/** R36：开服后数据目录重绑（{@code DataRoot.onWorldReady} 调用，%world% 这时才可用）。 */
	@Override
	public void rebindDataDir() {
		resolveDir();
	}

	private static void resolveDir() {
		try {
			PathResolver.Resolved r = DataRoot.resolve();
			Path d = r.path().resolve("guild");
			Files.createDirectories(d);
			dir = d;
			GuildData.setDir(d);
		} catch (Exception e) {
			dir = null;
			GuildData.setDir(null);
			RedtaleEconomy.LOGGER.error("[guild] 目录不可用：{}", e.getMessage());
		}
	}

	public static boolean enabled() {
		return com.akira.redtale.economy.module.ModuleManager.get().isEnabled("guild")
				&& GuildConfig.enabled();
	}

	// ---------------------------------------------------------------- 等级 / 声望

	public static long rep(UUID uuid) {
		return uuid == null ? 0 : GuildData.of(uuid).rep;
	}

	/** 当前等级；等级表为空或未启用时返回 null。 */
	public static GuildConfig.Rank rankOf(UUID uuid) {
		return rankFor(rep(uuid));
	}

	public static GuildConfig.Rank rankFor(long rep) {
		List<GuildConfig.Rank> ranks = GuildConfig.ranks();
		GuildConfig.Rank cur = ranks.isEmpty() ? null : ranks.get(0);
		for (GuildConfig.Rank r : ranks) {
			if (rep >= r.rep()) cur = r;
			else break;
		}
		return cur;
	}

	/** 等级序号（0 起）；找不到返回 -1。 */
	public static int rankIndex(UUID uuid) {
		GuildConfig.Rank cur = rankOf(uuid);
		if (cur == null) return -1;
		List<GuildConfig.Rank> ranks = GuildConfig.ranks();
		for (int i = 0; i < ranks.size(); i++) {
			if (ranks.get(i).id().equals(cur.id())) return i;
		}
		return -1;
	}

	/** 下一个等级；已满级返回 null。 */
	public static GuildConfig.Rank nextRank(UUID uuid) {
		List<GuildConfig.Rank> ranks = GuildConfig.ranks();
		GuildConfig.Rank cur = rankOf(uuid);
		if (cur == null) return null;
		for (int i = 0; i < ranks.size(); i++) {
			if (ranks.get(i).id().equals(cur.id())) {
				return i + 1 < ranks.size() ? ranks.get(i + 1) : null;
			}
		}
		return null;
	}

	/** 按来源加声望（bounty/submit/newBiome/newDimension/ftbqChapter/checkin）。返回是否升级。 */
	public static synchronized boolean addRep(UUID uuid, String source) {
		if (uuid == null || !enabled()) return false;
		long amount = GuildConfig.repGain(source);
		return amount > 0 && addRep(uuid, source, amount);
	}

	public static synchronized boolean addRep(UUID uuid, String source, long amount) {
		if (uuid == null || amount <= 0 || !enabled()) return false;
		GuildData d = GuildData.of(uuid);
		GuildConfig.Rank before = rankFor(d.rep);
		d.rep += amount;
		d.dirty = true;
		d.save();
		GuildConfig.Rank after = rankFor(d.rep);
		boolean up = before == null || after == null || !before.id().equals(after.id());
		if (up && after != null) notifyRankUp(uuid, after);
		return up;
	}

	// ---------------------------------------------------------------- T59 扣声望

	/** 同 (玩家, 来源) 上次扣声望的时间戳，用于冷却。 */
	private static final Map<String, Long> LAST_LOSS = new java.util.concurrent.ConcurrentHashMap<>();

	/** 按来源扣声望（{@code guild.json → repLoss.sources}）。返回实际扣掉多少。 */
	public static synchronized long loseRep(UUID uuid, String source) {
		if (uuid == null || !enabled() || !GuildConfig.repLossEnabled()) return 0;
		long amount = GuildConfig.repLoss(source);
		return amount <= 0 ? 0 : loseRep(uuid, source, amount);
	}

	/**
	 * 扣声望（T59）：冷却 + 每日上限 + 下限三重约束，返回**实际**扣掉的量。
	 *
	 * <p>返回 0 的三种情况：模块关、冷却中、已达每日上限/下限。调用方不需要再判断。
	 */
	public static synchronized long loseRep(UUID uuid, String source, long amount) {
		if (uuid == null || amount <= 0 || !enabled() || !GuildConfig.repLossEnabled()) return 0;
		String src = source == null ? "" : source;
		long now = System.currentTimeMillis();
		int cd = GuildConfig.repLossCooldownSeconds();
		String cdKey = uuid + "|" + src;
		if (cd > 0) {
			Long last = LAST_LOSS.get(cdKey);
			if (last != null && now - last < cd * 1000L) return 0;
		}
		GuildData d = GuildData.of(uuid);
		String today = java.time.LocalDate.now().toString();
		if (!today.equals(d.repLossDay)) {
			d.repLossDay = today;
			d.repLossToday = 0;
		}
		long cap = GuildConfig.repLossDailyCap();
		long allowed = amount;
		if (cap > 0) allowed = Math.min(allowed, Math.max(0, cap - d.repLossToday));
		if (allowed <= 0) return 0;
		long next = Math.max(GuildConfig.repFloor(), d.rep - allowed);
		long applied = d.rep - next;
		if (applied <= 0) return 0;
		GuildConfig.Rank before = rankFor(d.rep);
		d.rep = next;
		d.repLossToday += applied;
		d.dirty = true;
		d.save();
		LAST_LOSS.put(cdKey, now);
		GuildConfig.Rank after = rankFor(d.rep);
		if (before != null && after != null && !before.id().equals(after.id())) {
			notifyRankChange(uuid, before, after);
		}
		return applied;
	}

	private static void notifyRankChange(UUID uuid, GuildConfig.Rank before, GuildConfig.Rank after) {
		try {
			ServerPlayerEntity p = RedtaleEconomy.server() == null ? null
					: RedtaleEconomy.server().getPlayerManager().getPlayer(uuid);
			if (p != null) {
				p.sendMessage(Text.literal(Lang.msg("guild.rankDown", before.name(), after.name(),
						GuildModule.rep(uuid))), false);
				EasyNpcModule.syncRankTag(p);
			}
		} catch (Exception ignored) {
			// 提示失败不影响数据
		}
	}

	private static void notifyRankUp(UUID uuid, GuildConfig.Rank rank) {
		try {
			ServerPlayerEntity p = RedtaleEconomy.server() == null ? null
					: RedtaleEconomy.server().getPlayerManager().getPlayer(uuid);
			if (p != null) {
				p.sendMessage(Text.literal(Lang.msg("guild.rankUp", rank.name(), rank.id())), false);
				// R33：升级后立刻同步 EasyNPC 用的等级 tag
				EasyNpcModule.syncRankTag(p);
			}
		} catch (Exception ignored) {
			// 提示失败不影响数据
		}
	}

	// ---------------------------------------------------------------- 特权

	/** 商店价格倍率（1 - 折扣）。未启用或等级为 0 时返回 1。 */
	public static double shopMultiplier(UUID uuid) {
		if (!enabled()) return 1.0;
		// T64：负声望优先 —— 有税就不打折（否则「负声望 + 高等级」会互相抵消）
		double tax = negativeTax(uuid);
		if (tax > 0) return 1.0 + tax;
		int idx = rankIndex(uuid);
		if (idx <= 0) return 1.0;
		double off = Math.min(GuildConfig.maxDiscount(), idx * GuildConfig.shopDiscountPerRank());
		return Math.max(0.0, 1.0 - off);
	}

	/** 商店折后价（向下取整，最低 1）；负声望时倍率 >1，等价加税。 */
	public static long applyShopDiscount(UUID uuid, long price) {
		if (price <= 0) return price;
		double m = shopMultiplier(uuid);
		if (Math.abs(m - 1.0) < 1e-9) return price;
		return priceAt(price, m);
	}

	/**
	 * 按倍率换算价格（纯函数，便于单测）。
	 *
	 * <p>向下取整、最低 1 铜；末尾加 {@code 1e-6} 抵消二进制浮点误差 ——
	 * 实测 {@code 100 * 1.15 == 114.99999999999999}，直接 floor 会少收 1 铜（T64 重度加税）。
	 */
	public static long priceAt(long price, double multiplier) {
		if (price <= 0) return price;
		if (Double.isNaN(multiplier) || multiplier <= 0) return price;
		return Math.max(1, (long) Math.floor(price * multiplier + 1e-6));
	}

	// ---------------------------------------------------------------- T64 负声望效果

	/** 负声望加税比例（0 = 没有负面效果）。 */
	public static double negativeTax(UUID uuid) {
		if (!enabled() || uuid == null) return 0.0;
		long r = rep(uuid);
		if (r <= GuildConfig.negativeSevereBelow()) return GuildConfig.negativeSevereTax();
		if (r <= GuildConfig.negativeLightBelow()) return GuildConfig.negativeLightTax();
		return 0.0;
	}

	/** 重度负声望是否拒绝商店购买。 */
	public static boolean denied(UUID uuid) {
		if (!enabled() || uuid == null) return false;
		return GuildConfig.negativeSevereDeny() && rep(uuid) <= GuildConfig.negativeSevereBelow();
	}

	public static double teleportMultiplier(UUID uuid) {
		if (!enabled()) return 1.0;
		int idx = rankIndex(uuid);
		if (idx <= 0) return 1.0;
		double off = Math.min(0.9, idx * GuildConfig.teleportDiscountPerRank());
		return Math.max(0.0, 1.0 - off);
	}

	/** 悬赏位加成（基础 slotCount 之外，封顶 maxBountySlots）。 */
	public static int bonusBountySlots(UUID uuid) {
		if (!enabled()) return 0;
		int idx = rankIndex(uuid);
		if (idx <= 0) return 0;
		return Math.max(0, idx * GuildConfig.bountySlotsPerRank());
	}

	// ---------------------------------------------------------------- 事件（群系 / 维度）

	private static void installHooks() {
		if (hooksInstalled) return;
		hooksInstalled = true;

		ModuleEvents.onWorldChange("guild", (player, origin, destination) ->
				newDimension(player, destination));

		ModuleEvents.onJoin("guild", p -> {
			try {
				newDimension(p, p.getServerWorld());
				checkBiome(p, p.getServerWorld());
			} catch (Exception e) {
				RedtaleEconomy.LOGGER.warn("[guild] JOIN 声望失败：{}", e.getMessage());
			}
		});

		// T59：死亡扣声望（冷却由 loseRep 统一管；返回 0 表示被限流，不打提示）
		ModuleEvents.onDeath("guild", (victim, source, killer) -> {
			if (!(victim instanceof ServerPlayerEntity p)) return;
			try {
				long lost = loseRep(p.getUuid(), "death");
				if (lost > 0) {
					p.sendMessage(Text.literal(Lang.msg("guild.repLost", lost, "death", rep(p.getUuid()))), false);
				}
			} catch (Exception e) {
				RedtaleEconomy.LOGGER.warn("[guild] 死亡扣声望失败：{}", e.getMessage());
			}
		});

		ModuleEvents.onQuit("guild", p -> {
			try {
				GuildData.evict(p.getUuid());
				LAST_BIOME.remove(p.getUuid());
			} catch (Exception ignored) {
				// 退出清缓存失败不影响存档
			}
		});

		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (!enabled()) return;
			if (++biomeCounter < 40) return;
			biomeCounter = 0;
			for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
				try {
					checkBiome(p, p.getServerWorld());
				} catch (Exception ignored) {
					// 单个玩家采样失败不影响其余
				}
			}
		});
	}

	private static void newDimension(ServerPlayerEntity p, ServerWorld world) {
		if (!enabled()) return;
		String dim = world.getRegistryKey().getValue().toString();
		GuildData d = GuildData.of(p.getUuid());
		if (d.seenDimensions.add(dim)) {
			d.dirty = true;
			d.save();
			addRep(p.getUuid(), "newDimension");
		}
	}

	private static void checkBiome(ServerPlayerEntity p, ServerWorld world) {
		if (!enabled()) return;
		var key = world.getBiome(p.getBlockPos()).getKey();
		if (key.isEmpty()) return;
		String id = key.get().getValue().toString();
		if (id.equals(LAST_BIOME.get(p.getUuid()))) return;
		LAST_BIOME.put(p.getUuid(), id);
		GuildData d = GuildData.of(p.getUuid());
		if (d.seenBiomes.add(id)) {
			d.dirty = true;
			d.save();
			addRep(p.getUuid(), "newBiome");
		}
	}
}
