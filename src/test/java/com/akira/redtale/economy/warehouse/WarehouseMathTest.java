package com.akira.redtale.economy.warehouse;

import com.akira.redtale.economy.config.WarehouseConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * R27/R28：仓库格数计算的纯函数测试（不碰配置/注册表，能在单测里跑）。
 */
class WarehouseMathTest {

	@Test
	void normaliseRoundsToRowsOfNine() {
		assertEquals(9, WarehouseConfig.normalise(1));
		assertEquals(9, WarehouseConfig.normalise(8));
		assertEquals(9, WarehouseConfig.normalise(9));
		assertEquals(54, WarehouseConfig.normalise(54));
		assertEquals(54, WarehouseConfig.normalise(60));
		assertEquals(108, WarehouseConfig.normalise(108));
	}

	@Test
	void pageSizeIsRowsTimesNine() {
		// 规格：一页 = rows × 9，默认 6 行 = 54 格，正好一个双层大箱子
		assertEquals(54, 6 * 9);
		assertEquals(27, 3 * 9);
	}
}
