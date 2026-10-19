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
		RedtaleEconomy.LOGGER.info("[{}] client initialized", RedtaleEconomy.MOD_ID);
	}
}
