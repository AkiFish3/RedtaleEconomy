package com.akira.redtale.economy.warehouse;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 仓储槽位边界（A 第 N+76 轮）。
 *
 * <p>这些断言看着琐碎，但每条都对应一个「客户端传进来就能打崩服务端」的入口：
 * 原版 {@code PlayerInventory.getStack(int)} / {@code setStack(int, ...)} 对负数不判越界，
 * 直接 {@code List.get(-1)} 抛 {@link IndexOutOfBoundsException}，而异常发生在主线程
 * 执行队列里 ⇒ 整个服务端 tick 崩。所以「负数必须为 false」是硬要求，不是风格问题。
 */
class WarehouseSlotsTest {

	@Test
	void inventoryAcceptsWholePlayerInventory() {
		assertTrue(WarehouseSlots.validInventory(0));   // 快捷栏第一格
		assertTrue(WarehouseSlots.validInventory(8));   // 快捷栏最后一格
		assertTrue(WarehouseSlots.validInventory(9));   // 主背包第一格
		assertTrue(WarehouseSlots.validInventory(35));  // 主背包最后一格
	}

	@Test
	void inventoryRejectsOutOfRangeIncludingNegative() {
		assertFalse(WarehouseSlots.validInventory(-1), "负数会进 List.get(-1) 崩主线程");
		assertFalse(WarehouseSlots.validInventory(Integer.MIN_VALUE));
		assertFalse(WarehouseSlots.validInventory(36), "36 起是盔甲槽，界面不该碰");
		assertFalse(WarehouseSlots.validInventory(999));
	}

	@Test
	void hotbarIsNineSlotsOnly() {
		assertTrue(WarehouseSlots.validHotbar(0));
		assertTrue(WarehouseSlots.validHotbar(8));
		assertFalse(WarehouseSlots.validHotbar(-1));
		assertFalse(WarehouseSlots.validHotbar(9));
	}

	@Test
	void storageUsesLiveSlotCount() {
		// 9 格仓（新号默认）：0..8 合法，9 起没买
		assertTrue(WarehouseSlots.validStorage(0, 9));
		assertTrue(WarehouseSlots.validStorage(8, 9));
		assertFalse(WarehouseSlots.validStorage(9, 9));
		assertFalse(WarehouseSlots.validStorage(-1, 9));
		// 缩容/扩容后按当次 slots 判，不写死
		assertFalse(WarehouseSlots.validStorage(8, 4));
		assertTrue(WarehouseSlots.validStorage(20, 27));
	}
}
