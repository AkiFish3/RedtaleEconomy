package com.akira.redtale.economy.shop;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 跨店搜索的排序/去重/截断口径（T25）。 */
class ShopCrossSearchTest {

	private static ShopCrossSearch.Hit hit(String shop, String item, long buy, long sell, long stock) {
		return new ShopCrossSearch.Hit(shop, shop + "店", item, item, buy, sell, stock);
	}

	@Test
	void tokensSplitAndLowercase() {
		assertEquals(0, ShopCrossSearch.tokens(null).length);
		assertEquals(0, ShopCrossSearch.tokens("   ").length);
		assertEquals(List.of("oak", "slab"), List.of(ShopCrossSearch.tokens("  Oak  SLAB ")));
	}

	@Test
	void emptyQueryMatchesNothing() {
		ShopSearch idx = ShopSearch.of("minecraft:oak_slab", "建材/木材", "", "橡木台阶");
		assertFalse(ShopCrossSearch.matches(idx, ShopCrossSearch.tokens("")));
		assertFalse(ShopCrossSearch.matches(null, new String[]{"oak"}));
		assertTrue(ShopCrossSearch.matches(idx, ShopCrossSearch.tokens("oak")));
	}

	@Test
	void inStockBuyableComesFirstThenPrice() {
		List<ShopCrossSearch.Hit> raw = new ArrayList<>();
		raw.add(hit("shopc", "minecraft:stone", 0, 8, 999));      // 只收购
		raw.add(hit("shopa", "minecraft:stone", 1, 0, 0));         // 有价但缺货
		raw.add(hit("shopb", "minecraft:stone", 50, 10, 12));      // 能买，50
		raw.add(hit("shopd", "minecraft:stone", 5, 0, 3));         // 能买，便宜

		List<ShopCrossSearch.Hit> out = ShopCrossSearch.rank(raw, 4);
		assertEquals(List.of("shopd", "shopb", "shopa", "shopc"),
				out.stream().map(ShopCrossSearch.Hit::shopId).toList());
	}

	@Test
	void duplicatesOfSameShopAndItemCollapse() {
		List<ShopCrossSearch.Hit> raw = List.of(
				hit("shopa", "minecraft:stone", 7, 0, 5),
				hit("shopa", "minecraft:stone", 7, 0, 5),
				hit("shopb", "minecraft:stone", 9, 0, 5));
		assertEquals(2, ShopCrossSearch.rank(raw, 10).size());
	}

	@Test
	void limitTruncatesAfterSorting() {
		List<ShopCrossSearch.Hit> raw = new ArrayList<>();
		for (int i = 1; i <= 20; i++) {
			raw.add(hit("shop" + i, "minecraft:stone", i, 0, 1));
		}
		List<ShopCrossSearch.Hit> out = ShopCrossSearch.rank(raw, 3);
		assertEquals(3, out.size());
		assertEquals(List.of("shop1", "shop2", "shop3"),
				out.stream().map(ShopCrossSearch.Hit::shopId).toList());
		assertTrue(ShopCrossSearch.rank(raw, 0).isEmpty());
		assertTrue(ShopCrossSearch.rank(List.of(), 5).isEmpty());
	}

	@Test
	void pricePrefersBuyThenSell() {
		assertEquals(30, hit("s", "i", 30, 10, 1).price());
		assertEquals(10, hit("s", "i", 0, 10, 1).price());
		assertFalse(hit("s", "i", 30, 0, 0).buyableNow());
	}
}
