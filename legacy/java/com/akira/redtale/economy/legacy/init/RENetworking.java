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

    // 只保留真正在用的两个通道：选择物品（C2S）与关闭选择界面（C2S）。
    // 旧的 OPEN_EDIT_PACKAGE / OPEN_CHOOSE_PACKAGE 全仓零引用、服务端也从未 registerGlobalReceiver，
    // 属于"客户端发了服务端也没人收"的静默丢包陷阱，已删（T38 清理）。
    public static final Identifier SELECT_ITEM = LegacyInit.id("select_item");
    public static final Identifier CLOSE_CHOOSE_PACKAGE = LegacyInit.id("close_choose_package");

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
                    // ★归属/距离校验★：这两个包都是「任意玩家都能发」的 C2S。
                    // 不校验的话，一个改过的客户端可以凭实体 id 远程销毁别人的选择包裹（静默、无日志）。
                    if (!isAllowedToInteract(player, pkgEntity, "close_choose_package")) return;
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
                ItemStack usedPack = null;
                Hand usedHand = null;

                if (isPackage(mainHand)) {
                    usedPack = mainHand;
                    usedHand = Hand.MAIN_HAND;
                } else if (isPackage(offHand)) {
                    usedPack = offHand;
                    usedHand = Hand.OFF_HAND;
                }

                PackageData data = usedPack == null ? null : PackageData.fromNbt(usedPack.getNbt());
                // ★index 必须按「界面同一份过滤列表」解释★（见 PackageChooseHandler#extractItemsFromNbt）：
                // 旧写法用 data.items().get(index)，包裹 NBT 里只要有一个空 item 就会错位给错东西。
                java.util.List<ItemStack> choosable = PackageChooseHandler.extractItemsFromNbt(usedPack == null ? null : usedPack.getNbt());

                if (data == null || data.mode() != PackageData.PackageMode.CHOOSE ||
                        index < 0 || index >= choosable.size()) {
                    LegacyInit.LOGGER.warn("Invalid select_item request from player {}", player.getGameProfile().getName());
                    return;
                }

                net.minecraft.entity.Entity entity = player.getWorld().getEntityById(entityId);
                if (entity instanceof PackageEntity pkgEntity) {
                    if (!isAllowedToInteract(player, pkgEntity, "select_item")) return;
                    if (pkgEntity.isActivated()) {
                        LegacyInit.LOGGER.warn("Duplicate SELECT_ITEM packet ignored for entity {}", entityId);
                        return;
                    }
                    pkgEntity.setSelectedItem(new PackageData.PackageItem(choosable.get(index).copy(), 1));
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

    /** C2S 交互闸门：owner 一致 + 距离 ≤20 格（与 {@code PackageEntity.tick} 的 400.0 平方距离同口径）。 */
    private static boolean isAllowedToInteract(ServerPlayerEntity player, PackageEntity entity, String channel) {
        if (entity.getOwnerUuid() != null && !entity.getOwnerUuid().equals(player.getUuid())) {
            LegacyInit.LOGGER.warn("{} rejected: {} is not the owner of package entity {}",
                    channel, player.getGameProfile().getName(), entity.getId());
            return false;
        }
        if (player.squaredDistanceTo(entity) > 400.0) {
            LegacyInit.LOGGER.warn("{} rejected: {} too far from package entity {}",
                    channel, player.getGameProfile().getName(), entity.getId());
            return false;
        }
        return true;
    }

    public static boolean isPackage(ItemStack stack) {
        return stack.isOf(REItems.NORMAL_PACKAGE) ||
                stack.isOf(REItems.RARE_PACKAGE) ||
                stack.isOf(REItems.LEGENDARY_PACKAGE);
    }
}