package com.akira.redtale.economy.legacy.screen;

import com.akira.redtale.economy.legacy.LegacyInit;
import com.akira.redtale.economy.legacy.data.PackageCatalog;
import com.akira.redtale.economy.legacy.data.PackageData;
import com.akira.redtale.economy.legacy.init.REHandlers;
import com.akira.redtale.economy.legacy.init.RENetworking;
import com.akira.redtale.economy.legacy.item.PackageItem;
import com.akira.redtale.economy.legacy.misc.EditablePackageInventory;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.util.Hand;

public class PackageEditHandler extends ScreenHandler {
    private final EditablePackageInventory inventory;
    /** ★非 final★：T71 档位切换会把整件物品换成同系列的另一个 id（tier 编码在物品 id 上）。 */
    private ItemStack originalStack;
    private final Hand hand;
    /**
     * 网格 / 背包的**唯一几何真值**：槽位（服务端）与背景绘制 / 命中判定（客户端）都读这里。
     * 之前两边各写一份常量，改布局时必然出现"图标在 A 处、点击判在 B 处"的错位。
     */
    public static final int EDITOR_SLOTS = 90;
    public static final int GRID_COLS = 9;
    public static final int GRID_ROWS = 10;
    public static final int GRID_X = 8;
    public static final int GRID_Y = 52;
    public static final int INV_X = 8;
    public static final int INV_Y = 240;
    /** 单条权重上限：防客户端塞 Integer.MAX_VALUE 让其它条目永远抽不到（也让 UI 放得下 3 位数）。 */
    public static final int MAX_WEIGHT = 999;
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
    private NbtCompound originalNbtSnapshot;
    /** 开界面时手牌那个 ItemStack 的**引用**（不是 copy）：换物品一定是换对象 ⇒ 便宜的第二道身份判据。 */
    private ItemStack handStackRef;
    /** 上次落盘时间（毫秒）。T76 第 2 层：改动后最多 1 秒写进物品 NBT。 */
    private long lastFlushMs = 0L;
    private static final long FLUSH_DEBOUNCE_MS = 1000L;

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
        this.ownerPlayer = player;
        if (player != null) {
            this.originalStack = player.getStackInHand(this.hand).copy();
            this.handStackRef = player.getStackInHand(this.hand);
        } else {
            this.originalStack = ItemStack.EMPTY;
            this.handStackRef = ItemStack.EMPTY;
        }
        this.originalNbtSnapshot = this.originalStack.hasNbt() ? this.originalStack.getNbt().copy() : null;

