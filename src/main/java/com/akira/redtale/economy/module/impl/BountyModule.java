package com.akira.redtale.economy.module.impl;

import com.akira.redtale.economy.module.ModuleEvents;
import com.akira.redtale.economy.RedtaleEconomy;
import com.akira.redtale.economy.bounty.BountyState;
import com.akira.redtale.economy.command.BountyCommands;
import com.akira.redtale.economy.config.BountyConfig;
import com.akira.redtale.economy.config.GuildConfig;
import com.akira.redtale.economy.config.ConfigManager;
import com.akira.redtale.economy.economy.BalanceService;
import com.akira.redtale.economy.module.RedtaleModule;
import com.akira.redtale.economy.storage.Ledger;
import com.akira.redtale.economy.util.DataRoot;
import com.akira.redtale.economy.util.PathResolver;
import com.akira.redtale.economy.util.TimeUtil;
import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.block.BarrelBlock;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 系统悬赏（R31）：个人进度、事件驱动、每日刷新。
 *
 * <p>9 种目标——kill / submit / loot_chest / biome / dimension / craft / fish / travel / playtime：
 * <ul>
 *   <li>kill：{@code ServerLivingEntityEvents.AFTER_DEATH}（取 attacker）</li>
 *   <li>submit：{@link TaskCommands} 缴纳成功时回调</li>
 *   <li>loot_chest：{@code UseBlockCallback}（箱子/木桶/潜影盒/Lootr）</li>
 *   <li>biome / dimension：维度切换事件 + 每 40 tick 采样群系</li>
 *   <li>craft / fish：mixin（{@code CraftingResultSlot.onTakeItem} / {@code FishingBobberEntity.use}）</li>
 *   <li>travel / playtime：每 tick 累计位移与在线时长，100 tick 落一次进度</li>
 * </ul>
 *
 * <p>进度只在服务端算，客户端没有写入通道；领取与每日任务一样走「标记已领 + 发钱」幂等路径。
 */
public final class BountyModule implements RedtaleModule {

	private static volatile boolean hooksInstalled;
	private static volatile List<BountyConfig.Def> defs = List.of();
	private static Path dir;

	/** 位移 / 在线 tick 的待落账缓冲（100 tick 结算一次，避免每 tick 写盘）。 */
	private static final Map<UUID, Track> PENDING = new ConcurrentHashMap<>();

	/** 每人一份的位移/在线缓冲。 */
	private static final class Track {
		double distance;
		double ticks;
		int lastX;
		int lastZ;
		boolean hasLast;
	}
	private static int tickCounter;
	private static int biomeCounter;
	private static final Map<UUID, String> LAST_BIOME = new HashMap<>();

	@Override
	public String id() {
		return "bounty";
	}

	@Override
	public void defineConfig(ConfigManager cfg) {
		cfg.define(BountyConfig.FILE, BountyConfig.SCHEMA);
	}

	@Override
	public void onLoad(ConfigManager cfg) {
		resolveDir();
		reloadPool();
		installHooks();
	}

	@Override
	public void onReload(ConfigManager cfg) {
		BountyState.flushAll();
		BountyState.clearCache();
		resolveDir();
		reloadPool();
	}

	@Override
	public void registerCommands(CommandDispatcher<ServerCommandSource> dispatcher) {
		BountyCommands.register(dispatcher);
	}

	private static void reloadPool() {
		defs = BountyConfig.pool();
		if (defs.isEmpty()) {
			RedtaleEconomy.LOGGER.warn("[bounty] pool 为空，悬赏不会生成");
		} else {
			RedtaleEconomy.LOGGER.info("[bounty] 已加载 {} 条悬赏定义", defs.size());
		}
	}

	/** R36：开服后数据目录重绑（{@code DataRoot.onWorldReady} 调用，%world% 这时才可用）。 */
	@Override
	public void rebindDataDir() {
		resolveDir();
	}

