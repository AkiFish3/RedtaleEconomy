package com.akira.redtale.economy.legacy.misc;

import com.akira.redtale.economy.legacy.data.PackageData;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;

import java.util.ArrayList;
import java.util.List;

/**
 * 用于编辑界面的临时库存，支持权重和模式。
 * 与手持卡包物品绑定，保存时写回 NBT。
 */
public class EditablePackageInventory extends SimpleInventory {
    public static final int SIZE = 90; // 10 行 × 9 列

    private final ItemStack stackInHand;
    private PackageData.PackageMode mode = PackageData.PackageMode.CHOOSE;
    private final int[] weights; // ✅ 改为 int[]

    public EditablePackageInventory(ItemStack stackInHand) {
        super(SIZE);
        this.stackInHand = stackInHand;
        this.weights = new int[SIZE];
        for (int i = 0; i < SIZE; i++) {
            this.weights[i] = 1; // ✅ 默认权重为 1（int）
        }
        this.loadFromNbt();
    }

    private void loadFromNbt() {
        PackageData data = PackageData.fromNbt(stackInHand.getNbt());
        this.mode = data.mode();

        List<PackageData.PackageItem> items = data.items();
        for (int i = 0; i < items.size() && i < SIZE; i++) {
            setStack(i, items.get(i).item().copy());
            this.weights[i] = items.get(i).weight(); // ✅ int 赋值
        }
    }

    public void saveToNbt() {
        List<PackageData.PackageItem> items = new ArrayList<>();
        for (int i = 0; i < SIZE; i++) {
            ItemStack stack = getStack(i);
            if (!stack.isEmpty()) {
                // ✅ 权重转为非负整数
                int weight = Math.max(0, this.weights[i]);
                items.add(new PackageData.PackageItem(stack, weight));
            }
        }
        PackageData data = new PackageData(this.mode, items);
        // ✅ 使用 toNbt() 并写回 ItemStack
        NbtCompound nbt = data.toNbt();
        stackInHand.setNbt(nbt);
    }

    // ———————— 权重操作 ———————— //

    public void setWeight(int slot, int weight) { // ✅ 参数改为 int
        if (slot >= 0 && slot < SIZE) {
            this.weights[slot] = weight;
        }
    }

    public int getWeight(int slot) { // ✅ 返回 int
        return (slot >= 0 && slot < SIZE) ? this.weights[slot] : 1;
    }

    // ———————— 模式操作 ———————— //

    public PackageData.PackageMode getMode() {
        return mode;
    }

    public void setMode(PackageData.PackageMode mode) {
        this.mode = mode;
    }
}