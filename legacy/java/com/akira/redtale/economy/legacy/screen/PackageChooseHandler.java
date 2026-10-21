package com.akira.redtale.economy.legacy.screen;

import com.akira.redtale.economy.legacy.init.REHandlers;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.util.collection.DefaultedList;

public class PackageChooseHandler extends ScreenHandler {
    private final Inventory packageInventory;
    public final int rows;
    public final int totalPackageSlots;
    private final int actualItemCount;
    private final int entityId;

    // 客户端使用的构造函数（从 buf 读取）
    public PackageChooseHandler(int syncId, PlayerInventory playerInventory, PacketByteBuf buf) {
        super(REHandlers.PACKAGE_CHOOSE_SCREEN, syncId);
        // 读取实体ID
        this.entityId = (buf != null && buf.isReadable()) ? buf.readVarInt() : -1;
        // 读取卡包NBT
        NbtCompound nbt = (buf != null && buf.isReadable()) ? buf.readNbt() : null;
        DefaultedList<ItemStack> packageItems = extractItemsFromNbt(nbt);
        this.actualItemCount = packageItems.size();
        this.rows = (this.actualItemCount == 0) ? 1 : (int) Math.ceil(this.actualItemCount / 9.0);
        this.totalPackageSlots = this.rows * 9;
        this.packageInventory = new SimpleInventoryWrapper(packageItems, this.totalPackageSlots);
        addPackageSlots();
        addPlayerInventorySlots(playerInventory);
    }

    // 服务端使用的构造函数（直接传数据）
    public PackageChooseHandler(int syncId, PlayerInventory playerInventory, int entityId, NbtCompound nbt) {
        super(REHandlers.PACKAGE_CHOOSE_SCREEN, syncId);
        this.entityId = entityId;
        DefaultedList<ItemStack> packageItems = extractItemsFromNbt(nbt);
        this.actualItemCount = packageItems.size();
        this.rows = (this.actualItemCount == 0) ? 1 : (int) Math.ceil(this.actualItemCount / 9.0);
        this.totalPackageSlots = this.rows * 9;
        this.packageInventory = new SimpleInventoryWrapper(packageItems, this.totalPackageSlots);
        addPackageSlots();
        addPlayerInventorySlots(playerInventory);
    }

    // 从NBT提取物品列表的通用方法
    private DefaultedList<ItemStack> extractItemsFromNbt(NbtCompound nbt) {
        DefaultedList<ItemStack> list = DefaultedList.of();
        if (nbt != null && nbt.contains("items", NbtElement.LIST_TYPE)) {
            NbtList itemsList = nbt.getList("items", NbtElement.COMPOUND_TYPE);
            for (int i = 0; i < itemsList.size(); i++) {
                NbtCompound compound = itemsList.getCompound(i);
                if (compound.contains("item", NbtElement.COMPOUND_TYPE)) {
                    ItemStack stack = ItemStack.fromNbt(compound.getCompound("item"));
                    if (!stack.isEmpty()) {
                        list.add(stack);
                    }
                }
            }
        }
        return list;
    }

    public int getEntityId() { return entityId; }
    public int getActualItemCount() { return actualItemCount; }

    private void addPackageSlots() {
        for (int i = 0; i < this.totalPackageSlots; i++) {
            int row = i / 9;
            int col = i % 9;
            int x = 8 + col * 18;
            int y = 17 + row * 18;
            addSlot(new PackageSlot(packageInventory, i, x, y));
        }
    }

    private void addPlayerInventorySlots(PlayerInventory playerInventory) {
        int startY = 17 + this.rows * 18 + 14;
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 9; c++) {
                int index = c + r * 9 + 9;
                int x = 8 + c * 18;
                int y = startY + r * 18;
                addSlot(new Slot(playerInventory, index, x, y));
            }
        }
        for (int i = 0; i < 9; i++) {
            int x = 8 + i * 18;
            int y = startY + 58;
            addSlot(new Slot(playerInventory, i, x, y));
        }
    }

    @Override
    public boolean canUse(PlayerEntity player) { return true; }
    @Override
    public ItemStack quickMove(PlayerEntity player, int slotIndex) { return ItemStack.EMPTY; }

    // 内部类保持不变
    private static class PackageSlot extends Slot {
        public PackageSlot(Inventory inventory, int index, int x, int y) { super(inventory, index, x, y); }
        @Override public boolean canTakeItems(PlayerEntity playerEntity) { return false; }
        @Override public boolean canInsert(ItemStack stack) { return false; }
    }

    private static class SimpleInventoryWrapper implements Inventory {
        private final DefaultedList<ItemStack> items;
        private final int size;
        public SimpleInventoryWrapper(DefaultedList<ItemStack> source, int size) {
            this.size = size;
            this.items = DefaultedList.ofSize(size, ItemStack.EMPTY);
            for (int i = 0; i < Math.min(source.size(), size); i++) this.items.set(i, source.get(i));
        }
        @Override public int size() { return size; }
        @Override public boolean isEmpty() { return items.stream().allMatch(ItemStack::isEmpty); }
        @Override public ItemStack getStack(int slot) { return (slot >= 0 && slot < size) ? items.get(slot) : ItemStack.EMPTY; }
        @Override public ItemStack removeStack(int slot, int amount) { return ItemStack.EMPTY; }
        @Override public ItemStack removeStack(int slot) { return ItemStack.EMPTY; }
        @Override public void setStack(int slot, ItemStack stack) {}
        @Override public void markDirty() {}
        @Override public boolean canPlayerUse(PlayerEntity player) { return true; }
        @Override public void clear() {}
    }
}