        this.inventory = new EditablePackageInventory(this.originalStack);
        // ★基准时间定在开界面时刻★：否则默认 0 ⇒ 第一次改动会被立刻落盘（"0" 到 now 恒 >1s），
        // 变成"第一次即时写、之后每秒一次"。规格要的是「改动后最多 1 秒落盘」，统一从开窗算起。
        this.lastFlushMs = System.currentTimeMillis();
        addInventorySlots();
        addPlayerInventorySlots(playerInventory);
    }

    private void addInventorySlots() {
        for (int row = 0; row < GRID_ROWS; row++) {
            for (int col = 0; col < GRID_COLS; col++) {
                int idx = row * GRID_COLS + col;
                int x = GRID_X + col * 18;
                int y = GRID_Y + row * 18;
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
        int startX = INV_X;
        int startY = INV_Y;

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
        // 同一对象 ⇒ 一定是开界面那件（快捷栏/背包里换物品必然换 ItemStack 实例）
        if (current == this.handStackRef) {
            return true;
        }
        if (!ItemStack.areItemsEqual(current, this.originalStack)) {
            return false;
        }
        NbtCompound currentNbt = current.hasNbt() ? current.getNbt() : null;
        return java.util.Objects.equals(currentNbt, this.originalNbtSnapshot);
    }

    /**
     * T76「三保险」第 2 层：**每秒 debounce 落盘**。
     *
     * <p>{@link net.minecraft.server.network.ServerPlayerEntity} 每 tick 会调当前 handler 的
     * {@code sendContentUpdates()} ⇒ 拿它当免费 tick 钩子（不需要自建 tick 事件，也不用 mixin）。
     * 有改动且距上次落盘 ≥1 秒才写，避免拖一件物品触发十几次 NBT 同步。
     */
    @Override
    public void sendContentUpdates() {
        super.sendContentUpdates();
        if (this.inventory == null || this.handStackRef == null) return;
        // ★只在服务端写★：ClientPlayerEntity 每 tick 也会调 sendContentUpdates，
        // 客户端改手牌 NBT 只是本地假象（下一次同步就被服务端覆盖），还会和快照打架。
        if (ownerPlayer == null || ownerPlayer.getWorld() == null || ownerPlayer.getWorld().isClient()) return;
        if (!this.inventory.isDirty()) return;
        long now = System.currentTimeMillis();
        if (now - lastFlushMs < FLUSH_DEBOUNCE_MS) return;
        if (playerEntity() == null) return;
        if (flushToHand(playerEntity())) {
            // 落盘成功才清脏：手牌已被换走（返回 false）要留着标记，等下次/关窗再试
            this.inventory.consumeDirty();
        }
    }

    /** 当前 handler 的主人（服务端）。构造时拿到过 PlayerInventory，这里不做额外字段。 */
    private PlayerEntity ownerPlayer;
    private PlayerEntity playerEntity() { return ownerPlayer; }

    /**
     * 把编辑结果写进手牌，并把 NBT 快照更新为刚写的那份。
     *
     * <p>★快照必须跟着更新★：身份判据是「NBT 等于快照」，落盘后手牌 NBT 必然变化，
     * 若不更新快照，关界面（或掉线）时会被判成「换了另一件包裹」而放弃保存 —— 改动全丢。
     * 反之更新后仍能挡住「换成别的包裹」：换物品要么换对象（引用判据），要么 NBT 不同。
     */
    private boolean flushToHand(PlayerEntity player) {
        // 失败也要记时间：不然「手牌已换走」会在每 tick 重试一次（本方法被 sendContentUpdates 调）
        this.lastFlushMs = System.currentTimeMillis();
        ItemStack current = player.getStackInHand(this.hand);
        if (!isSamePackage(current)) return false;
        this.inventory.saveToNbt();
        if (this.originalStack.hasNbt()) {
            current.setNbt(this.originalStack.getNbt().copy());
            this.originalNbtSnapshot = current.getNbt() == null ? null : current.getNbt().copy();
        }
        return true;
    }

    /**
     * T76「三保险」第 3 层：掉线强制提交（{@code ServerPlayConnectionEvents.DISCONNECT} 调用）。
     * 幂等：已经 flush 过就只是再写一次内容相同的 NBT；手牌已换物品则直接放弃。
     */
    public void forceCommit(PlayerEntity player) {
        if (player == null) return;
        flushToHand(player);
        this.inventory.consumeDirty();
    }

    // ———————————————————————— T71 可视化编辑器：服务端动作 ————————————————————————

    /**
     * 当前编辑状态（mode + 90 条权重 + 上限）。
     *
     * <p>★为什么不用原版 Property 同步★：1.20.1 的属性同步按「单个 int / 每次变化一个包」走，
     * 90 条权重会变成 90 个包；这里一次性发一个 NBT 更省，也让客户端能拿到 complete state
     * （界面重开、掉线重连后不会残留上一件包裹的权重）。
     */
    public NbtCompound toStateNbt() {
        NbtCompound nbt = new NbtCompound();
        nbt.putString("mode", inventory.getMode().name());
        int[] w = new int[EDITOR_SLOTS];
        for (int i = 0; i < EDITOR_SLOTS; i++) w[i] = inventory.getWeight(i);
        nbt.putIntArray("weights", w);
        nbt.putInt("maxWeight", MAX_WEIGHT);
        return nbt;
    }

    /** 把状态回发给开界面的玩家（服务端调用；客户端自己调是空操作）。 */
    public void sendState() {
        if (ownerPlayer instanceof net.minecraft.server.network.ServerPlayerEntity sp) {
            RENetworking.sendEditState(sp, toStateNbt());
        }
    }

    /** mode 切换。返回是否真的改了。 */
    public boolean applySetMode(int ordinal) {
        PackageData.PackageMode[] modes = PackageData.PackageMode.values();
        if (ordinal < 0 || ordinal >= modes.length) return false;
        inventory.setMode(modes[ordinal]);
        return true;
    }

    /** 权重设置：钳到 [0, MAX_WEIGHT]。0 = 该条目在 RANDOM 里被跳过（但仍留在包里）。 */
    public boolean applySetWeight(int slot, int weight) {
        if (slot < 0 || slot >= EDITOR_SLOTS) return false;
        inventory.setWeight(slot, Math.max(0, Math.min(MAX_WEIGHT, weight)));
        return true;
    }

    /** 删除条目：清空格子并把权重复位（不清权重会让新拖入的物品继承上一次的权重）。 */
    public boolean applyClearSlot(int slot) {
        if (slot < 0 || slot >= EDITOR_SLOTS) return false;
        inventory.setStack(slot, ItemStack.EMPTY);
        inventory.setWeight(slot, 1);
        return true;
    }

    /**
     * 档位切换：tier 编码在**物品 id** 上（{@code package_rare}），所以换档 = 换一件物品，
     * 内容/NBT 照搬。
     *
     * <p>★换完必须整体换绑身份基准★（originalStack / handStackRef / NBT 快照 / 库存绑定）：
     * 否则下一次 flush 的 {@link #isSamePackage} 会把新物品判成「玩家换了包裹」而拒写，
     * 症状是「切档后拖进去的物品一个也不保存」，且零报错。
     */
    public boolean applySetTier(String tierId) {
        PlayerEntity player = ownerPlayer;
        if (player == null || tierId == null) return false;
        // 先把最新编辑落进当前物品，换档时 NBT 才是最新的
        flushToHand(player);
        ItemStack current = player.getStackInHand(this.hand);
        if (!isSamePackage(current)) return false;
        String path = net.minecraft.registry.Registries.ITEM.getId(current.getItem()).getPath();
        String targetId = PackageCatalog.itemIdForTier(path, tierId);
        if (targetId == null) return false;
        Item target = net.minecraft.registry.Registries.ITEM.get(
                new net.minecraft.util.Identifier(LegacyInit.MOD_ID, targetId));
        if (target == null || target == net.minecraft.item.Items.AIR) return false;
        if (target == current.getItem()) return true; // 已经是这个档

        ItemStack replacement = new ItemStack(target, Math.max(1, current.getCount()));
        if (current.hasNbt()) replacement.setNbt(current.getNbt().copy());
        player.setStackInHand(this.hand, replacement);

        this.originalStack = replacement.copy();
        this.handStackRef = replacement;
        this.originalNbtSnapshot = replacement.hasNbt() ? replacement.getNbt().copy() : null;
        this.inventory.rebind(replacement);
        LegacyInit.LOGGER.info("[legacy] 包裹换档 {} → {}（{}）", path, targetId,
                player.getGameProfile().getName());
        return true;
    }

    /**
     * 当前编辑物品的 item path（如 {@code package_rare}）。
     *
     * <p>★优先读手牌★：客户端与服务端是**两个** handler 实例，服务端换档不会同步这边的字段；
     * 手牌物品会随背包同步下来，所以只有它能反映真实档位（否则档位高亮永远停在开界面那一档）。
     */
    public String currentItemPath() {
        ItemStack cur = null;
        if (ownerPlayer != null) {
            cur = ownerPlayer.getStackInHand(this.hand);
        }
        if (cur == null || cur.isEmpty()) cur = this.originalStack;
        if (cur == null || cur.isEmpty()) return "";
        return net.minecraft.registry.Registries.ITEM.getId(cur.getItem()).getPath();
    }

    /** 当前档位 id（{@code normal/rare/...}）；未知返回空串。 */
    public String currentTierId() {
        String t = PackageCatalog.tierOf(currentItemPath());
        return t == null ? "" : t;
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
            // T76「三保险」第 1 层：关闭即保存（无保存按钮）。
            // ★先判身份再落盘★：手牌已被换走/丢掉时，saveToNbt + setNbt 会把内容写进"另一件物品"。
            // 判据见 isSamePackage / originalNbtSnapshot 注释。
            flushToHand(player);
            // 不论写没写成，都要清脏：否则这个 handler 被复用/复归时还会再试一次
            this.inventory.consumeDirty();
        }
    }
}