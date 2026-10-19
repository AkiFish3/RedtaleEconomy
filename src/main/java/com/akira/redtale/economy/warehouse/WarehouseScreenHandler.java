package com.akira.redtale.economy.warehouse;

import com.akira.redtale.economy.config.WarehouseConfig;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.screen.slot.Slot;

/**
 * 仓库容器（R27）。
 *
 * <p>客户端用原版的 {@link ScreenHandlerType#GENERIC_9X6} 打开（{@code GenericContainerScreen}），
 * 所以<b>不需要任何自定义界面代码</b>，拖拽 / Shift 点击 / 分堆都是原版行为；
 * 服务端这份 handler 只负责两件事：把槽位映射到当前页、按 {@code blockItem} 拦截潜影盒。
 *
 * <p>槽位索引必须与客户端一致：0..(rows*9-1) 仓库区，(rows*9)..(rows*9+26) 主背包，最后 9 格快捷栏。
 */
public final class WarehouseScreenHandler extends ScreenHandler {

	private final WarehouseInventory inventory;
	private final int rows;

	public WarehouseScreenHandler(int syncId, PlayerInventory playerInventory, WarehouseInventory inventory, int rows) {
		// ★必须按行数选类型★ 客户端是按这个 type 自己 new 原版 handler 的，
		// 类型与 rows 不匹配 ⇒ 两边槽位数不同 ⇒ 直接断开连接
		super(typeFor(rows), syncId);
		this.inventory = inventory;
		this.rows = Math.max(1, Math.min(6, rows));

		int containerSize = this.rows * 9;
		for (int index = 0; index < containerSize; index++) {
			int col = index % 9;
			int row = index / 9;
			addSlot(new WarehouseSlot(inventory, index, 8 + col * 18, 18 + row * 18));
		}
		for (int row = 0; row < 3; row++) {
			for (int col = 0; col < 9; col++) {
				addSlot(new Slot(playerInventory, col + row * 9 + 9,
						8 + col * 18, 18 + (row + this.rows) * 18 + 14));
			}
		}
		for (int col = 0; col < 9; col++) {
			addSlot(new Slot(playerInventory, col, 8 + col * 18, 18 + this.rows * 18 + 14 + 4 + 54));
		}
	}

	private static ScreenHandlerType<?> typeFor(int rows) {
		return switch (Math.max(1, Math.min(6, rows))) {
			case 1 -> ScreenHandlerType.GENERIC_9X1;
			case 2 -> ScreenHandlerType.GENERIC_9X2;
			case 3 -> ScreenHandlerType.GENERIC_9X3;
			case 4 -> ScreenHandlerType.GENERIC_9X4;
			case 5 -> ScreenHandlerType.GENERIC_9X5;
			default -> ScreenHandlerType.GENERIC_9X6;
		};
	}

	public int containerSize() {
		return rows * 9;
	}

	public int page() {
		return inventory.page();
	}

	@Override
	public ItemStack quickMove(PlayerEntity player, int index) {
		ItemStack original = ItemStack.EMPTY;
		Slot slot = this.slots.get(index);
		if (slot == null || !slot.hasStack()) return ItemStack.EMPTY;
		ItemStack stack = slot.getStack();
		original = stack.copy();
		int container = containerSize();
		if (index < container) {
			if (!this.insertItem(stack, container, this.slots.size(), true)) return ItemStack.EMPTY;
		} else {
			if (!this.insertItem(stack, 0, container, false)) return ItemStack.EMPTY;
		}
		if (stack.isEmpty()) slot.setStack(ItemStack.EMPTY);
		else slot.markDirty();
		return original;
	}

	@Override
	public boolean canUse(PlayerEntity player) {
		return inventory.canPlayerUse(player);
	}

	@Override
	public void onClosed(PlayerEntity player) {
		super.onClosed(player);
		// 关界面立刻落盘：仓库不是高频写入，没必要等 tick 兜底
		inventory.data().save();
	}

	/** 仓库槽位：按 {@code blockItem} 拦截潜影盒，其余同原版。 */
	private static final class WarehouseSlot extends Slot {

		WarehouseSlot(Inventory inventory, int index, int x, int y) {
			super(inventory, index, x, y);
		}

		@Override
		public boolean canInsert(ItemStack stack) {
			if (stack.isEmpty()) return true;
			if (!WarehouseConfig.blockItem()) return true;
			return !(stack.getItem() instanceof BlockItem bi && bi.getBlock() instanceof ShulkerBoxBlock);
		}
	}
}
