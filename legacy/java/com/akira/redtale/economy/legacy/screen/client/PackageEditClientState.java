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

    public static final int SLOTS = 90;

    private static String mode = "CHOOSE";
    private static int[] weights = new int[SLOTS];
    private static int maxWeight = 999;
    /** 每次收到状态 +1：界面据此判断"需要重画/重排"，也方便夹具断言状态真的变化过。 */
    private static long revision = 0L;

    private PackageEditClientState() {
    }

    public static void apply(NbtCompound nbt) {
        if (nbt == null) return;
        if (nbt.contains("mode")) {
            mode = nbt.getString("mode");
        }
        if (nbt.contains("weights")) {
            int[] parsed = new int[SLOTS];
            java.util.Arrays.fill(parsed, 1);
            int[] raw = ((NbtIntArray) nbt.get("weights")).getIntArray();
            for (int i = 0; i < SLOTS && i < raw.length; i++) parsed[i] = raw[i];
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
        weights = new int[SLOTS];
        java.util.Arrays.fill(weights, 1);
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
