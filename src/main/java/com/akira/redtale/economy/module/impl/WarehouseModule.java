package com.akira.redtale.economy.module.impl;

import com.akira.redtale.economy.RedtaleEconomy;
import com.akira.redtale.economy.command.WarehouseCommands;
import com.akira.redtale.economy.config.ConfigManager;
import com.akira.redtale.economy.config.StorageConfig;
import com.akira.redtale.economy.config.WarehouseConfig;
import com.akira.redtale.economy.economy.BalanceService;
import com.akira.redtale.economy.module.RedtaleModule;
import com.akira.redtale.economy.storage.Ledger;
import com.akira.redtale.economy.storage.TxnRunner;
import com.akira.redtale.economy.util.DataRoot;
import com.akira.redtale.economy.util.PathResolver;
import com.akira.redtale.economy.warehouse.WarehouseData;
import com.akira.redtale.economy.warehouse.WarehouseInventory;
import com.akira.redtale.economy.warehouse.WarehouseScreenHandler;
import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.SimpleNamedScreenHandlerFactory;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

/**
 * 玩家仓储模块（R27 + R28）。
 *
 * <p>数据落在 {@code <storage.root>/warehouse/<uuid>.json}（原子写）；GUI 用原版 9×6 容器，
 * 超过一页用 {@code /storage <页>} 翻页；扩容档位全部来自 {@code warehouse.json}。
 *
 * <p>对外只暴露三个静态方法给别的模块用：{@link #countFor}、{@link #takeFor}、{@link #give}，
 * 任务缴纳（R27）和邮件（R21）都走这里，避免各自读一份数据。
 */
public final class WarehouseModule implements RedtaleModule {

	private static volatile boolean hooksInstalled;
	private static int tickCounter;

	@Override
	public String id() {
		return "warehouse";
	}

	@Override
	public void defineConfig(ConfigManager cfg) {
		cfg.define(WarehouseConfig.FILE, WarehouseConfig.SCHEMA);
	}

	@Override
	public void onLoad(ConfigManager cfg) {
		resolveDir();
		installHooks();
	}

	@Override
	public void onReload(ConfigManager cfg) {
		// 先落盘再清缓存，避免热重载丢掉刚放进仓库的东西
		WarehouseData.flushAll();
		WarehouseData.clearCache();
		resolveDir();
	}

	@Override
	public void registerCommands(CommandDispatcher<ServerCommandSource> dispatcher) {
		WarehouseCommands.register(dispatcher, WarehouseConfig.openCommands());
	}

	/** R36：开服后数据目录重绑（{@code DataRoot.onWorldReady} 调用，%world% 这时才可用）。 */
	@Override
	public void rebindDataDir() {
		resolveDir();
	}

	private static void resolveDir() {
		try {
			PathResolver.Resolved r = DataRoot.resolve();
			Path dir = r.path().resolve("warehouse");
			Files.createDirectories(dir);
			WarehouseData.setDir(dir);
		} catch (Exception e) {
			WarehouseData.setDir(null);
			RedtaleEconomy.LOGGER.error("[warehouse] 目录不可用：{}", e.getMessage());
		}
	}

