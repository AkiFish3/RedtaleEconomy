package com.akira.redtale.economy.legacy.init;

import com.akira.redtale.economy.legacy.LegacyInit;
import com.akira.redtale.economy.legacy.data.PackageData;
import com.akira.redtale.economy.legacy.entity.PackageEntity;
import com.akira.redtale.economy.legacy.screen.PackageChooseHandler;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.screenhandler.v1.ExtendedScreenHandlerFactory;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.Nullable;

public class RENetworking {

    public static final Identifier OPEN_EDIT_PACKAGE = LegacyInit.id("open_edit_package");
    public static final Identifier OPEN_CHOOSE_PACKAGE = LegacyInit.id("open_choose_package");
    public static final Identifier SELECT_ITEM = LegacyInit.id("select_item");
    public static final Identifier CLOSE_CHOOSE_PACKAGE = LegacyInit.id("close_choose_package");

    public static void sendOpenChoosePackagePacket(ServerPlayerEntity player, ItemStack packageStack) {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeItemStack(packageStack);
        ServerPlayNetworking.send(player, OPEN_CHOOSE_PACKAGE, buf);
    }

    public static void sendSelectItemPacket(int index, int entityId) {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeInt(index);
        buf.writeInt(entityId);
        ClientPlayNetworking.send(SELECT_ITEM, buf);
    }

    public static void sendCloseChoosePackagePacket(int entityId) {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeInt(entityId);
        ClientPlayNetworking.send(CLOSE_CHOOSE_PACKAGE, buf);
    }

    public static void register() {
        // 1. 客户端 → 服务端：关闭选择界面，立即销毁实体
        ServerPlayNetworking.registerGlobalReceiver(CLOSE_CHOOSE_PACKAGE, (server, player, handler, buf, responseSender) -> {
            int entityId = buf.readInt();
            server.execute(() -> {
                net.minecraft.entity.Entity entity = player.getWorld().getEntityById(entityId);
                if (entity instanceof PackageEntity pkgEntity) {
                    // 如果实体还没有被激活（即未选择物品），立即销毁
                    if (!pkgEntity.isActivated()) {
                        pkgEntity.destroyImmediately();
                        LegacyInit.LOGGER.info("Destroyed CHOOSE mode entity {} because player closed screen", entityId);
                    }
                }
            });
        });

        // 2. 客户端 → 服务端：选择物品
        ServerPlayNetworking.registerGlobalReceiver(SELECT_ITEM, (server, player, handler, buf, responseSender) -> {
            int index = buf.readInt();
            int entityId = buf.readInt();
            server.execute(() -> {
                ItemStack mainHand = player.getStackInHand(Hand.MAIN_HAND);
                ItemStack offHand = player.getStackInHand(Hand.OFF_HAND);
                PackageData data = null;
                Hand usedHand = null;

                if (isPackage(mainHand)) {
                    data = PackageData.fromNbt(mainHand.getNbt());
                    usedHand = Hand.MAIN_HAND;
                } else if (isPackage(offHand)) {
                    data = PackageData.fromNbt(offHand.getNbt());
                    usedHand = Hand.OFF_HAND;
                }

                if (data == null || data.mode() != PackageData.PackageMode.CHOOSE ||
                        index < 0 || index >= data.items().size()) {
                    LegacyInit.LOGGER.warn("Invalid select_item request from player {}", player.getGameProfile().getName());
                    return;
                }

                net.minecraft.entity.Entity entity = player.getWorld().getEntityById(entityId);
                if (entity instanceof PackageEntity pkgEntity) {
                    if (pkgEntity.isActivated()) {
                        LegacyInit.LOGGER.warn("Duplicate SELECT_ITEM packet ignored for entity {}", entityId);
                        return;
                    }
                    pkgEntity.setSelectedItem(data.items().get(index));
                } else {
                    LegacyInit.LOGGER.warn("Entity {} not found or not a PackageEntity", entityId);
                    return;
                }

                player.closeHandledScreen();

                if (usedHand != null && !player.isCreative()) {
                    player.getStackInHand(usedHand).decrement(1);
                }
            });
        });

        LegacyInit.LOGGER.info("✅ Registered LegacyInit networking");
    }

    public static boolean isPackage(ItemStack stack) {
        return stack.isOf(REItems.NORMAL_PACKAGE) ||
                stack.isOf(REItems.RARE_PACKAGE) ||
                stack.isOf(REItems.LEGENDARY_PACKAGE);
    }
}