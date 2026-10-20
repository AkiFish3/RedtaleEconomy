package com.akira.redtale.economy.module.impl;

import com.akira.redtale.economy.RedtaleEconomy;
import com.akira.redtale.economy.command.MarketCommands;
import com.akira.redtale.economy.config.ConfigManager;
import com.akira.redtale.economy.config.MarketConfig;
import com.akira.redtale.economy.market.MarketService;
import com.akira.redtale.economy.market.MarketStore;
import com.akira.redtale.economy.module.RedtaleModule;
import com.akira.redtale.economy.util.DataRoot;
import com.akira.redtale.economy.util.PathResolver;
import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.command.ServerCommandSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 玩家市场模块（R40，spec §43.5「统一挂单表」）。
 *
 * <p>只做三件事：把 {@code market/offers.json} 绑到数据根目录、每 60 秒扫一次过期单、
 * 注册命令与网络通道。业务细节全在 {@link MarketService}。
 *
 * <p>依赖 {@code warehouse}：成交物品默认进仓储（仓储关掉时自动退化为「背包 → 邮件」，
 * 由 {@link MarketService#deliver} 处理，所以依赖不是硬失败点）。
 */
public final class MarketModule implements RedtaleModule {

	private static volatile boolean hooksInstalled;
	private static int tickCounter;

	@Override
	public String id() {
		return "market";
	}

	@Override
	public List<String> requires() {
		return List.of("warehouse");
	}

	@Override
	public void defineConfig(ConfigManager cfg) {
		cfg.define(MarketConfig.FILE, MarketConfig.SCHEMA);
	}

	@Override
	public void onLoad(ConfigManager cfg) {
		resolveDir();
		installHooks();
	}

	@Override
	public void onReload(ConfigManager cfg) {
		resolveDir();
	}

	@Override
	public void rebindDataDir() {
		resolveDir();
	}

	@Override
	public void registerCommands(CommandDispatcher<ServerCommandSource> dispatcher) {
		MarketCommands.register(dispatcher);
	}

	private static void resolveDir() {
		try {
			PathResolver.Resolved r = DataRoot.resolve();
			Path dir = r.path().resolve("market");
			Files.createDirectories(dir);
			MarketStore.init(dir);
			RedtaleEconomy.LOGGER.info("[market] 挂单表：{}（{} 条）", dir.resolve("offers.json"),
					MarketStore.all().size());
		} catch (Exception e) {
			MarketStore.init(null);
			RedtaleEconomy.LOGGER.error("[market] 目录不可用：{}", e.getMessage());
		}
	}

	/** 每 60 秒扫一次过期；顺带每 30 分钟清一次 7 天前的终态条目。 */
	private static void installHooks() {
		if (hooksInstalled) return;
		hooksInstalled = true;
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			try {
				if (!enabled()) return;
				tickCounter++;
				if (tickCounter % 1200 != 0) return;
				int expired = MarketService.sweep(server);
				if (expired > 0) {
					RedtaleEconomy.LOGGER.info("[market] 到期下架 {} 条", expired);
				}
				if (tickCounter % 36_000 == 0) {
					MarketStore.prune(System.currentTimeMillis(), 7L * 24 * 3600_000L);
				}
			} catch (Exception e) {
				com.akira.redtale.economy.util.Logs.warnOnce("market.sweep",
						"[market] 过期扫描失败：{}", e.getMessage());
			}
		});
	}

	public static boolean enabled() {
		return com.akira.redtale.economy.module.ModuleManager.get().isEnabled("market")
				&& MarketConfig.enabled();
	}

	public static int openCount() {
		return MarketStore.open().size();
	}
}
