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
    /**
     * 开界面那一刻手牌物品的 NBT 快照（可为 null）。
     * ★必须用快照判「还是同一件包裹」★：`ItemStack.areItemsEqual` 按 yarn 定义**只比物品、不比 NBT**
     * （见 yarn ItemStack javadoc「use areItemsEqual to check the item only」）⇒ 创造模式开编辑器后
     * 把主手换成另一个同种包裹，关界面时会把 A 的内容写进 B。而拿 `originalStack` 去比也不行 ——
     * 它在 close 前已被 saveToNbt 覆盖成新数据，拿它当基准恒不等。
     */
    private final NbtCompound originalNbtSnapshot;

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
        this.originalNbtSnapshot = this.originalStack.hasNbt() ? this.originalStack.getNbt().copy() : null;

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
        // 这里只做"还是包裹类"的轻判定：严格 NBT 身份判定放在 onClosed（写盘前），
        // 避免客户端快照与服务端 NBT 归一化差异导致界面被秒关。
        return player.getStackInHand(this.hand).getItem() instanceof PackageItem;
    }

    /**
     * 手牌是不是"开界面时那一件"包裹：物品相同 **且 NBT 与快照相同**。
     * 只比物品不够 —— `areItemsEqual` 不比 NBT，换成另一个同种包裹会串写。
     */
    private boolean isSamePackage(ItemStack current) {
        if (current == null || current.isEmpty()) {
            return false;
        }
        if (!ItemStack.areItemsEqual(current, this.originalStack)) {
            return false;
        }
        NbtCompound currentNbt = current.hasNbt() ? current.getNbt() : null;
        return java.util.Objects.equals(currentNbt, this.originalNbtSnapshot);
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
            // ★先判身份再落盘★：手牌已被换走/丢掉时，saveToNbt + setNbt 会把内容写进"另一件物品"。
            // 判据用开界面时的 NBT 快照（见 originalNbtSnapshot 注释），不能用 originalStack。
            ItemStack currentStack = player.getStackInHand(hand);
            if (!isSamePackage(currentStack)) {
                return;
            }
            inventory.saveToNbt();
            // 编辑结果写回原手牌（saveToNbt 已把 mode/items 合并进 originalStack，其余键原样保留）
            if (this.originalStack.hasNbt()) {
                currentStack.setNbt(this.originalStack.getNbt().copy());
            }
        }
    }
}