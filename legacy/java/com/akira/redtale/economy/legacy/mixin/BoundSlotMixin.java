package com.akira.redtale.economy.legacy.mixin;

import com.akira.redtale.economy.legacy.bound.BoundItemHandler;
import com.akira.redtale.economy.legacy.bound.BoundItemsConfig;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.slot.Slot;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Slot.class)
public class BoundSlotMixin {

    @Inject(method = "canTakeItems", at = @At("HEAD"), cancellable = true)
    private void onCanTakeItems(PlayerEntity player, CallbackInfoReturnable<Boolean> cir) {
        Slot self = (Slot) (Object) this;
        ItemStack stack = self.getStack();
        if (stack.isEmpty()) return;

        // 使用 isOwner 判断（内部已处理 OP 绕过）
        if (!BoundItemHandler.isOwner(stack, player)) {
            player.sendMessage(Text.translatable(BoundItemHandler.MSG_NOT_OWNER), true);
            cir.setReturnValue(false);
            return;
        }

        // 自动绑定（如果物品未绑定且属于可绑定物品）
        if (!BoundItemHandler.hasOwner(stack) && BoundItemsConfig.isBoundItem(stack)) {
            BoundItemHandler.bindToPlayer(stack, player);
        }
    }

    @Inject(method = "onTakeItem", at = @At("HEAD"), cancellable = true)
    private void onOnTakeItem(PlayerEntity player, ItemStack stack, CallbackInfo ci) {
        if (stack.isEmpty()) return;
        if (!BoundItemHandler.isOwner(stack, player)) {
            player.sendMessage(Text.translatable(BoundItemHandler.MSG_NOT_OWNER), true);
            ci.cancel();
        }
    }
}