package com.akira.redtale.economy.legacy.bound;

import com.akira.redtale.economy.legacy.LegacyInit;
import com.akira.redtale.economy.legacy.item.ServerItems;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.TypedActionResult;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 旧占位物品 1:1 兑换（A 队列 2026-10-21 13:22 请求）。
 *
 * <p>映射：{@code yuushya:sparking_flame → exchange_token_basic}、
 * {@code yuushya:floating_bloom → exchange_token_advanced}。
 *
 * <p>触发方式 = 主/副手拿着旧占位物品<strong>右键</strong>：整个手持堆原数量换成对应代币。
 * 选右键而不是上线自动换，是因为自动换无法回滚、也没法让玩家确认；
 * 右键是玩家主动行为，可重复、可预期，且不需要命令权限（{@code /redtale bound} 整棵子树是 OP4）。
 */
public final class LegacyExchange {

    /** 旧占位 id → 新代币 id（不带命名空间）。 */
    private static final Map<Identifier, String> LEGACY_TO_TOKEN = new LinkedHashMap<>();

    static {
        LEGACY_TO_TOKEN.put(new Identifier("yuushya", "sparking_flame"), "exchange_token_basic");
        LEGACY_TO_TOKEN.put(new Identifier("yuushya", "floating_bloom"), "exchange_token_advanced");
    }

    private LegacyExchange() {
    }

    public static void register() {
        UseItemCallback.EVENT.register((player, world, hand) -> {
            ItemStack held = player.getStackInHand(hand);
            if (held.isEmpty()) {
                return TypedActionResult.pass(held);
            }
            String tokenId = LEGACY_TO_TOKEN.get(Registries.ITEM.getId(held.getItem()));
            if (tokenId == null) {
                return TypedActionResult.pass(held);
            }
            if (world.isClient()) {
                // 客户端只回 success，真正改物品在服务端（客户端零信任）
                return TypedActionResult.success(held);
            }

            Item token = ServerItems.find(tokenId).orElse(null);
            if (token == null) {
                LegacyInit.LOGGER.warn("[legacy] 兑换目标 {} 未注册，跳过", tokenId);
                return TypedActionResult.pass(held);
            }

            int count = held.getCount();
            ItemStack converted = new ItemStack(token, count);
            player.setStackInHand(hand, converted);
            player.sendMessage(Text.translatable("command.redtale_economy.exchange.done", count, token.getName()), false);
            LegacyInit.LOGGER.info("[legacy] {} 兑换 {} x{}（{}）",
                    Registries.ITEM.getId(held.getItem()), tokenId, count, player.getGameProfile().getName());
            // fail = 已处理完毕，不再触发该物品自己的 use 逻辑
            return TypedActionResult.fail(converted);
        });
        LegacyInit.LOGGER.info("[legacy] 旧占位兑换已挂载：{}", LEGACY_TO_TOKEN);
    }

    /** 公开只读映射，供自检/文档用。 */
    public static Map<Identifier, String> mapping() {
        return Map.copyOf(LEGACY_TO_TOKEN);
    }
}
