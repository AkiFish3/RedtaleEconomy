package com.akira.redtale.economy.legacy;

import com.akira.redtale.economy.legacy.bound.BoundItemsConfig;
import com.akira.redtale.economy.legacy.init.*;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LegacyInit implements ModInitializer {
	public static final String MOD_ID = "redtale_economy";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		LOGGER.info("[legacy] 旧模桥接初始化…");
		REItems.register();
		REEntities.register();
		REHandlers.register();
		RENetworking.register();
		BoundItemsConfig.load();

		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
				RECommands.register(dispatcher));

		LOGGER.info("[legacy] 旧模桥接初始化完成");
	}

	/** 统一 Identifier 入口（沿用旧模 Redtale.id 语义，命名空间已迁到 redtale_economy）。 */
	public static Identifier id(String path) {
		return new Identifier(MOD_ID, path);
	}
}