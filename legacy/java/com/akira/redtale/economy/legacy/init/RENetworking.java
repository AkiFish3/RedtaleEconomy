package com.akira.redtale.economy.legacy.init;

import com.akira.redtale.economy.legacy.LegacyInit;
import com.akira.redtale.economy.legacy.data.PackageData;
import com.akira.redtale.economy.legacy.entity.PackageEntity;
import com.akira.redtale.economy.legacy.screen.PackageChooseHandler;
import com.akira.redtale.economy.legacy.screen.PackageEditHandler;
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
    /** T71：编辑器动作（C2S）。载荷 = varInt action + 动作参数。 */
    public static final Identifier EDIT_ACTION = LegacyInit.id("package_edit_action");
    /** T71：编辑器状态回发（S2C）。载荷 = 一个 NBT（mode / weights / maxWeight）。 */
    public static final Identifier EDIT_STATE = LegacyInit.id("package_edit_state");

    public static final int ACT_SET_MODE = 0;
    public static final int ACT_SET_WEIGHT = 1;
    public static final int ACT_CLEAR_SLOT = 2;
    public static final int ACT_SET_TIER = 3;
    public static final int ACT_REQ_STATE = 4;

    /** 客户端：发一个编辑器动作。{@code body} 里写参数（不含 action 本身）。 */
    public static void sendEditAction(int action, java.util.function.Consumer<PacketByteBuf> body) {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeVarInt(action);
        if (body != null) body.accept(buf);
        ClientPlayNetworking.send(EDIT_ACTION, buf);
    }

    /** 服务端：回发编辑器状态。 */
    public static void sendEditState(ServerPlayerEntity player, net.minecraft.nbt.NbtCompound state) {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeNbt(state);
        ServerPlayNetworking.send(player, EDIT_STATE, buf);
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
                net.minecraft.entity.Entity entity = player.getWorld().getEntityById(entityId);
                if (!(entity instanceof PackageEntity pkgEntity)) {
                    LegacyInit.LOGGER.warn("select_item rejected: entity {} not found or not a PackageEntity", entityId);
                    return;
                }
                if (!isAllowedToInteract(player, pkgEntity, "select_item")) return;

                // ★奖励真值取「实体上的 packageStack」而不是手牌★：
                // 界面是 HandledScreen，玩家在界面开着时可以随意挪自己的背包 —— 旧实现每次 select 都
                // 重新读手牌，只要把另一个内容更好的 CHOOSE 包裹换到手上，就能用 A 的界面领 B 的奖励，
                // 而且被扣掉（decrement）的也是 B。实体创建时就 `stack.copy()` 过，它才是这次交互的唯一依据。
                if (!pkgEntity.isChooseModeActive()) {
                    LegacyInit.LOGGER.warn("select_item rejected: entity {} is not in choose mode", entityId);
                    return;
                }
                if (pkgEntity.isActivated()) {
                    LegacyInit.LOGGER.warn("Duplicate SELECT_ITEM packet ignored for entity {}", entityId);
                    return;
                }

                ItemStack entityPack = pkgEntity.getPackageStack();
                PackageData data = entityPack.isEmpty() ? null : PackageData.fromNbt(entityPack.getNbt());
                if (data == null || data.mode() != PackageData.PackageMode.CHOOSE) {
                    LegacyInit.LOGGER.warn("select_item rejected: entity {} has no CHOOSE package data", entityId);
                    return;
                }
                // ★index 必须按「界面同一份过滤列表」解释★（见 PackageChooseHandler#extractItemsFromNbt）：
                // 旧写法用 data.items().get(index)，包裹 NBT 里只要有一个空 item 就会错位给错东西。
                java.util.List<ItemStack> choosable = PackageChooseHandler.extractItemsFromNbt(entityPack.getNbt());
                if (index < 0 || index >= choosable.size()) {
                    LegacyInit.LOGGER.warn("Invalid select_item request from player {} (index={}, size={})",
                            player.getGameProfile().getName(), index, choosable.size());
                    return;
                }

                // ★先扣件再发奖★：扣不到同款包裹就整单拒绝，避免「改个客户端就能白嫖」。
                // 不限定手牌：玩家可能在界面开着时把包裹拖进背包，只要背包里还有同一件就算数。
                if (!player.isCreative() && !consumeOnePackage(player, entityPack)) {
                    LegacyInit.LOGGER.warn("select_item rejected: {} has no matching package in inventory",
                            player.getGameProfile().getName());
                    return;
                }

                pkgEntity.setSelectedItem(new PackageData.PackageItem(choosable.get(index).copy(), 1));
                player.closeHandledScreen();
            });
        });

        // 3. 客户端 → 服务端：编辑器动作（T71 可视化编辑器）
        // ★先读完载荷再 server.execute★：PacketByteBuf 只在当前回调里有效，跨线程读会读到已释放的缓冲。
        // 身份闸门 = `player.currentScreenHandler` 必须就是编辑器本身 —— 这条天然挡住"改客户端驱动别人的界面"，
        // 也不需要额外传 syncId（原版 openHandledScreen 已经把 handler 与玩家绑定）。
        ServerPlayNetworking.registerGlobalReceiver(EDIT_ACTION, (server, player, handler, buf, responseSender) -> {
            int action = buf.readVarInt();
            int slot = -1;
            int value = -1;
            String tier = null;
            try {
                if (action == ACT_SET_WEIGHT) {
                    slot = buf.readVarInt();
                    value = buf.readVarInt();
                } else if (action == ACT_CLEAR_SLOT) {
                    slot = buf.readVarInt();
                } else if (action == ACT_SET_MODE) {
                    value = buf.readVarInt();
                } else if (action == ACT_SET_TIER) {
                    tier = buf.readString(64);
                }
            } catch (Throwable t) {
                LegacyInit.LOGGER.warn("package_edit_action 载荷解析失败（action={}）：{}", action, t.toString());
                return;
            }
            final int fSlot = slot;
            final int fValue = value;
            final String fTier = tier;
            final int fAction = action;
            server.execute(() -> {
                if (!(player.currentScreenHandler instanceof PackageEditHandler edit)) {
                    LegacyInit.LOGGER.warn("package_edit_action 丢弃：{} 当前界面不是包裹编辑器（action={}）",
                            player.getGameProfile().getName(), fAction);
                    return;
                }
                switch (fAction) {
                    case ACT_REQ_STATE -> { /* 只是要一份状态，下面统一回发 */ }
                    case ACT_SET_MODE -> edit.applySetMode(fValue);
                    case ACT_SET_WEIGHT -> edit.applySetWeight(fSlot, fValue);
                    case ACT_CLEAR_SLOT -> edit.applyClearSlot(fSlot);
                    case ACT_SET_TIER -> edit.applySetTier(fTier);
                    default -> LegacyInit.LOGGER.warn("package_edit_action 未知动作 {}（已忽略）", fAction);
                }
                // 无论成功与否都回发真值：客户端据此对齐，避免"界面显示改了、实际没改"
                edit.sendState();
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

    /** 从玩家身上扣掉一件与 {@code template} 同款（物品 + NBT）的包裹；扣不到返回 false。 */
    private static boolean consumeOnePackage(ServerPlayerEntity player, ItemStack template) {
        // 手持优先：绝大多数情况下包裹就在手上，省一次 41 格扫描。
        for (Hand hand : Hand.values()) {
            ItemStack held = player.getStackInHand(hand);
            if (ItemStack.canCombine(held, template)) {
                held.decrement(1);
                return true;
            }
        }
        PlayerInventory inventory = player.getInventory();
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack slot = inventory.getStack(i);
            if (ItemStack.canCombine(slot, template)) {
                slot.decrement(1);
                return true;
            }
        }
        return false;
    }

    /**
     * 是否包裹。★按类型判断，不按 id 白名单★：包裹现在是数据驱动的（3 系列 × 6 档 + surprise = 19 个 id），
     * 写死 id 列表的结果是「新加的档位右键没反应、还看不出哪里错」。
     */
    public static boolean isPackage(ItemStack stack) {
        return stack != null && !stack.isEmpty()
                && stack.getItem() instanceof com.akira.redtale.economy.legacy.item.PackageItem;
    }
}