	private static void resolveDir() {
		try {
			PathResolver.Resolved r = DataRoot.resolve();
			Path d = r.path().resolve("bounty");
			Files.createDirectories(d);
			dir = d;
			BountyState.setDir(d);
		} catch (Exception e) {
			dir = null;
			BountyState.setDir(null);
			RedtaleEconomy.LOGGER.error("[bounty] 目录不可用：{}", e.getMessage());
		}
	}

	public static boolean enabled() {
		return com.akira.redtale.economy.module.ModuleManager.get().isEnabled("bounty")
				&& BountyConfig.enabled();
	}

	// ---------------------------------------------------------------- 每次取当天悬赏

	public static synchronized BountyState daily(UUID uuid) {
		BountyState st = BountyState.of(uuid);
		String today = TimeUtil.today(BountyConfig.resetHour());
		if (!today.equals(st.date) || st.slots.isEmpty()) {
			// R32：协会等级提高悬赏位（封顶 maxBountySlots）
			int slots = Math.min(GuildConfig.maxBountySlots(),
					BountyConfig.slotCount() + GuildModule.bonusBountySlots(uuid));
			st.reset(today, pick(uuid, today, slots));
			st.save();
		}
		return st;
	}

	/** 按 uuid + 日期做种子，保证同一天重进游戏看到的是同一批悬赏。 */
	private static List<String> pick(UUID uuid, String date, int slots) {
		List<BountyConfig.Def> pool = new ArrayList<>(defs);
		int totalWeight = 0;
		for (BountyConfig.Def d : pool) totalWeight += d.weight();
		List<String> out = new ArrayList<>();
		Random rnd = new Random(uuid.getMostSignificantBits() ^ date.hashCode());
		int want = Math.min(slots, pool.size());
		for (int i = 0; i < want && !pool.isEmpty(); i++) {
			int roll = rnd.nextInt(Math.max(1, totalWeight));
			int acc = 0;
			int idx = 0;
			for (int j = 0; j < pool.size(); j++) {
				acc += pool.get(j).weight();
				if (roll < acc) {
					idx = j;
					break;
				}
			}
			BountyConfig.Def chosen = pool.remove(idx);
			totalWeight -= chosen.weight();
			out.add(chosen.id());
		}
		return out;
	}

	// ---------------------------------------------------------------- 进度

	/**
	 * 推进某类目标的进度。
	 *
	 * @param type      类型（kill/submit/...）
	 * @param target    具体目标（物品 id / 实体 id）；空 = 任意
	 * @param amount    增量
	 * @param uniqueKey unique 目标的唯一键（群系/维度 key），非 unique 传空
	 */
	public static void progress(UUID uuid, String type, String target, int amount, String uniqueKey) {
		if (uuid == null || amount <= 0 || !enabled()) return;
		try {
			BountyState st = daily(uuid);
			boolean changed = false;
			for (BountyState.Slot slot : st.slots) {
				if (slot.claimed) continue;
				BountyConfig.Def d = find(slot.id);
				if (d == null || !d.type().equals(type)) continue;
				if (!d.target().isEmpty() && !d.target().equals(target)) continue;
				int before = slot.progress;
				if (d.unique()) {
					String key = type + ":" + uniqueKey;
					if (key.endsWith(":") || !st.seen.add(key)) continue;
					slot.progress = Math.min(d.count(), slot.progress + 1);
				} else {
					slot.progress = Math.min(d.count(), slot.progress + amount);
				}
				if (slot.progress != before) {
					changed = true;
					st.dirty = true;
					if (slot.progress >= d.count()) notifyDone(uuid, d);
				}
			}
			if (changed) st.save();
		} catch (Exception e) {
			RedtaleEconomy.LOGGER.error("[bounty] 推进进度失败：{}", e.getMessage());
		}
	}

	private static BountyConfig.Def find(String id) {
		for (BountyConfig.Def d : defs) {
			if (d.id().equals(id)) return d;
		}
		return null;
	}

