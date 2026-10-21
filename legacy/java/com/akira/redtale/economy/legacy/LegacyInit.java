package com.akira.redtale.economy.legacy;

import com.akira.redtale.economy.legacy.bound.BoundItemsConfig;
import com.akira.redtale.economy.legacy.bound.LegacyExchange;
import com.akira.redtale.economy.legacy.init.*;
import com.akira.redtale.economy.legacy.item.ServerItems;
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
		ServerItems.register();
		REEntities.register();
		REHandlers.register();
		RENetworking.register();
		BoundItemsConfig.load();
		com.akira.redtale.economy.legacy.data.PackageConfig.load();
		LegacyExchange.register();
		// 开发期视觉夹具：源码在 legacy/devsrc/（不在源集内 ⇒ 发行 jar 不含这些类）。
		// 需要复测时把 legacy/devsrc 临时加进 main.java.srcDirs，下面按类名可选加载。
		invokeDev("com.akira.redtale.economy.legacy.dev.PackageShotHarness");
		invokeDev("com.akira.redtale.economy.legacy.dev.TooltipShotHarness");

		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
				RECommands.register(dispatcher));

		LOGGER.info("[legacy] 旧模桥接初始化完成");
	}

	/** 统一 Identifier 入口（沿用旧模 Redtale.id 语义，命名空间已迁到 redtale_economy）。 */
	public static Identifier id(String path) {
		return new Identifier(MOD_ID, path);
	}

	/**
	 * 可选启用开发期夹具：类不在 classpath（发行版）就静默跳过，存在则调其静态 {@code register()}。
	 * <p>捕获 {@link Throwable} 而不是 {@link Exception} —— 客户端夹具在服务端加载会抛
	 * {@code NoClassDefFoundError}（Error 而非 Exception）。
	 */
	private static void invokeDev(String className) {
		try {
			Class.forName(className).getMethod("register").invoke(null);
		} catch (ClassNotFoundException e) {
			// 发行版：legacy/devsrc 未参与编译，属正常路径
		} catch (Throwable t) {
			LOGGER.warn("[legacy] 夹具 {} 未启用：{}", className, t.toString());
		}
	}
}