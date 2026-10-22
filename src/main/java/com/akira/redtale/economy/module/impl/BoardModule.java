package com.akira.redtale.economy.module.impl;

import com.akira.redtale.economy.RedtaleEconomy;
import com.akira.redtale.economy.board.BoardStore;
import com.akira.redtale.economy.command.BoardCommands;
import com.akira.redtale.economy.config.BoardConfig;
import com.akira.redtale.economy.config.ConfigManager;
import com.akira.redtale.economy.config.StorageConfig;
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
 * 玩家委托板（R30）：发单扣「赏金 + 手续费」、接单校验、交付即销毁物品、超时自动撤销退赏金。
 */
public final class BoardModule implements RedtaleModule {

	private static Path dir;
	private static volatile boolean hooksInstalled;
	private static int tick;

	@Override
	public String id() {
		return "board";
	}

	@Override
	public void defineConfig(ConfigManager cfg) {
		cfg.define(BoardConfig.FILE, BoardConfig.SCHEMA);
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
	public void registerCommands(CommandDispatcher<ServerCommandSource> dispatcher) {
		BoardCommands.register(dispatcher);
	}

	/** R36：开服后数据目录重绑（{@code DataRoot.onWorldReady} 调用，%world% 这时才可用）。 */
	@Override
	public void rebindDataDir() {
		resolveDir();
	}

	private static void resolveDir() {
		try {
			PathResolver.Resolved r = DataRoot.resolve();
			Path d = r.path().resolve("board");
			Files.createDirectories(d);
			dir = d;
			BoardStore.init(d);
		} catch (Exception e) {
			dir = null;
			BoardStore.init(null);
			RedtaleEconomy.LOGGER.error("[board] 目录不可用：{}", e.getMessage());
		}
	}

	public static boolean enabled() {
		return com.akira.redtale.economy.module.ModuleManager.get().isEnabled("board")
				&& BoardConfig.enabled();
	}

	public static String newId() {
		return Long.toString(System.currentTimeMillis() % 1_000_000L, 36).toUpperCase()
				+ Integer.toString((int) (Math.random() * 1296), 36).toUpperCase();
	}

	/** 超时扫描：每 30 秒一次，撤销过期未接单的委托并把赏金退回发布者（手续费不退）。 */
	private static void installHooks() {
		if (hooksInstalled) return;
		hooksInstalled = true;
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (!enabled()) return;
			if (++tick < 600) return;
			tick = 0;
			try {
				expireNow();
			} catch (Exception e) {
				RedtaleEconomy.LOGGER.warn("[board] 超时扫描失败：{}", e.getMessage());
			}
		});
	}

	public static int expireNow() {
		long now = System.currentTimeMillis();
		int n = 0;
		List<BoardStore.Entry> list = BoardStore.all();
		for (BoardStore.Entry e : list) {
			if (!BoardStore.Status.OPEN.name().equals(e.status)) continue;
			if (e.expiresAt <= 0 || e.expiresAt > now) continue;
			// ★R129：先落盘「已撤销」再退钱★ 原来「退钱 → save()」，保存失败时条目重启后还是 OPEN，
			// 每次重启再退一次 = 反复领赏金。
			String prev = e.status;
			e.status = BoardStore.Status.CANCELLED.name();
			if (!BoardStore.saveChecked()) {
				e.status = prev;
				RedtaleEconomy.LOGGER.error("[board] 超时撤销落盘失败，跳过本条（不退款）：{}", e.id);
				continue;
			}
			try {
				com.akira.redtale.economy.economy.BalanceService.add(java.util.UUID.fromString(e.poster),
						e.bounty, com.akira.redtale.economy.storage.Ledger.TYPE_BOARD,
						"board:expire:" + e.id);
			} catch (Throwable t) {
				// 退款没成功 ⇒ 把条目放回 OPEN，下一轮扫描再试，不能让赏金消失
				e.status = prev;
				BoardStore.saveChecked();
				RedtaleEconomy.LOGGER.error("[board] 超时退款失败，已恢复挂单：{}", t.toString(), t);
				continue;
			}
			n++;
		}
		if (n > 0) {
			RedtaleEconomy.LOGGER.info("[board] {} 条委托超时撤销，赏金已退回", n);
		}
		return n;
	}
}
