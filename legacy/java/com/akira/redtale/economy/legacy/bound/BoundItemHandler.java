package com.akira.redtale.economy.legacy.bound;

import com.akira.redtale.economy.legacy.LegacyInit;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.List;

public class BoundItemHandler {
    private static final String OWNER_NAME_KEY = "BoundOwnerName";

    public static final String MSG_NOT_OWNER = "bound.error.not_owner";

    /**
     * 为物品绑定所有者（仅记录玩家名称）
     */
    public static void bindToPlayer(ItemStack stack, PlayerEntity player) {
        if (stack.isEmpty()) return;
        if (!BoundItemsConfig.isBoundItem(stack)) return;
        if (hasOwner(stack)) return;

        NbtCompound tag = stack.getOrCreateNbt();
        tag.putString(OWNER_NAME_KEY, player.getName().getString());
        LegacyInit.LOGGER.info("Bound item {} to player {}", stack.getItem().getTranslationKey(), player.getName().getString());
    }

    /**
     * 检查物品是否已绑定所有者
     */
    public static boolean hasOwner(ItemStack stack) {
        return stack.hasNbt() && stack.getNbt().contains(OWNER_NAME_KEY);
    }

    /**
     * 获取物品的所有者名称
     */
    public static String getOwnerName(ItemStack stack) {
        if (!hasOwner(stack)) return null;
        return stack.getNbt().getString(OWNER_NAME_KEY);
    }

    /**
     * 检查玩家是否为物品的所有者。
     * OP 玩家（权限等级 >= 4）无条件允许。
     */
    public static boolean isOwner(ItemStack stack, PlayerEntity player) {
        // 最直接的 OP 判定：权限等级 >= 4
        if (player.hasPermissionLevel(4)) {
            LegacyInit.LOGGER.info("OP bypass for player: {}", player.getName().getString());
            return true;
        }
        if (!hasOwner(stack)) {
            // 无主物品视为公共，任何人都可以操作
            return true;
        }
        String ownerName = getOwnerName(stack);
        boolean result = ownerName != null && ownerName.equals(player.getName().getString());
        LegacyInit.LOGGER.debug("isOwner result: {} for player {} on item owned by {}", result, player.getName().getString(), ownerName);
        return result;
    }

    /**
     * 修改物品显示名称，追加 “属于 <玩家名>”
     */
    public static Text getDisplayName(ItemStack stack) {
        Text originalName = stack.getName();
        if (!hasOwner(stack)) return originalName;
        String ownerName = getOwnerName(stack);
        if (ownerName == null) return originalName;
        MutableText boundSuffix = Text.literal(" §7(属于 " + ownerName + "§7)");
        return originalName.copy().append(boundSuffix);
    }

    /**
     * 在物品的 tooltip 中添加所有者信息
     */
    public static void appendOwnerTooltip(ItemStack stack, List<Text> tooltip) {
        if (!hasOwner(stack)) return;
        String ownerName = getOwnerName(stack);
        if (ownerName != null) {
            tooltip.add(Text.translatable("bound.tooltip.owner", ownerName).formatted(Formatting.GRAY));
        }
    }
}