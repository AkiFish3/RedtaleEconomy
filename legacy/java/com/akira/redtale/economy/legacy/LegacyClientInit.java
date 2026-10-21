package com.akira.redtale.economy.legacy;

import com.akira.redtale.economy.legacy.entity.client.PackageRenderer;
import com.akira.redtale.economy.legacy.init.REEntities;
import com.akira.redtale.economy.legacy.init.REHandlers;
import com.akira.redtale.economy.legacy.screen.client.PackageChooseScreen;
import com.akira.redtale.economy.legacy.screen.client.PackageEditScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.minecraft.client.gui.screen.ingame.HandledScreens;

@Environment(EnvType.CLIENT)
public class LegacyClientInit implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        EntityRendererRegistry.register(REEntities.PACKAGE_ENTITY, PackageRenderer::new);
        HandledScreens.register(REHandlers.PACKAGE_EDIT_SCREEN, PackageEditScreen::new);
        HandledScreens.register(REHandlers.PACKAGE_CHOOSE_SCREEN, PackageChooseScreen::new);
        // 创造栏条目由 REItems.register() 统一加（旧模在这里重复加过一次，会出两份）
        LegacyInit.LOGGER.info("[legacy] 客户端渲染器/Screen 注册完成");
    }
}