package com.akira.redtale.economy.legacy.misc;

import com.akira.redtale.economy.legacy.data.PackageData;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;

import java.util.ArrayList;
import java.util.List;

/**
 * 用于编辑界面的临时库存，支持权重和模式。
 * 与手持卡包物品绑定，保存时写回 NBT。
 */
public class EditablePackageInventory extends SimpleInventory {
    /**
     * 编辑器格数 = 网格格数（`GRID_COLS × GRID_ROWS`），**唯一真值在 `PackageEditHandler.EDITOR_SLOTS`**。
     *
     * <p>★这里原是第四份字面量 90★（另有 `GRID_COLS/ROWS`、`PackageEditHandler.EDITOR_SLOTS`、
     * `PackageEditClientState.SLOTS`）。这个数组是**落盘的那一份**（`saveToNbt` 按它截断、`loadFromNbt` 按它读），
     * 一旦与网格分叉就是「界面能放 60 格、NBT 只存 90」或反过来的静默错位。
     *
     * <p>引用的是**常量表达式** ⇒ 编译期内联，运行期不会真的去初始化 screen 包的那个类，
     * 所以 misc → screen 的源码依赖不构成初始化顺序问题（`javac` 允许这种互相引用）。
     */
    public static final int SIZE = com.akira.redtale.economy.legacy.screen.PackageEditHandler.EDITOR_SLOTS;

    /** ★可换绑★：T71 的「档位选择」要换物品 id（tier 存在 id 里）⇒ 底层 ItemStack 会整颗换掉。 */
    private ItemStack stackInHand;
    private PackageData.PackageMode mode = PackageData.PackageMode.CHOOSE;
    private final int[] weights; // ✅ 改为 int[]
    /** T76「三保险」第 1 层：任何一次改动（拖入/移出/改权重）都置脏，由 handler 每 tick 判断是否落盘。 */
    private boolean dirty = false;

    public EditablePackageInventory(ItemStack stackInHand) {
        super(SIZE);
        this.stackInHand = stackInHand;
        this.weights = new int[SIZE];
        for (int i = 0; i < SIZE; i++) {
            this.weights[i] = 1; // ✅ 默认权重为 1（int）
        }
        this.loadFromNbt();
        // ★读入不算改动★：loadFromNbt 走 setStack ⇒ markDirty ⇒ 若不在这里清掉，
        // 开一次编辑器就会「1 秒后无条件重写一遍手牌 NBT」（内容一样，但会触发同步 + 覆盖快照）。
        this.dirty = false;
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
        // ★不能整包 setNbt(data.toNbt())★：那会把物品上其它键一起抹掉（铁砧改名的 display.Name、
        // lore、RepairCost、绑定归属……）⇒ 玩家改名后开一次编辑器就丢名字，且零报错。
        // 正确形态：以现有 tag 为底，只覆盖本模组拥有的 mode / items 两个键。
        NbtCompound nbt = stackInHand.hasNbt() ? stackInHand.getNbt().copy() : new NbtCompound();
        nbt.putString("mode", data.mode().name());
        NbtList itemsList = new NbtList();
        for (PackageData.PackageItem item : data.items()) {
            itemsList.add(item.toNbt());
        }
        nbt.put("items", itemsList);
        stackInHand.setNbt(nbt);
    }

    // ———————— 权重操作 ———————— //

    public void setWeight(int slot, int weight) { // ✅ 参数改为 int
        if (slot >= 0 && slot < SIZE) {
            if (this.weights[slot] != weight) this.dirty = true;
            this.weights[slot] = weight;
        }
    }

    /** 取走「有改动」标记（读即清）。 */
    public boolean consumeDirty() {
        boolean d = this.dirty;
        this.dirty = false;
        return d;
    }

    public boolean isDirty() {
        return this.dirty;
    }

    /** {@link SimpleInventory} 在 setStack/removeStack 里都会调它 ⇒ 拖入/移出自动置脏。 */
    @Override
    public void markDirty() {
        this.dirty = true;
    }

