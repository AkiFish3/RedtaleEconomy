package com.akira.redtale.economy.legacy.screen.client;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIntArray;

/**
 * 编辑器状态的客户端镜像（T71）。
 *
 * <p>为什么需要它：{@code ScreenHandler} 只同步**槽位物品**，不同步「模式」和「权重」这两个纯数据字段。
 * 服务端每次动作后回发一份完整状态（{@link com.akira.redtale.economy.legacy.init.RENetworking#EDIT_STATE}），
 * 客户端只读不写 —— 界面永远不会显示"改了但其实没改"的假象。
 */
public final class PackageEditClientState {

    /**
     * ★长度必须跟服务端的格数同源★：旧实现写死 90，与服务端 {@code EDITOR_SLOTS} 各有一份真值。
     * Aki 待定项「320 宽是否缩到 10×6（60 格）」一旦落地，只改网格行列而漏改这里会得到
     * 「服务端发 60 条权重、客户端数组按 90 读」的静默错位（越界格恒 1，越界的安全）。
     * 现在直接引用 {@code PackageEditHandler.EDITOR_SLOTS}（常量表达式 ⇒ 编译期内联，无运行期依赖）。
     */
    public static final int SLOTS = com.akira.redtale.economy.legacy.screen.PackageEditHandler.EDITOR_SLOTS;

    private static String mode = "CHOOSE";
    /**
     * ★初值必须是 1，不能是 `new int[]` 的全 0★：编辑器开界面到第一份状态回包之间（1~2 帧）会按这个数组
     * 画角标，全 0 ⇒ 90 格全部显示灰色「0」（权重 0 的语义是"该条目永远抽不到"），看起来像数据坏了。
     * {@link #apply} / {@link #reset} 都已经填 1，只有静态初值漏了。
     */
    private static int[] weights = filledDefaults();
    private static int maxWeight = 999;
    /** 每次收到状态 +1：界面据此判断"需要重画/重排"，也方便夹具断言状态真的变化过。 */
    private static long revision = 0L;

    private PackageEditClientState() {
    }

    private static int[] filledDefaults() {
        int[] w = new int[SLOTS];
        java.util.Arrays.fill(w, 1);
        return w;
    }

    public static void apply(NbtCompound nbt) {
        if (nbt == null) return;
        if (nbt.contains("mode")) {
            mode = nbt.getString("mode");
        }
        if (nbt.contains("weights")) {
            int[] parsed = new int[SLOTS];
            java.util.Arrays.fill(parsed, 1);
            // ★必须判类型再转★：原来直接 `(NbtIntArray) nbt.get("weights")` —— 服务端若因未来改动
            // 发了别的标签类型（或长数组被拆），客户端会吃 ClassCastException 崩在渲染线程里，
            // 而这条路径每帧都在跑。非数组就保留默认 1（权重 1 = 权重中立，与未编辑等价）。
            if (nbt.get("weights") instanceof NbtIntArray arr) {
                int[] raw = arr.getIntArray();
                for (int i = 0; i < SLOTS && i < raw.length; i++) parsed[i] = raw[i];
            }
            weights = parsed;
        }
        if (nbt.contains("maxWeight")) {
            maxWeight = Math.max(1, nbt.getInt("maxWeight"));
        }
        revision++;
    }

    /** 断线/退服时清空，避免下一次进服先闪一帧上一件包裹的权重。 */
    public static void reset() {
        mode = "CHOOSE";
        weights = filledDefaults();
        maxWeight = 999;
        revision++;
    }

    public static String mode() {
        return mode;
    }

    public static int weight(int slot) {
        return slot >= 0 && slot < SLOTS ? weights[slot] : 1;
    }

    public static int maxWeight() {
        return maxWeight;
    }

    public static long revision() {
        return revision;
    }
}
