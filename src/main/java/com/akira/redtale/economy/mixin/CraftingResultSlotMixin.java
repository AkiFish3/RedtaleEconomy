package com.akira.redtale.economy.mixin;

import com.akira.redtale.economy.module.impl.BountyModule;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.slot.CraftingResultSlot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 合成目标槽被取走时推进 R31 的 {@code craft} 悬赏。
 *
 * <p>Fabric API 1.20.1 没有合成事件，这里用最小侵入的 HEAD 注入；
 * 只读物品与玩家，不改任何原版行为（不 cancel、不修改参数）。
 */
@Mixin(CraftingResultSlot.class)
public class CraftingResultSlotMixin {

	@Inject(method = "onTakeItem", at = @At("HEAD"))
	private void redtaleEconomy$onTakeItem(PlayerEntity player, ItemStack stack, CallbackInfo ci) {
		if (player == null || stack == null || stack.isEmpty()) return;
		try {
			BountyModule.onCraft(player, stack);
		} catch (Exception ignored) {
			// 悬赏统计失败不能影响正常合成
		}
	}
}
