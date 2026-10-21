package com.akira.redtale.economy.legacy.screen;

import com.akira.redtale.economy.legacy.init.REHandlers;
import com.akira.redtale.economy.legacy.item.PackageItem;
import com.akira.redtale.economy.legacy.misc.EditablePackageInventory;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.util.Hand;

public class PackageEditHandler extends ScreenHandler {
    private final EditablePackageInventory inventory;
    private final ItemStack originalStack;
    private final Hand hand;
    private static final int EDITOR_SLOTS = 90;
    private static final int PLAYER_INV_START = EDITOR_SLOTS;
    private static final int PLAYER_INV_END = PLAYER_INV_START + 27;
    private static final int HOTBAR_START = PLAYER_INV_END;
    private static final int HOTBAR_END = HOTBAR_START + 9;
    private final ItemStack packageStack;

    public PackageEditHandler(int syncId, PlayerInventory playerInventory, PacketByteBuf buf) {
        super(REHandlers.PACKAGE_EDIT_SCREEN, syncId);

        // ✅ 安全读取 NBT（避免 IndexOutOfBoundsException）
        NbtCompound nbt = null;
        if (buf != null && buf.isReadable()) {
            nbt = buf.readNbt();
        }
        this.packageStack = nbt != null ? ItemStack.fromNbt(nbt) : ItemStack.EMPTY;

        // ★必须从 buf 读 hand★：旧实现硬编码 MAIN_HAND ⇒ 创造模式用**副手**的包裹右键时，
        // 编辑器绑的是主手物品（任何物品都能被塞进 90 格 NBT）。
        this.hand = buf != null && buf.isReadable() ? buf.readEnumConstant(Hand.class) : Hand.MAIN_HAND;

        PlayerEntity player = playerInventory.player;
        if (player != null) {
            this.originalStack = player.getStackInHand(this.hand).copy();
        } else {
            this.originalStack = ItemStack.EMPTY;
        }

        this.inventory = new EditablePackageInventory(this.originalStack);
        addInventorySlots();
        addPlayerInventorySlots(playerInventory);
    }

    private void addInventorySlots() {
        for (int row = 0; row < 10; row++) {
            for (int col = 0; col < 9; col++) {
                int idx = row * 9 + col;
                int x = 8 + col * 18;
                int y = 18 + row * 18;
                addSlot(new Slot(inventory, idx, x, y) {
                    @Override
                    public boolean canInsert(ItemStack stack) {
                        return !ItemStack.areItemsEqual(stack, originalStack);
                    }
                });
            }
        }
    }

    private void addPlayerInventorySlots(PlayerInventory inv) {
        int startX = 8;
        int startY = 212;

        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 9; c++) {
                addSlot(new Slot(inv, c + r * 9 + 9, startX + c * 18, startY + r * 18));
            }
        }

        for (int i = 0; i < 9; i++) {
            addSlot(new Slot(inv, i, startX + i * 18, startY + 58));
        }
    }

    @Override
    public boolean canUse(PlayerEntity player) {
        // ★不能再恒 true★：编辑器写的是"手上那件物品"的 NBT ⇒ 必须确认手牌还是包裹本身，
        // 否则玩家在界面开着时把包裹换走/丢出，关闭时会把 90 格 NBT 写进手里任意物品。
        return player.getStackInHand(this.hand).getItem() instanceof PackageItem;
    }

    @Override
    public ItemStack quickMove(PlayerEntity player, int index) {
        Slot slot = this.slots.get(index);
        if (slot != null && slot.hasStack()) {
            ItemStack originalStack = slot.getStack();
            ItemStack newStack = originalStack.copy();

            if (index < EDITOR_SLOTS) {
                if (!this.insertItem(originalStack, PLAYER_INV_START, HOTBAR_END, true)) {
                    return ItemStack.EMPTY;
                }
            } else {
                if (!this.insertItem(originalStack, 0, EDITOR_SLOTS, false)) {
                    return ItemStack.EMPTY;
                }
            }

            if (originalStack.isEmpty()) {
                slot.setStack(ItemStack.EMPTY);
            } else {
                slot.markDirty();
            }

            return newStack;
        }
        return ItemStack.EMPTY;
    }

    @Override
    public void onClosed(PlayerEntity player) {
        super.onClosed(player);
        if (!player.getWorld().isClient()) {
            inventory.saveToNbt();
            // 把编辑结果写回原手牌（仍是同一包裹、且没被换走才写）
            ItemStack currentStack = player.getStackInHand(hand);
            if (!currentStack.isEmpty() && originalStack.hasNbt() && ItemStack.areItemsEqual(currentStack, originalStack)) {
                currentStack.setNbt(originalStack.getNbt().copy());
            }
        }
    }
}