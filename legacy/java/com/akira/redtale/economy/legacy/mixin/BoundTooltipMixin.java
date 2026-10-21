package com.akira.redtale.economy.legacy.mixin;

import com.akira.redtale.economy.legacy.bound.BoundItemHandler;
import net.minecraft.client.item.TooltipContext;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

@Mixin(ItemStack.class)
public class BoundTooltipMixin {

    @Inject(method = "getTooltip", at = @At("RETURN"))
    private void appendOwnerTooltip(PlayerEntity player, TooltipContext context, CallbackInfoReturnable<List<Text>> cir) {
        List<Text> tooltip = cir.getReturnValue();
        ItemStack self = (ItemStack) (Object) this;
        BoundItemHandler.appendOwnerTooltip(self, tooltip);
    }
}