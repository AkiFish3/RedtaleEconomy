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
    /**
     * 候选网格**最多显示 10 行（90 格）**。
     *
     * <p>★旧实现 `rows = ceil(count / 9)` 没有上限★：包裹 NBT 里的 `items` 条数只由编辑器
     * （90 格）约束，管理员用手改 NBT / `/data modify` 塞进 120 条时 rows=14 ⇒ 面板高
     * `114 + 14×18 = 366`，在 1920×1080（guiScale auto=4 ⇒ 逻辑 480×270）下 `top()` 变成 -48
     * ⇒ 前半截候选格和整块背包区跑到屏幕外，**看得见点不到**，且零报错、零日志（鼠标移到
     * 格子上也没反应，玩家只会觉得"这个礼包打不开"）。
     *
     * <p>现在按最窄目标分辨率反推：10 行 = 294 高，在 480×270 下只切掉底部 12px 的背包边框
     * （`_b39_screen_fit.py` 那条 WARN 就是它，候选格全部仍可点）；超过 90 条的部分不显示，
     * 由服务端构造器写一条 warn，别静默。
     */
    public static final int MAX_ROWS = 10;

    private final Inventory packageInventory;
    public final int rows;
    public final int totalPackageSlots;
    private final int actualItemCount;
    private final int entityId;

    /**
     * 条目数 → 行数。★客户端与服务端两个构造器必须共用这一份★：
     * 两边各写一遍公式时，只要有一边加了上限，槽位下标 / 点击映射就会错位（点了 A 给 B）。
     */
    private static int rowsFor(int count) {
        if (count <= 0) return 1;
        return Math.min(MAX_ROWS, (int) Math.ceil(count / 9.0));
    }

    // 客户端使用的构造函数（从 buf 读取）
    public PackageChooseHandler(int syncId, PlayerInventory playerInventory, PacketByteBuf buf) {
        super(REHandlers.PACKAGE_CHOOSE_SCREEN, syncId);
        // 读取实体ID
        this.entityId = (buf != null && buf.isReadable()) ? buf.readVarInt() : -1;
        // 读取卡包NBT
        NbtCompound nbt = (buf != null && buf.isReadable()) ? buf.readNbt() : null;
        DefaultedList<ItemStack> packageItems = extractItemsFromNbt(nbt);
        this.actualItemCount = packageItems.size();
        this.rows = rowsFor(this.actualItemCount);
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
        this.rows = rowsFor(this.actualItemCount);
        this.totalPackageSlots = this.rows * 9;
        if (this.actualItemCount > this.totalPackageSlots) {
            // 静默截断会让"管理员塞了 120 件但只能选前 90 件"变成玄学 ⇒ 明确记一条
            com.akira.redtale.economy.legacy.LegacyInit.LOGGER.warn(
                    "[legacy] 选择包条目 {} > 界面上限 {} 格，超出部分不显示（实体 {})",
                    this.actualItemCount, this.totalPackageSlots, entityId);
        }
        this.packageInventory = new SimpleInventoryWrapper(packageItems, this.totalPackageSlots);
        addPackageSlots();
        addPlayerInventorySlots(playerInventory);
    }

    // 从NBT提取物品列表的通用方法
    // ★必须 public static★：SELECT_ITEM 包里传的是「界面第几个格子」，而界面只把**非空**物品放进格子
    // ⇒ 服务端必须用同一份过滤逻辑把 index 映射回 ItemStack；旧实现直接 `data.items().get(index)`
    // 用的是含空条目的原始列表，只要包裹 NBT 里有一个空 item，点第一个格子就会发出第 N 个物品（零报错错位）。
    public static DefaultedList<ItemStack> extractItemsFromNbt(NbtCompound nbt) {
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

    /**
     * ★每 tick 由服务端判定★：旧实现恒 true ⇒ 包裹实体因超距离/超时被 discard 之后，
     * 界面还挂在玩家屏幕上（点什么都没反应、也不报错），看起来像"卡界面"。
     * 现在实体没了 / 已选完 / 不是本人 / 走太远都直接关窗。
     */
    @Override
    public boolean canUse(PlayerEntity player) {
        if (entityId < 0) return true; // 客户端侧构造器没有实体 id
        if (!(player.getWorld().getEntityById(entityId) instanceof com.akira.redtale.economy.legacy.entity.PackageEntity pkg)) {
            return false;
        }
        if (!pkg.isChooseModeActive()) return false;
        if (pkg.getOwnerUuid() != null && !pkg.getOwnerUuid().equals(player.getUuid())) return false;
        // ★400.0（20 格）与 RENetworking 的 C2S 闸门、PackageEntity.tick 的超距离回收同口径★：
        // 旧值 256.0（16 格）比另外两处更紧 ⇒ 玩家站在 17~19 格时服务端照收 select_item、
        // 实体也不会被回收，但 canUse 每 tick 判 false ⇒ 界面被强行关掉（"点不到，界面自己没了"）。
        return player.squaredDistanceTo(pkg) <= 400.0;
    }
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