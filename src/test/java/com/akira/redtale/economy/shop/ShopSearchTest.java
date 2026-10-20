package com.akira.redtale.economy.shop;

import com.akira.redtale.economy.util.Pinyin;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** U11：搜索键（id / 分类 / 配置名 / 显示名 / 拼音首字母 / 全拼）。 */
class ShopSearchTest {

	private static final String[] T = {"oak"};

	@Test
	void matchesIdWithAndWithoutSeparators() {
		ShopSearch s = ShopSearch.of("minecraft:oak_stairs", "", "", "");
		assertTrue(s.matches("oak"));
		assertTrue(s.matches("oak_stairs"));
		assertTrue(s.matches("oakstairs"), "下划线应能被忽略");
		assertTrue(s.matches("stairs"));
		assertFalse(s.matches("birch"));
	}

	@Test
	void matchesChineseCategoryByLiteralAndPinyin() {
		ShopSearch s = ShopSearch.of("coc:chair_wood", "椅子", "", "");
		assertTrue(s.matches("椅子"), "中文原文命中");
		assertTrue(s.matches("yz"), "拼音首字母");
		assertTrue(s.matches("yizi"), "全拼");
		assertFalse(s.matches("zt"), "别的首字母不应命中");
	}

	@Test
	void matchesConfiguredNameAndServerDisplayName() {
		ShopSearch byName = ShopSearch.of("coc:stairs_wood", "", "橡木台阶", "");
		assertTrue(byName.matches("橡木"));
		assertTrue(byName.matches("xmtj"), "配置名的首字母");

		ShopSearch byDisplay = ShopSearch.of("minecraft:oak_stairs", "", "", "Oak Stairs");
		assertTrue(byDisplay.matches("stairs"), "显示名（en_us）也要命中");
		assertTrue(byDisplay.matches("oak"));
	}

	@Test
	void multiTokenIsAnd() {
		ShopSearch s = ShopSearch.of("minecraft:oak_stairs", "台阶", "", "");
		assertTrue(s.matchesAll(new String[]{"oak", "台阶"}), "「oak 台阶」应命中");
		assertTrue(s.matchesAll(new String[]{"oak", "tj"}), "「oak tj」也应命中");
		assertFalse(s.matchesAll(new String[]{"oak", "birch"}));
		assertFalse(s.matchesAll(new String[]{"birch", "tj"}));
	}

	@Test
	void noChineseMeansNoPinyinBranch() {
		ShopSearch s = ShopSearch.of("minecraft:stone", "stone", "", "");
		assertFalse(s.hasPinyin(), "没有中文就没有拼音索引");
		assertFalse(s.matches("s t"), "不该凭空生成拼音串");
	}

	@Test
	void pinyinTableIsBundled() {
		assertTrue(Pinyin.ready(), "/redtale_pinyin.tsv 必须在 classpath 上（专用服务端靠它）");
		assertTrue(Pinyin.size() > 6000, "GB2312 常用字应 >= 6000，实际 " + Pinyin.size());
		assertEquals("xmtj", Pinyin.initials("橡木台阶"));
		assertEquals("xiangmutaijie", Pinyin.full("橡木台阶"));
		assertEquals("", Pinyin.initials(""));
	}
}
