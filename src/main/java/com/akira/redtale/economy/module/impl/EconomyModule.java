package com.akira.redtale.economy.module.impl;

import com.akira.redtale.economy.command.EconomyCommands;
import com.akira.redtale.economy.config.ConfigManager;
import com.akira.redtale.economy.economy.BalanceService;
import com.akira.redtale.economy.module.RedtaleModule;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.server.command.ServerCommandSource;

/** 币值模块：BalanceProvider 选择 + {@code /bal} {@code /pay}。 */
public final class EconomyModule implements RedtaleModule {

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
	}

	@Override
	public void onReload(ConfigManager cfg) {
		// provider 选择可能被改；内部 provider 复用实例，不丢缓存
		BalanceService.init();
	}

	@Override
	public void registerCommands(CommandDispatcher<ServerCommandSource> dispatcher) {
		EconomyCommands.register(dispatcher);
	}
}
