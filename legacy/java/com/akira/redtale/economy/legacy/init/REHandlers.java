package com.akira.redtale.economy.legacy.init;

import com.akira.redtale.economy.legacy.LegacyInit;
import com.akira.redtale.economy.legacy.screen.PackageChooseHandler;
import com.akira.redtale.economy.legacy.screen.PackageEditHandler;
import net.fabricmc.fabric.api.screenhandler.v1.ExtendedScreenHandlerType;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;

public class REHandlers {
    public static final ExtendedScreenHandlerType<PackageEditHandler> PACKAGE_EDIT_SCREEN =
            Registry.register(Registries.SCREEN_HANDLER, LegacyInit.id("package_edit"),
                    new ExtendedScreenHandlerType<>(
                            (syncId, inventory, buf) -> new PackageEditHandler(syncId, inventory, buf)
                    )
            );

    public static final ExtendedScreenHandlerType<PackageChooseHandler> PACKAGE_CHOOSE_SCREEN =
            Registry.register(Registries.SCREEN_HANDLER, LegacyInit.id("package_choose"),
                    new ExtendedScreenHandlerType<>(
                            (syncId, inventory, buf) -> {
                                // 🔥 关键日志：确认客户端是否收到了创建请求
                                System.err.println("🔥🔥 [REHandlers] FACTORY CALLED! syncId=" + syncId);
                                return new PackageChooseHandler(syncId, inventory, buf);
                            }
                    )
            );

    public static void register() {
        LegacyInit.LOGGER.info("✅ Registered LegacyInit screen handlers");
    }
}