	private static void notifyDone(UUID uuid, BountyConfig.Def d) {
		if (!BountyConfig.notifyOnComplete()) return;
		try {
			ServerPlayerEntity p = RedtaleEconomy.server() == null ? null
					: RedtaleEconomy.server().getPlayerManager().getPlayer(uuid);
			if (p != null) {
				p.sendMessage(Text.literal(com.akira.redtale.economy.lang.Lang.msg("bounty.done", d.id())), true);
			}
		} catch (Exception ignored) {
			// 提示失败不影响进度
		}
	}

	/** 领取一条；返回奖励金额，-1 表示不可领。 */
	public static synchronized long claim(UUID uuid, int index) {
		// 发奖前自判模块开关（同 TaskModule.claimAndSave）：别把「能不能发钱」交给调用方。
		if (!enabled()) return -1;
		BountyState st = daily(uuid);
		if (index < 0 || index >= st.slots.size()) return -1;
		BountyState.Slot slot = st.slots.get(index);
		if (slot.claimed) return -1;
		BountyConfig.Def d = find(slot.id);
		if (d == null || slot.progress < d.count()) return -1;
		slot.claimed = true;
		st.dirty = true;
		st.save();
		if (d.money() > 0) {
			BalanceService.add(uuid, d.money(), Ledger.TYPE_BOUNTY, "bounty:" + d.id());
		}
		// R32：完成悬赏涨协会声望
		GuildModule.addRep(uuid, "bounty");
		return d.money();
	}

	/** 任务缴纳（submit 类）回调；由 TaskCommands 调用。 */
	public static void onSubmit(UUID uuid, String itemId, int count) {
		progress(uuid, "submit", itemId, count, "");
	}

	public static List<BountyState.Slot> slots(UUID uuid) {
		return daily(uuid).slots;
	}

	public static BountyConfig.Def definition(String id) {
		return find(id);
	}

	// ---------------------------------------------------------------- 事件

