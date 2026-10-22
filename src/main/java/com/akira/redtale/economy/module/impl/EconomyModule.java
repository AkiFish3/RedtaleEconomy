package com.akira.redtale.economy.module.impl;

import com.akira.redtale.economy.command.EconomyCommands;
import com.akira.redtale.economy.config.ConfigManager;
import com.akira.redtale.economy.economy.BalanceService;
import com.akira.redtale.economy.module.ModuleEvents;
import com.akira.redtale.economy.module.PayService;
import com.akira.redtale.economy.module.RedtaleModule;
import com.akira.redtale.economy.storage.PendingPay;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.server.command.ServerCommandSource;

/** 币值模块：BalanceProvider 选择 + {@code /bal} {@code /pay} + 离线转账补发。 */
public final class EconomyModule implements RedtaleModule {

	/** join 钩子只挂一次（onLoad 在热重载 / 关开模块时会重复跑）。 */
	private static boolean hooksInstalled;

	@Override
	public String id() {
		return "economy";
	}

	@Override
	public boolean defaultEnabled() {
		return true;
	}

	@Override
	public void onLoad(ConfigManager cfg) {
		BalanceService.init();
		// 离线转账挂账：加载期目录可能还是 redtale-data.pending，
		// world ready 后 rebindDataDir 会再解析一次（与 CheckinModule 的 stateDir 同机制）。
		PendingPay.resolve();
		installHooks();
	}

	@Override
	public void onReload(ConfigManager cfg) {
		// provider 选择可能被改；内部 provider 复用实例，不丢缓存
		BalanceService.init();
		// ★这里不要 resolve PendingPay★：它会清空内存再重读文件，
		// 热重载瞬间还没落盘的那一笔就没了。挂账目录只在加载期 / world ready 时解析。
	}

	@Override
	public void rebindDataDir() {
		// world ready：%world% 这时才可用（加载期可能落在 redtale-data.pending）。
		// 此刻还没有玩家在线，重读不会有未落盘的挂账。
		PendingPay.resolve();
	}

	@Override
	public void registerCommands(CommandDispatcher<ServerCommandSource> dispatcher) {
		EconomyCommands.register(dispatcher);
	}

	private static void installHooks() {
		if (hooksInstalled) return;
		hooksInstalled = true;
		// 上线第一刻补发离线转账（此时玩家的余额组件一定可写，Numismatic 也一样）
		ModuleEvents.onJoin("economy.pending-pay", p -> PayService.claim(p));
	}
}
