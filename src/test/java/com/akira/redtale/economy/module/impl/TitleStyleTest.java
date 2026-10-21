package com.akira.redtale.economy.module.impl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** T23：颜色解析 / 插值 / 聊天标签。纯函数，不碰 MC。 */
class TitleStyleTest {

	@Test
	void parseAcceptsBothForms() {
		assertEquals(0xFFD700, TitleStyle.parse("#FFD700", -1));
		assertEquals(0xFFD700, TitleStyle.parse("ffd700", -1));
		assertEquals(0x8A2BE2, TitleStyle.parse("  #8a2be2  ", -1));
	}

	@Test
	void parseRejectsGarbage() {
		assertEquals(-1, TitleStyle.parse(null, -1));
		assertEquals(-1, TitleStyle.parse("#12345", -1));
		assertEquals(-1, TitleStyle.parse("gold", -1));
		assertEquals(-1, TitleStyle.parse("#GGGGGG", -1));
	}

	@Test
	void hexRoundTrip() {
		assertEquals("#FFD700", TitleStyle.hex(0xFFD700));
		assertEquals("#000000", TitleStyle.hex(0));
		assertEquals("#FFFFFF", TitleStyle.hex(-1));           // 高位被裁掉，只输出 6 位
		assertEquals(TitleStyle.parse("#123ABC", -1), 0x123ABC);
	}

	@Test
	void interpolationHitsBothEnds() {
		assertEquals(0xFF0000, TitleStyle.at(true, 0xFF0000, 0x0000FF, 0, 5));
		assertEquals(0x0000FF, TitleStyle.at(true, 0xFF0000, 0x0000FF, 4, 5));
		int mid = TitleStyle.at(true, 0xFF0000, 0x0000FF, 2, 5);
		assertEquals(0x800080, mid);
		// 单字符 / 纯色都返回起始色
		assertEquals(0xFF0000, TitleStyle.at(true, 0xFF0000, 0x0000FF, 0, 1));
		assertEquals(0xFF0000, TitleStyle.at(false, 0xFF0000, 0x0000FF, 3, 8));
	}

	@Test
	void solidChatUsesOneTag() {
		String s = TitleStyle.chat("工匠", false, 0xFFD700, 0x8A2BE2);
		assertEquals("<c:#FFD700>工匠</c>", s);
	}

	@Test
	void gradientChatIsPerCharacter() {
		String s = TitleStyle.chat("abc", true, 0xFF0000, 0x0000FF);
		assertTrue(s.startsWith("<c:#FF0000>a</c>"));
		assertTrue(s.endsWith("<c:#0000FF>c</c>"));
		assertEquals(3, count(s, "<c:#"));
	}

	@Test
	void playerSuppliedTagsAreEscaped() {
		String s = TitleStyle.chat("<c:red>x", false, 0xFFD700, 0x8A2BE2);
		assertFalse(s.contains("<c:red>"));
		assertTrue(s.contains("\\<c:red\\>"));
		assertEquals("<c:red>x", TitleStyle.strip(s));
	}

	@Test
	void emptyTextProducesEmpty() {
		assertEquals("", TitleStyle.chat("", true, 0xFF0000, 0x0000FF));
		assertEquals("", TitleStyle.chat(null, false, 0xFF0000, 0x0000FF));
	}

	private static int count(String hay, String needle) {
		int n = 0;
		int i = 0;
		while ((i = hay.indexOf(needle, i)) >= 0) {
			n++;
			i += needle.length();
		}
		return n;
	}
}
