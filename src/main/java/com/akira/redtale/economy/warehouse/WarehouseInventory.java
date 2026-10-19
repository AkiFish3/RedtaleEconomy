package com.akira.redtale.economy.warehouse;

import com.akira.redtale.economy.config.WarehouseConfig;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;

/**
 * 仓库的「一页视图」（R27）。
 *
 * <p>原版容器 GUI 一次只画 54 格，所以这里把整份 {@link WarehouseData} 压缩成
 * {@code rows × 9} 的窗口；{@link #setStack}/{@link #getStack} 都带页偏移，
 * 槽位索引对客户端就是 0..53，原版同步逻辑照常工作。
 */
public final class WarehouseInventory implements Inventory {

	private final WarehouseData data;
	private final int offset;
	private final int size;

	public WarehouseInventory(WarehouseData data, int page, int rows) {
		this.data = data;
		int pageSize = Math.max(9, rows * 9);
		int pages = Math.max(1, (int) Math.ceil(data.slots() / (double) pageSize));
		int p = Math.max(0, Math.min(page, pages - 1));
		this.offset = p * pageSize;
		this.size = Math.max(0, Math.min(pageSize, data.slots() - offset));
	}

	public WarehouseData data() {
		return data;
	}

	public int offset() {
		return offset;
	}

	public int page() {
		return offset / Math.max(9, WarehouseConfig.pageSize());
	}

	@Override
	public int size() {
		return size;
	}

	@Override
	public boolean isEmpty() {
		for (int i = 0; i < size; i++) {
			if (!data.get(offset + i).isEmpty()) return false;
		}
		return true;
	}

	@Override
	public ItemStack getStack(int slot) {
		if (slot < 0 || slot >= size) return ItemStack.EMPTY;
		return data.get(offset + slot);
	}

	@Override
	public ItemStack removeStack(int slot, int amount) {
		if (slot < 0 || slot >= size) return ItemStack.EMPTY;
		ItemStack cur = data.get(offset + slot);
		if (cur.isEmpty()) return ItemStack.EMPTY;
		ItemStack out = cur.split(Math.min(Math.max(amount, 0), cur.getCount()));
		if (cur.isEmpty()) data.set(offset + slot, ItemStack.EMPTY);
		else data.markDirty();
		return out;
	}

	@Override
	public ItemStack removeStack(int slot) {
		if (slot < 0 || slot >= size) return ItemStack.EMPTY;
		ItemStack cur = data.get(offset + slot);
		data.set(offset + slot, ItemStack.EMPTY);
		return cur;
	}

	@Override
	public void setStack(int slot, ItemStack stack) {
		if (slot < 0 || slot >= size) return;
		data.set(offset + slot, stack == null ? ItemStack.EMPTY : stack);
	}

	@Override
	public void markDirty() {
		data.markDirty();
	}

	@Override
	public boolean canPlayerUse(PlayerEntity player) {
		return player != null && !player.isRemoved();
	}

	@Override
	public void clear() {
		for (int i = 0; i < size; i++) data.set(offset + i, ItemStack.EMPTY);
	}
}
