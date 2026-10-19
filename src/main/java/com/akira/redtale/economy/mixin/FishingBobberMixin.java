package com.akira.redtale.economy.mixin;

import com.akira.redtale.economy.module.impl.BountyModule;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.FishingBobberEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 收杆时统计「钓到几条鱼」：用背包里 {@code #minecraft:fishes} 的数量差判断，
 * 比「只要收杆就算」准确（拉空杆、拉实体都不计数）。
 */
@Mixin(FishingBobberEntity.class)
public class FishingBobberMixin {

	@Unique
	private int redtaleEconomy$fishBefore;

	@Inject(method = "use", at = @At("HEAD"))
	private void redtaleEconomy$beforeUse(ItemStack usedItem, CallbackInfoReturnable<Integer> cir) {
		redtaleEconomy$fishBefore = redtaleEconomy$countFish();
	}

	@Inject(method = "use", at = @At("RETURN"))
	private void redtaleEconomy$afterUse(ItemStack usedItem, CallbackInfoReturnable<Integer> cir) {
		try {
			int gained = redtaleEconomy$countFish() - redtaleEconomy$fishBefore;
			PlayerEntity owner = ((FishingBobberEntity) (Object) this).getPlayerOwner();
			if (gained > 0 && owner instanceof ServerPlayerEntity sp) {
				BountyModule.onFish(sp, gained);
			}
		} catch (Exception ignored) {
			// 统计失败不影响钓鱼
		}
	}

	@Unique
	private int redtaleEconomy$countFish() {
		PlayerEntity owner = ((FishingBobberEntity) (Object) this).getPlayerOwner();
		if (owner == null) return 0;
		int n = 0;
		for (int i = 0; i < owner.getInventory().size(); i++) {
			ItemStack s = owner.getInventory().getStack(i);
			if (!s.isEmpty() && s.isIn(ItemTags.FISHES)) n += s.getCount();
		}
		return n;
	}
}