	/** tick 兜底落盘 + 退出落盘；只装一次（reload 不会重复注册）。 */
	private static void installHooks() {
		if (hooksInstalled) return;
		hooksInstalled = true;
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (++tickCounter < 100) return;   // 5 秒一次，只在有脏数据时才写盘
			tickCounter = 0;
			if (!com.akira.redtale.economy.module.ModuleManager.get().isEnabled("warehouse")) return;
			WarehouseData.flushAll();
		});
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			try {
				WarehouseData.evict(handler.getPlayer().getUuid());
			} catch (Exception e) {
				RedtaleEconomy.LOGGER.warn("[warehouse] 退出落盘失败：{}", e.getMessage());
			}
		});
	}

	public static boolean enabled() {
		return com.akira.redtale.economy.module.ModuleManager.get().isEnabled("warehouse")
				&& WarehouseConfig.enabled();
	}

	public static List<String> pagesHint() {
		return WarehouseConfig.openCommands();
	}

	/** 打开仓库某一页（0 起）。 */
	public static void open(ServerPlayerEntity player, int page) {
		if (player == null || !enabled()) return;
		try {
			WarehouseData data = WarehouseData.of(player.getUuid());
			int rows = WarehouseConfig.rows();
			int pageSize = rows * 9;
			int pages = Math.max(1, (int) Math.ceil(data.slots() / (double) pageSize));
			int p = Math.max(0, Math.min(page, pages - 1));
			WarehouseInventory inv = new WarehouseInventory(data, p, rows);
			Text title = Text.translatable("gui.redtale_economy.warehouse.title", p + 1, pages);
			player.openHandledScreen(new SimpleNamedScreenHandlerFactory(
					(syncId, playerInv, pl) -> new WarehouseScreenHandler(syncId, playerInv, inv, rows), title));
		} catch (Exception e) {
			RedtaleEconomy.LOGGER.error("[warehouse] 打开界面失败：{}", e.getMessage());
		}
	}

	// ---------------------------------------------------------------- 给其它模块用

	/** 仓库里某物品的数量；模块关闭或配置关掉「优先扣仓库」时返回 0。 */
	public static int countFor(UUID uuid, String itemId) {
		if (uuid == null || !enabled() || !WarehouseConfig.taskSubmitFromWarehouse()) return 0;
		try {
			return WarehouseData.of(uuid).count(itemId);
		} catch (Exception e) {
			return 0;
		}
	}

	/** 从仓库扣物品，返回实际扣掉的数量。 */
	public static int takeFor(UUID uuid, String itemId, int amount) {
		if (uuid == null || amount <= 0 || !enabled()) return 0;
		try {
			WarehouseData d = WarehouseData.of(uuid);
			int taken = d.take(itemId, amount);
			if (taken > 0) d.save();
			return taken;
		} catch (Exception e) {
			RedtaleEconomy.LOGGER.error("[warehouse] 扣除物品失败：{}", e.getMessage());
			return 0;
		}
	}

	/** 往仓库塞物品；返回没塞进去的部分（调用方负责还给玩家或掉落）。 */
	public static ItemStack give(UUID uuid, ItemStack stack) {
		if (uuid == null || stack == null || stack.isEmpty() || !enabled()) return stack;
		try {
			WarehouseData d = WarehouseData.of(uuid);
			ItemStack rest = d.insert(stack);
			d.save();
			return rest;
		} catch (Exception e) {
			RedtaleEconomy.LOGGER.error("[warehouse] 存入物品失败：{}", e.getMessage());
			return stack;
		}
	}

	/** 仓库是否还有空位（邮件/商店发货前判断用）。 */
	public static boolean hasRoom(UUID uuid) {
		if (!enabled()) return false;
		try {
			WarehouseData d = WarehouseData.of(uuid);
			for (int i = 0; i < d.slots(); i++) {
				if (d.get(i).isEmpty()) return true;
			}
			return false;
		} catch (Exception e) {
			return false;
		}
	}

	// ---------------------------------------------------------------- 扩容（R28）

	public enum UpgradeResult { OK, MAX, DISABLED, NO_MONEY, NO_PREMIUM, FAILED }

	public record UpgradeOutcome(UpgradeResult result, int slots, WarehouseConfig.Upgrade step) {
	}

	/** 扩容一档：先校验钱够不够，再扣（铜币 / 赞助点可同时要）。 */
	public static synchronized UpgradeOutcome upgrade(PlayerEntity player) {
		if (!(player instanceof ServerPlayerEntity sp)) {
			return new UpgradeOutcome(UpgradeResult.FAILED, 0, null);
		}
		if (!enabled() || !WarehouseConfig.upgradeEnabled()) {
			return new UpgradeOutcome(UpgradeResult.DISABLED, 0, null);
		}
		WarehouseData data = WarehouseData.of(sp.getUuid());
		WarehouseConfig.Upgrade next = WarehouseConfig.nextUpgrade(data.slots());
		if (next == null) return new UpgradeOutcome(UpgradeResult.MAX, data.slots(), null);

		UUID uuid = sp.getUuid();
		if (next.price() > 0 && !BalanceService.has(uuid, next.price())) {
			return new UpgradeOutcome(UpgradeResult.NO_MONEY, data.slots(), next);
		}
		if (next.premium() > 0) {
			if (!com.akira.redtale.economy.module.ModuleManager.get().isEnabled("premium")
					|| PremiumModule.get(uuid) < next.premium()) {
				return new UpgradeOutcome(UpgradeResult.NO_PREMIUM, data.slots(), next);
			}
		}
		// 校验通过后再扣：两个都扣成功才算数
		if (next.price() > 0 && !BalanceService.take(uuid, next.price(), Ledger.TYPE_WAREHOUSE,
				"slots:" + next.slots())) {
			return new UpgradeOutcome(UpgradeResult.NO_MONEY, data.slots(), next);
		}
		if (next.premium() > 0 && !PremiumModule.take(uuid, next.premium())) {
			// T34：退款走统一出口，失败会留补偿记录（以前是裸 add，失败无声）
			TxnRunner.refund(uuid, next.price(), Ledger.TYPE_WAREHOUSE, "refund:slots:" + next.slots(),
					new TxnRunner.Note(uuid, "warehouse", "slots:" + next.slots(), 0, next.price(),
							"premium take failed"));
			return new UpgradeOutcome(UpgradeResult.NO_PREMIUM, data.slots(), next);
		}
		boolean ok = data.expandTo(next.slots());
		data.save();
		if (!ok) {
			// T34 修：expandTo 返回 false 说明槽位没变（已达上限），以前钱扣了不退
			TxnRunner.refund(uuid, next.price(), Ledger.TYPE_WAREHOUSE, "refund:slots:" + next.slots(),
					new TxnRunner.Note(uuid, "warehouse", "slots:" + next.slots(), 0, next.price(),
							"expand failed"));
			TxnRunner.revert(TxnRunner.PREMIUM, uuid, next.premium(), Ledger.TYPE_PREMIUM,
					"refund:slots:" + next.slots());
			return new UpgradeOutcome(UpgradeResult.FAILED, data.slots(), next);
		}
		return new UpgradeOutcome(UpgradeResult.OK, data.slots(), next);
	}

	/** 命令层用：只读展示用。 */
	public static ItemStack iconFor(String itemId) {
		Item item = WarehouseData.resolve(itemId);
		return item == null ? ItemStack.EMPTY : new ItemStack(item);
	}
}