	private static void installHooks() {
		if (hooksInstalled) return;
		hooksInstalled = true;

		ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
			try {
				if (!enabled()) return;
				ServerPlayerEntity killer = attacker(source);
				if (killer == null) return;
				progress(killer.getUuid(), "kill", Registries.ENTITY_TYPE.getId(entity.getType()).toString(), 1, "");
			} catch (Exception e) {
				com.akira.redtale.economy.util.Logs.warnOnce("bounty.kill", "[bounty] kill 进度失败：{}", e.getMessage());
			}
		});

		ModuleEvents.onUseBlock("bounty.loot", (player, world, hand, hit) -> {
			if (world.isClient() || hand != Hand.MAIN_HAND || !(player instanceof ServerPlayerEntity sp)) {
				return ActionResult.PASS;
			}
			if (!enabled()) return ActionResult.PASS;
			var state = world.getBlockState(hit.getBlockPos());
			if (isLootContainer(state)) {
				progress(sp.getUuid(), "loot_chest", "", 1, "");
			}
			return ActionResult.PASS;
		});

		ModuleEvents.onWorldChange("bounty", (player, origin, destination) -> {
			if (!enabled()) return;
			String dim = destination.getRegistryKey().getValue().toString();
			progress(player.getUuid(), "dimension", dim, 1, dim);
			checkBiome(player, destination);
		});

		ModuleEvents.onJoin("bounty", p -> {
			if (!enabled()) return;
			String dim = p.getWorld().getRegistryKey().getValue().toString();
			progress(p.getUuid(), "dimension", dim, 1, dim);
			checkBiome(p, p.getServerWorld());
		});

		ModuleEvents.onQuit("bounty", p -> {
			try {
				BountyState.evict(p.getUuid());
				PENDING.remove(p.getUuid());
				LAST_BIOME.remove(p.getUuid());
			} catch (Exception ignored) {
				// 退出时清缓存失败不影响存档
			}
		});

		ServerTickEvents.END_SERVER_TICK.register(server -> {
			try {
				if (!enabled()) return;
				tickCounter++;
				biomeCounter++;
				boolean flush = tickCounter >= BountyConfig.progressFlushTicks();
				boolean biome = biomeCounter >= BountyConfig.biomeSampleTicks();
				for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
					track(p);
					if (biome) checkBiome(p, p.getServerWorld());
				}
				if (flush) {
					tickCounter = 0;
					settle(server);
				}
				if (biome) biomeCounter = 0;
			} catch (Exception e) {
				com.akira.redtale.economy.util.Logs.warnOnce("bounty.tick", "[bounty] tick 统计失败：{}", e.getMessage());
			}
		});
	}

	private static ServerPlayerEntity attacker(DamageSource source) {
		if (source == null) return null;
		var a = source.getAttacker();
		if (a instanceof ServerPlayerEntity sp) return sp;
		var owner = source.getSource();
		return owner instanceof ServerPlayerEntity sp2 ? sp2 : null;
	}

	private static boolean isLootContainer(net.minecraft.block.BlockState state) {
		var block = state.getBlock();
		if (block instanceof ChestBlock || block instanceof BarrelBlock || block instanceof ShulkerBoxBlock) return true;
		String id = Registries.BLOCK.getId(block).toString();
		return id.startsWith("lootr:");
	}

	/** 每 tick 累计位移；在线时长按 tick 数换算成分钟。 */
	private static void track(ServerPlayerEntity p) {
		Track t = PENDING.computeIfAbsent(p.getUuid(), k -> new Track());
		BlockPos pos = p.getBlockPos();
		if (t.hasLast) {
			double dx = pos.getX() - t.lastX;
			double dz = pos.getZ() - t.lastZ;
			double dist = Math.sqrt(dx * dx + dz * dz);
			// 传送会一次跳很远：>64 格视为瞬移，不计入行走距离
			if (dist > 0 && dist <= 64) t.distance += dist;
		}
		t.lastX = pos.getX();
		t.lastZ = pos.getZ();
		t.hasLast = true;
		t.ticks += 1;
	}

	/** 把缓冲的位移/时长落成进度（100 tick 一次）。 */
	private static void settle(net.minecraft.server.MinecraftServer server) {
		for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
			Track t = PENDING.get(p.getUuid());
			if (t == null) continue;
			int blocks = (int) Math.floor(t.distance);
			if (blocks > 0) {
				progress(p.getUuid(), "travel", "", blocks, "");
				t.distance -= blocks;
			}
			int minutes = (int) Math.floor(t.ticks / 1200.0);   // 20 tick = 1 秒，1200 tick = 1 分钟
			if (minutes > 0) {
				progress(p.getUuid(), "playtime", "", minutes, "");
				t.ticks -= minutes * 1200.0;
			}
		}
		BountyState.flushAll();
	}

	private static void checkBiome(ServerPlayerEntity p, ServerWorld world) {
		try {
			var key = world.getBiome(p.getBlockPos()).getKey();
			if (key.isEmpty()) return;
			String id = key.get().getValue().toString();
			String last = LAST_BIOME.get(p.getUuid());
			if (id.equals(last)) return;
			LAST_BIOME.put(p.getUuid(), id);
			progress(p.getUuid(), "biome", id, 1, id);
		} catch (Exception e) {
			com.akira.redtale.economy.util.Logs.warnOnce("bounty.biome", "[bounty] 群系采样失败：{}", e.getMessage());
		}
	}

	// ---------------------------------------------------------------- 给 mixin 用

	/** 合成取走成品（CraftingResultSlot.onTakeItem）。 */
	public static void onCraft(PlayerEntity player, ItemStack stack) {
		if (!(player instanceof ServerPlayerEntity)) return;
		progress(player.getUuid(), "craft",
				Registries.ITEM.getId(stack.getItem()).toString(), Math.max(1, stack.getCount()), "");
	}

	/** 钓到鱼（FishingBobberEntity.use，背包内 #minecraft:fishes 数量增量）。 */
	public static void onFish(ServerPlayerEntity player, int gained) {
		if (gained > 0) progress(player.getUuid(), "fish", "", gained, "");
	}

	/** 调试/管理：清空某人今天的悬赏。 */
	public static void reroll(UUID uuid) {
		BountyState st = daily(uuid);
		st.reset(TimeUtil.today(BountyConfig.resetHour()), pick(uuid, st.date + "-r", BountyConfig.slotCount()));
		st.save();
	}
}
