package com.akira.redtale.economy.legacy;

import com.akira.redtale.economy.legacy.entity.client.PackageRenderer;
import com.akira.redtale.economy.legacy.init.REEntities;
import com.akira.redtale.economy.legacy.init.REHandlers;
import com.akira.redtale.economy.legacy.init.RENetworking;
import com.akira.redtale.economy.legacy.screen.client.PackageChooseScreen;
import com.akira.redtale.economy.legacy.screen.client.PackageEditClientState;
import com.akira.redtale.economy.legacy.screen.client.PackageEditScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.fabricmc.fabric.api.client.screenhandler.v1.ScreenRegistry;
import net.minecraft.nbt.NbtCompound;

@Environment(EnvType.CLIENT)
public class LegacyClientInit implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        EntityRendererRegistry.register(REEntities.PACKAGE_ENTITY, PackageRenderer::new);
        ScreenRegistry.register(REHandlers.PACKAGE_EDIT_SCREEN, PackageEditScreen::new);
        ScreenRegistry.register(REHandlers.PACKAGE_CHOOSE_SCREEN, PackageChooseScreen::new);

        // ★用 ScreenRegistry 而不是 HandledScreens.register★：后者在 yarn 里 private，
        // 只靠 Fabric API 的 access widener 才能调 —— loom 缓存重建后全量编译会直接报
        // 「register 在 HandledScreens 中是 private 访问控制」。ScreenRegistry 是公开 API。
        // T71：编辑器状态（mode + 权重）不在槽位同步里，单独走一个 S2C。
        // ★先在网络线程读完 NBT，再切主线程应用★：PacketByteBuf 离开回调即失效。
        ClientPlayNetworking.registerGlobalReceiver(RENetworking.EDIT_STATE, (client, handler, buf, sender) -> {
            NbtCompound state = buf.readNbt();
            client.execute(() -> PackageEditClientState.apply(state));
        });
        // 退服清空：否则下次进服、打开编辑器前会短暂显示上一局的权重
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> PackageEditClientState.reset());

        // 创造栏条目由 REItems.register() 统一加（旧模在这里重复加过一次，会出两份）
        LegacyInit.LOGGER.info("[legacy] 客户端渲染器/Screen 注册完成");
    }
}