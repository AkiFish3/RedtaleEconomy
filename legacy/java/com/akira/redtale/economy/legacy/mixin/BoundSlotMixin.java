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

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 绑定物槽位拦截：非主人不许从槽里拿走。
 *
 * <p>相对旧模修掉 3 个缺陷（T39）：
 * <ol>
 *   <li>{@code canTakeItems} 每次点击 / 批量转移都会被调用，旧代码每次都发 actionbar ⇒ 刷屏。
 *       改为「同一玩家 1 秒内只提示一次」。</li>
 *   <li>客户端也会跑 {@code canTakeItems}，旧代码在客户端本地伪造提示 ⇒ 先判 {@code isClient()} 直接放行。</li>
 *   <li>旧代码 {@code canTakeItems} 与 {@code onTakeItem} 各提示一次 ⇒ 双击。只保留一处提示。</li>
 * </ol>
 */
@Mixin(Slot.class)
public class BoundSlotMixin {

	private static final long MESSAGE_COOLDOWN_MS = 1000L;
	private static final Map<UUID, Long> LAST_MESSAGE = new ConcurrentHashMap<>();

	@Inject(method = "canTakeItems", at = @At("HEAD"), cancellable = true)
	private void onCanTakeItems(PlayerEntity player, CallbackInfoReturnable<Boolean> cir) {
		// 客户端不参与归属判定（判定与提示都只在服务端做，避免本地伪造提示）
		if (player == null || player.getWorld().isClient()) {
			return;
		}

		Slot self = (Slot) (Object) this;
		ItemStack stack = self.getStack();
		if (stack.isEmpty()) {
			return;
		}

		if (BoundItemsConfig.blockNonOwnerInSlot() && !BoundItemHandler.isOwner(stack, player)) {
			notifyNotOwner(player);
			cir.setReturnValue(false);
			return;
		}

		// 首次拿走时自动绑定（仅在配置为可绑定物品且开关打开时）
		if (BoundItemsConfig.bindOnPickup()
				&& !BoundItemHandler.hasOwner(stack) && BoundItemsConfig.isBoundItem(stack)) {
			BoundItemHandler.bindToPlayer(stack, player);
		}
	}

	/** 旧模在这里再提示一次 ⇒ 与 canTakeItems 重复；vanilla 只有 canTake 通过后才会调它。 */
	@Inject(method = "onTakeItem", at = @At("HEAD"), cancellable = true)
	private void onOnTakeItem(PlayerEntity player, ItemStack stack, CallbackInfo ci) {
		if (player == null || player.getWorld().isClient() || stack.isEmpty()) {
			return;
		}
		if (!BoundItemHandler.isOwner(stack, player)) {
			ci.cancel();
		}
	}

	private static void notifyNotOwner(PlayerEntity player) {
		long now = System.currentTimeMillis();
		Long last = LAST_MESSAGE.get(player.getUuid());
		if (last != null && now - last < MESSAGE_COOLDOWN_MS) {
			return;
		}
		LAST_MESSAGE.put(player.getUuid(), now);
		player.sendMessage(Text.translatable(BoundItemHandler.MSG_NOT_OWNER), true);
	}
}
