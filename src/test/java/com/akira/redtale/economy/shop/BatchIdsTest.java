package com.akira.redtale.economy.shop;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** T11：批量操作 id 解析（纯函数）。命令面与界面共用同一套分隔规则。 */
class BatchIdsTest {

	@Test
	void commaAndSpaceBothWork() {
		assertEquals(List.of("minecraft:apple", "minecraft:bread", "coc:chair"),
				BatchIds.parse("minecraft:apple,minecraft:bread coc:chair"));
	}

	@Test
	void chineseSeparatorsAndDupes() {
		// 中文逗号/顿号常从 Excel 粘过来；重复 id 只保留第一次出现的位置
		assertEquals(List.of("a:b", "c:d"), BatchIds.parse("a:b，c:d、a:b ； c:d"));
	}

	@Test
	void emptyAndNullAreEmpty() {
		assertTrue(BatchIds.parse(null).isEmpty());
		assertTrue(BatchIds.parse("   , ;  ").isEmpty());
	}

	@Test
	void joinRoundTripsThroughParse() {
		String joined = BatchIds.join(List.of("a:b", "c:d", "a:b"));
		assertEquals("a:b,c:d", joined);
		assertEquals(List.of("a:b", "c:d"), BatchIds.parse(joined));
	}

	@Test
	void idWithNamespaceColonSurvives() {
		// 冒号是 id 的一部分，不是分隔符（这条如果坏了，批量上架会去上架一个叫 minecraft 的物品）
		assertEquals(List.of("minecraft:diamond_sword"), BatchIds.parse("minecraft:diamond_sword"));
	}
}