    public int getWeight(int slot) { // ✅ 返回 int
        return (slot >= 0 && slot < SIZE) ? this.weights[slot] : 1;
    }

    /**
     * 拖出即清权重（2026-10-22 第 32 轮）。
     *
     * <p>★权重数组按「格子」存，不按物品存★：右键删除走 {@link #setWeight} 复位了，但**把物品拖回背包**
     * 走的是 {@code removeStack}（{@code Slot.onTakeItem} ⇒ {@code Inventory.removeStack}），
     * 旧实现只留下一个空格子 + 旧权重。玩家随后往同一格拖入新物品 ⇒ 新物品**静默继承**上一件的权重
     * （实测 RANDOM 包里把权重 50 的钻石拖走、换成苹果 ⇒ 苹果直接占 50/51 的抽取概率，界面看不出原因）。
     *
     * <p>两个重载都要覆盖：vanilla 的 {@code removeStack(slot)} 不走 {@code removeStack(slot, amount)}。
     */
    @Override
    public ItemStack removeStack(int slot, int amount) {
        ItemStack removed = super.removeStack(slot, amount);
        resetWeightIfEmpty(slot);
        return removed;
    }

    @Override
    public ItemStack removeStack(int slot) {
        ItemStack removed = super.removeStack(slot);
        resetWeightIfEmpty(slot);
        return removed;
    }

    /**
     * ★{@code setStack} 是第三条清权重入口，第 32 轮漏了★（第 53 轮）。
     *
     * <p>第 32 轮只覆盖了 {@link #removeStack} 的两个重载（拖出/右键删走 {@code Slot.onTakeItem}
     * → {@code Inventory.removeStack}）。但 **shift-click 移出**走的是另一条：
     * {@code ScreenHandler.quickMove} 在物品搬空后执行 `slot.setStack(ItemStack.EMPTY)`，
     * 而 javap 实证 {@code Slot.setStack} → {@code setStackNoCallbacks} → {@code Inventory.setStack(index, stack)}
     * （字节码 `invokeinterface #83 Inventory.setStack`），**完全不经过 removeStack**。
     * ⇒ 同一个格子：shift 移出后权重仍是旧值，下一件拖进来的物品静默继承
     * （与第 32 轮同一个症状，只是入口从"拖出"换成"shift 移出"，零报错、界面看不出原因）。
     *
     * <p>{@code removeStack} 不会调到这里（{@code SimpleInventory.removeStack} 用
     * {@code Inventories.splitStack} 直接改 list），所以两个重载保留、互不重复置脏。
     */
    @Override
    public void setStack(int slot, ItemStack stack) {
        super.setStack(slot, stack);
        resetWeightIfEmpty(slot);
    }

    /** 格子空了就把权重归 1（新物品进来时不会继承上一件的权重）。 */
    private void resetWeightIfEmpty(int slot) {
        if (slot < 0 || slot >= SIZE) return;
        if (getStack(slot).isEmpty() && this.weights[slot] != 1) {
            this.weights[slot] = 1;
            this.dirty = true;
        }
    }

    // ———————— 模式操作 ———————— //

    public PackageData.PackageMode getMode() {
        return mode;
    }

    public void setMode(PackageData.PackageMode mode) {
        if (mode == null || this.mode == mode) return;
        this.mode = mode;
        // ★改 mode 也算改动★：T76 的三层保存都靠这个脏标记，漏置 ⇒ 改完模式关窗不落盘（零报错）。
        this.dirty = true;
    }

    /**
     * 换绑到另一件 ItemStack（T71 档位切换用：tier 编码在物品 id 上，切档 = 换物品实例）。
     * 内容（items / weights / mode）保持不变，只换写入目标与后续读取的基准。
     */
    public void rebind(ItemStack newStack) {
        if (newStack == null) return;
        this.stackInHand = newStack;
        markDirty();
    }

    /** 当前绑定的物品栈（供 handler 回读/校准）。 */
    public ItemStack boundStack() {
        return this.stackInHand;
    }
}