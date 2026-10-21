package com.akira.redtale.economy.legacy.mixin;

import com.akira.redtale.economy.legacy.bound.BoundItemHandler;
import com.akira.redtale.economy.legacy.bound.BoundItemsConfig;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ItemEntity.class)
public abstract class BoundPickupMixin {

    @Shadow
    public abstract ItemStack getStack();

    /**
     * ★本方法每 tick 都会跑★（{@code ItemEntity.tick} 里对碰撞箱外扩 1 格的每个玩家调一次）
     * ⇒ 提示必须走 {@link BoundItemHandler#notifyNotOwner} 的 1 秒冷却，否则非主人站在有主绑定物上
     * 就是 20 条/秒 actionbar 刷屏（2026-10-22 实测 2 秒 40 条）。
     */
    @Inject(method = "onPlayerCollision", at = @At("HEAD"), cancellable = true)
    private void onPlayerCollision(PlayerEntity player, CallbackInfo ci) {
        if (player.getWorld().isClient()) return;

        ItemStack stack = this.getStack();
        if (stack.isEmpty()) return;

        // 归属验证
        if (!BoundItemHandler.isOwner(stack, player)) {
            BoundItemHandler.notifyNotOwner(player);
            ci.cancel();
            return;
        }

        // 绑定逻辑（仅当未绑定 + 配置为可绑定物品 + 开关打开）
        if (BoundItemsConfig.bindOnPickup()
                && !BoundItemHandler.hasOwner(stack) && BoundItemsConfig.isBoundItem(stack)) {
            BoundItemHandler.bindToPlayer(stack, player);
        }
    }
}