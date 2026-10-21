package com.akira.redtale.economy.legacy.bound;

import com.akira.redtale.economy.legacy.LegacyInit;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtString;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

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
        applyOwnerLore(stack);
        LegacyInit.LOGGER.info("Bound item {} to player {}", stack.getItem().getTranslationKey(), player.getName().getString());
    }

    /**
     * 把归属人写进原版 {@code display.Lore}（**不注入 {@code ItemStack.getTooltip}**）。
     *
     * <p>为什么改成写 lore：旧实现用 {@code @Inject(at=RETURN)} 往 getTooltip 的返回列表里追加，
     * 而 Obscure-API 在同一个 {@code RETURN} 上 {@code cancellable=true} + {@code setReturnValue}
     * —— 排在它前面的注入被它重建的列表覆盖，排在它后面的被它的「提前 return」整段跳过，
     * 两个 priority 方向实测都拿不到这一行（`_LEGACY_VERIFY.md §3.7`）。
     * lore 是原版 NBT 数据，任何模组重建 tooltip 都会把它读回来，因此不存在抢注入点的问题。</p>
     *
     * <p>幂等：同一行已存在就不重复追加（重复绑定时不会堆出多条）。</p>
     */
    public static void applyOwnerLore(ItemStack stack) {
        String ownerName = getOwnerName(stack);
        if (ownerName == null || ownerName.isEmpty()) return;
        Text line = Text.translatable("bound.tooltip.owner", ownerName).formatted(Formatting.GRAY);
        String json = Text.Serializer.toJson(line);
        NbtCompound display = stack.getOrCreateSubNbt("display");
        NbtList lore = display.getList("Lore", NbtElement.STRING_TYPE);
        for (int i = 0; i < lore.size(); i++) {
            if (json.equals(lore.getString(i))) return;
        }
        NbtList updated = new NbtList();
        updated.addAll(lore);
        updated.add(NbtString.of(json));
        display.put("Lore", updated);
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
            LegacyInit.LOGGER.debug("OP bypass for player: {}", player.getName().getString());
            return true;
        }
        if (!hasOwner(stack)) {
            // 无主物品视为公共，任何人都可以操作
            return true;
        }
        String ownerName = getOwnerName(stack);
        boolean result = ownerName != null && ownerName.equals(player.getName().getString());
        // 旧存档的绑定物品只有 BoundOwnerName、没有 lore ⇒ 首次被交互时补一次（只服务端写，避免客户端 NBT 抖动）
        if (!player.getWorld().isClient()) {
            applyOwnerLore(stack);
        }
        LegacyInit.LOGGER.debug("isOwner result: {} for player {} on item owned by {}", result, player.getName().getString(), ownerName);
        return result;
    }

}
