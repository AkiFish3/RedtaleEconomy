package com.akira.redtale.economy.module.impl;

import com.akira.redtale.economy.RedtaleEconomy;
import com.akira.redtale.economy.command.GuildCommands;
import com.akira.redtale.economy.config.ConfigManager;
import com.akira.redtale.economy.config.GuildConfig;
import com.akira.redtale.economy.config.StorageConfig;
import com.akira.redtale.economy.guild.GuildData;
import com.akira.redtale.economy.lang.Lang;
import com.akira.redtale.economy.module.RedtaleModule;
import com.akira.redtale.economy.util.PathResolver;
import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityWorldChangeEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
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

	private static void resolveDir() {
		try {
			PathResolver.Resolved r = PathResolver.resolveDir(StorageConfig.root(), "%server%/redtale-data");
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
		int idx = rankIndex(uuid);
		if (idx <= 0) return 1.0;
		double off = Math.min(GuildConfig.maxDiscount(), idx * GuildConfig.shopDiscountPerRank());
		return Math.max(0.0, 1.0 - off);
	}

	/** 商店折后价（向下取整，最低 1）。 */
	public static long applyShopDiscount(UUID uuid, long price) {
		if (price <= 0) return price;
		double m = shopMultiplier(uuid);
		if (m >= 1.0) return price;
		return Math.max(1, (long) Math.floor(price * m));
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

		ServerEntityWorldChangeEvents.AFTER_PLAYER_CHANGE_WORLD.register((player, origin, destination) -> {
			try {
				newDimension(player, destination);
			} catch (Exception e) {
				com.akira.redtale.economy.util.Logs.warnOnce("guild.dimension", "[guild] 维度声望失败：{}", e.getMessage());
			}
		});

		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			try {
				ServerPlayerEntity p = handler.getPlayer();
				newDimension(p, p.getServerWorld());
				checkBiome(p, p.getServerWorld());
			} catch (Exception e) {
				RedtaleEconomy.LOGGER.warn("[guild] JOIN 声望失败：{}", e.getMessage());
			}
		});

		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			try {
				GuildData.evict(handler.getPlayer().getUuid());
				LAST_BIOME.remove(handler.getPlayer().getUuid());
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
