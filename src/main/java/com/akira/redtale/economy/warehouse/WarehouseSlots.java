package com.akira.redtale.economy.warehouse;

/**
 * 仓储/背包槽位的边界规则（纯函数，零 MC 依赖 ⇒ 可单测）。
 *
 * <p><b>为什么需要这个类</b>：仓储的 13 个 C2S 包里有 4 个把「背包槽号」「快捷栏槽号」
 * 直接从客户端读进来当索引用。原版 {@code PlayerInventory.getStack(int)} 的实现是
 * 「逐段减 size，最后 {@code List.get(slot)}」——<b>负数不会被判越界</b>，
 * 直接进 {@code List.get(-1)} 抛 {@link IndexOutOfBoundsException}：
 * <pre>
 *   0: aconst_null ... 32: iload_1 / 35: size() / 38: if_icmpge（-1 &lt; size ⇒ 取该段）
 *   68: iload_1 / 70: List.get(I)  ⇐ 负索引在这里炸
 * </pre>
 * 异常发生在主线程执行队列里 ⇒ 整个服务端 tick 崩，改过的客户端发一个
 * {@code warehouse/quickinv} 带 -1 就能打崩服务器。
 *
 * <p>所以索引在进原版 API 之前必须先过这一层；界面侧 {@code displayToSlot} 的
 * 映射结果是 {@code 0..35}（主背包 9..35 + 快捷栏 0..8），与 {@link #INVENTORY_SLOTS} 一致。
 */
public final class WarehouseSlots {

	/** 界面可操作的背包槽数（主背包 27 + 快捷栏 9）。 */
	public static final int INVENTORY_SLOTS = 36;

	/** 快捷栏槽数（数字键 1~9）。 */
	public static final int HOTBAR_SLOTS = 9;

	private WarehouseSlots() {
	}

	/** 背包槽号是否合法。 */
	public static boolean validInventory(int slot) {
		return slot >= 0 && slot < INVENTORY_SLOTS;
	}

	/** 快捷栏槽号是否合法。 */
	public static boolean validHotbar(int slot) {
		return slot >= 0 && slot < HOTBAR_SLOTS;
	}

	/** 仓储格下标是否合法（{@code slots} = 当前可用格数）。 */
	public static boolean validStorage(int index, int slots) {
		return index >= 0 && index < slots;
	}
}
