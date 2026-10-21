package com.akira.redtale.economy;

import com.akira.redtale.economy.client.ShopClientNetworking;
import com.akira.redtale.economy.client.config.ConfigClientNetworking;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/**
 * 客户端入口。R1 阶段只做初始化占位，
 * 后续 Screen / 网络接收器 在此注册。
 */
@Environment(EnvType.CLIENT)
public class RedtaleEconomyClient implements ClientModInitializer {

	@Override
	public void onInitializeClient() {
		ShopClientNetworking.register();
		ConfigClientNetworking.register();
		com.akira.redtale.economy.client.tasks.TaskClientNetworking.register();
		com.akira.redtale.economy.client.checkin.CheckinClientNetworking.register();
		com.akira.redtale.economy.client.ui.ClientKeybinds.register();
		com.akira.redtale.economy.client.ui.UiBootstrap.register();
		com.akira.redtale.economy.client.bounty.BountyClientNetworking.register();
		com.akira.redtale.economy.client.ui.hub.BoardClientNetworking.register();
		com.akira.redtale.economy.client.ui.hub.LedgerClientNetworking.register();
		com.akira.redtale.economy.client.ui.hub.AdminClientNetworking.register();
		com.akira.redtale.economy.client.ui.hub.ModulesClientNetworking.register();
		com.akira.redtale.economy.client.pass.PassClientNetworking.register();
		com.akira.redtale.economy.client.guild.GuildClientNetworking.register();
		com.akira.redtale.economy.client.balance.BalanceClientNetworking.register();
		com.akira.redtale.economy.client.title.TitlesClientNetworking.register();
		com.akira.redtale.economy.client.warehouse.WarehouseClientNetworking.register();
		com.akira.redtale.economy.client.ui.hub.HubClientNetworking.register();
		com.akira.redtale.economy.client.rank.RankClientNetworking.register();
		com.akira.redtale.economy.client.market.MarketClientNetworking.register();
		com.akira.redtale.economy.client.dev.UiShots.register();
		RedtaleEconomy.LOGGER.info("[{}] client initialized", RedtaleEconomy.MOD_ID);
	}
}
