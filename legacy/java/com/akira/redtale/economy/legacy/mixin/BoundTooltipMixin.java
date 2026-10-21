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

/**
 * 绑定物显示归属人。
 *
 * <p>与 Obscure-API / extraspellattributes 同场（两者都注入 {@code ItemStack.getTooltip}）时的兼容点：
 * <ul>
 *   <li>Obscure-API 在 {@code RETURN} 上 {@code cancellable=true}，可能把返回值换成别的列表；</li>
 *   <li>extraspellattributes 用 {@code ModifyReturnValue(TAIL)} 包返回值；</li>
 * </ul>
 * 因此这里不假设返回列表可变，也不原地强改：由 {@link BoundItemHandler#withOwnerTooltip} 返回
 * 「可能新建的」列表，只有引用变化时才回写 return value。</p>
 */
@Mixin(ItemStack.class)
public class BoundTooltipMixin {

    @Inject(method = "getTooltip", at = @At("RETURN"), cancellable = true)
    private void appendOwnerTooltip(PlayerEntity player, TooltipContext context, CallbackInfoReturnable<List<Text>> cir) {
        ItemStack self = (ItemStack) (Object) this;
        List<Text> before = cir.getReturnValue();
        List<Text> after = BoundItemHandler.withOwnerTooltip(self, before);
        if (after != before) {
            cir.setReturnValue(after);
        }
    }
}
