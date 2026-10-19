package com.akira.redtale.economy.client.admin;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** R16a：编辑界面的命令格式（与 ShopAdminCommands 的子命令名逐一对齐）。 */
class EditCommandsTest {

	@Test
	void priceFormat() {
		assertEquals("shop admin price shop1 minecraft:stone 100 40",
				EditCommands.price("shop1", "minecraft:stone", 100, 40));
	}

	@Test
	void priceClampsNegativeToZero() {
		assertEquals("shop admin price s i 0 0", EditCommands.price("s", "i", -5, -7));
	}

	@Test
	void stockPassesMinusOneForUnlimited() {
		assertEquals("shop admin stock s i -1", EditCommands.stock("s", "i", -1));
		assertEquals("shop admin stock s i 0", EditCommands.stock("s", "i", 0));
		assertEquals("shop admin stock s i 12", EditCommands.stock("s", "i", 12));
	}

	@Test
	void anyNegativeStockBecomesMinusOne() {
		assertEquals("shop admin stock s i -1", EditCommands.stock("s", "i", -99));
	}

	@Test
	void toggleAndRemoveFormat() {
		assertEquals("shop admin toggle shop1 minecraft:stone",
				EditCommands.toggle("shop1", "minecraft:stone"));
		assertEquals("shop admin remove shop1 minecraft:stone",
				EditCommands.remove("shop1", "minecraft:stone"));
	}

	@Test
	void addFormat() {
		assertEquals("shop admin add shop1 mod:item 10 5",
				EditCommands.add("shop1", "mod:item", 10, 5));
	}

	@Test
	void allCommandsUseAdminSubtreeAndNoLeadingSlash() {
		for (String c : new String[]{EditCommands.price("s", "i", 1, 1), EditCommands.stock("s", "i", 1),
				EditCommands.toggle("s", "i"), EditCommands.remove("s", "i"), EditCommands.add("s", "i", 1, 1)}) {
			assertTrue(c.startsWith("shop admin "), c);
			assertTrue(!c.startsWith("/"), c);
		}
	}

	@Test
	void namespaceColonSurvives() {
		// H02：物品 id 带冒号必须原样传下去，不能被截断
		assertTrue(EditCommands.add("s", "minecraft:diamond_sword", 1, 1).contains("minecraft:diamond_sword"));
	}
}
