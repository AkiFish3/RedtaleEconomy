package com.akira.redtale.economy.legacy.mixin;

import com.akira.redtale.economy.legacy.bound.BoundItemHandler;
import com.akira.redtale.economy.legacy.bound.BoundItemsConfig;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ItemEntity.class)
public abstract class BoundPickupMixin {

    @Shadow
    public abstract ItemStack getStack();

    @Inject(method = "onPlayerCollision", at = @At("HEAD"), cancellable = true)
    private void onPlayerCollision(PlayerEntity player, CallbackInfo ci) {
        if (player.getWorld().isClient()) return;

        ItemStack stack = this.getStack();
        if (stack.isEmpty()) return;

        // 归属验证
        if (!BoundItemHandler.isOwner(stack, player)) {
            player.sendMessage(Text.translatable(BoundItemHandler.MSG_NOT_OWNER), true);
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