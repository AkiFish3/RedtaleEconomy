package com.akira.redtale.economy.module;

import com.akira.redtale.economy.module.impl.TitleSlots;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** T60 多槽位的纯函数规则（无 MC 依赖，可直接跑）。 */
class TitleSlotsTest {

	private static final List<String> NONE = List.of();

	@Test
	void normalizeDropsBlankAndDuplicate() {
		assertEquals(List.of("A", "B"), TitleSlots.normalize(List.of(" A ", "", "A", "B", "B"), 3));
	}

	@Test
	void normalizeTruncatesToSlots() {
		assertEquals(List.of("A", "B"), TitleSlots.normalize(List.of("A", "B", "C"), 2));
		assertEquals(NONE, TitleSlots.normalize(List.of("A"), 0));
	}

	@Test
	void equipAppendsWhenSlotNegative() {
		assertEquals(List.of("A", "B"), TitleSlots.equip(List.of("A"), "B", -1));
	}

	@Test
	void equipReplacesLastWhenFull() {
		assertEquals(List.of("A", "B", "Z"), TitleSlots.equip(List.of("A", "B", "C"), "Z", -1));
	}

	@Test
	void equipMovesExistingTitleInsteadOfDuplicating() {
		// 已在槽里的称号再装一次 ⇒ 挪位置，不出现两个
		List<String> out = TitleSlots.equip(List.of("A", "B"), "B", 0);
		assertEquals(List.of("B", "A"), out);
		assertEquals(2, out.size());
	}

	@Test
	void equipHonoursExplicitSlot() {
		assertEquals(List.of("A", "Z", "B"), TitleSlots.equip(List.of("A", "B"), "Z", 1));
	}

	@Test
	void unequipByIndex() {
		assertEquals(List.of("A"), TitleSlots.unequip(List.of("A", "B"), 1));
		assertEquals(List.of("A", "B"), TitleSlots.unequip(List.of("A", "B"), 5));
	}

	@Test
	void unequipByText() {
		assertEquals(List.of("A"), TitleSlots.unequip(List.of("A", "B"), "B"));
	}

	@Test
	void joinUsesSeparatorAndSkipsEmpty() {
		assertEquals("A·B", TitleSlots.join(List.of("A", "", "B"), "·"));
		assertEquals("", TitleSlots.join(NONE, "·"));
		assertEquals("A|B", TitleSlots.join(List.of("A", "B"), "|"));
		assertEquals("A·B", TitleSlots.join(List.of("A", "B"), ""), "空分隔符回落默认");
	}

	@Test
	void clampCapsAtHardLimit() {
		assertTrue(TitleSlots.clampSlots(99) <= TitleSlots.MAX_SLOTS);
		assertEquals(0, TitleSlots.clampSlots(-3));
	}
}
