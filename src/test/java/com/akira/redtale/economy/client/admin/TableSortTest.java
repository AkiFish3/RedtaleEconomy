package com.akira.redtale.economy.client.admin;

import com.akira.redtale.economy.net.ShopProtocol;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** R16：表格编辑器排序 / 搜索（比较器写反在界面上几乎看不出来，只能靠测试钉）。 */
class TableSortTest {

	private static ShopProtocol.Entry e(String id, long buy, long sell, long stock) {
		return new ShopProtocol.Entry(id, buy, sell, stock, true, true);
	}

	private final List<ShopProtocol.Entry> rows = List.of(
			e("minecraft:bread", 30, 10, 100),
			e("minecraft:cake", 300, 90, -1),
			e("minecraft:apple", 12, 5, 500));

	@Test
	void sortsByBuy() {
		List<ShopProtocol.Entry> out = TableSort.sort(rows, "buy", true);
		assertEquals(List.of("minecraft:apple", "minecraft:bread", "minecraft:cake"),
				out.stream().map(ShopProtocol.Entry::itemId).toList());
	}

	@Test
	void sortsByNameDescending() {
		List<ShopProtocol.Entry> out = TableSort.sort(rows, "name", false);
		assertEquals("minecraft:cake", out.get(0).itemId());
	}

	@Test
	void sortsByStockTreatingMinusOneAsLowest() {
		List<ShopProtocol.Entry> out = TableSort.sort(rows, "stock", true);
		assertEquals("minecraft:cake", out.get(0).itemId());
	}

	@Test
	void filterIsCaseInsensitiveAndPartial() {
		assertEquals(1, TableSort.filter(rows, "BRE").size());
		assertEquals(3, TableSort.filter(rows, "  ").size());
		assertEquals(0, TableSort.filter(rows, "zzz").size());
	}
}
