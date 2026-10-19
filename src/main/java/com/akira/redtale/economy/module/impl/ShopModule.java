package com.akira.redtale.economy.module.impl;

import com.akira.redtale.economy.config.ConfigManager;
import com.akira.redtale.economy.module.RedtaleModule;
import com.akira.redtale.economy.command.ShopAdminCommands;
import com.akira.redtale.economy.net.ShopNetworking;
import com.akira.redtale.economy.shop.ShopCatalog;
import com.akira.redtale.economy.shop.ShopTransactions;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.server.command.ServerCommandSource;

/**
 * 商店模块（R4 只接协议；商品读取/买卖/GUI 在 R7 补齐）。
 *
 * <p>关闭本模块 = 不注册任何商店通道，客户端拿不到一页数据。
 */
public final class ShopModule implements RedtaleModule {

	@Override
	public String id() {
		return "shop";
	}

	@Override
	public void defineConfig(ConfigManager cfg) {
		ShopCatalog.defineConfig(cfg);
	}

	@Override
	public void onLoad(ConfigManager cfg) {
		ShopNetworking.registerServer();
		ShopNetworking.setSource(ShopCatalog.create());
		ShopTransactions.init();
		ShopNetworking.setHandler(new ShopTransactions());
	}

	@Override
	public void onReload(ConfigManager cfg) {
		// pageSize / 限流参数每次请求现读；商品表与库存需要显式重读
		ShopCatalog.reload();
		ShopTransactions.reloadState();
	}

	@Override
	public void registerCommands(CommandDispatcher<ServerCommandSource> dispatcher) {
		ShopAdminCommands.register(dispatcher);
	}
}
