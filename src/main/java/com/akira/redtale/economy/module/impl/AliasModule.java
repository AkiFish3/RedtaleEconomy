package com.akira.redtale.economy.module.impl;

import com.akira.redtale.economy.command.alias.AliasRegistry;
import com.akira.redtale.economy.config.ConfigManager;
import com.akira.redtale.economy.module.RedtaleModule;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.server.command.ServerCommandSource;

/**
 * 命令别名模块（spec v7.2）。
 *
 * <p>必须<b>最后注册</b>：它的 registerCommands 依赖其它模块已把命令挂到 dispatcher 上。
 * 别名属于注册期资源，改 commands.json 后需要重启才生效（/redtale reload 不会重挂命令）。
 */
public final class AliasModule implements RedtaleModule {

	@Override
	public String id() {
		return "aliases";
	}

	@Override
	public void defineConfig(ConfigManager cfg) {
		cfg.define(AliasRegistry.FILE, AliasRegistry.SCHEMA);
	}

	@Override
	public void registerCommands(CommandDispatcher<ServerCommandSource> dispatcher) {
		AliasRegistry.registerAll(dispatcher);
	}
}
