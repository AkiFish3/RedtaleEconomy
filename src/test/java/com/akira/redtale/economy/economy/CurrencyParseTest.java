package com.akira.redtale.economy.economy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * B04：{@link Currency#parse(String)} 的边界。
 *
 * <p>只覆盖<b>不依赖配置</b>的分支（纯数字 / 非法输入）。{@code 1g20s5c} 这类带单位
 * 的解析要读 economy.json 的换算比例，属于运行时验证，见 _MOD_TASKS.md 遗留项。
 */
class CurrencyParseTest {

	@Test
	void plainDigitsAreCopper() {
		assertEquals(0L, Currency.parse("0"));
		assertEquals(123L, Currency.parse("123"));
		assertEquals(1L, Currency.parse("  1  "));
	}

	@Test
	void nullAndBlankRejected() {
		assertEquals(-1L, Currency.parse(null));
		assertEquals(-1L, Currency.parse(""));
		assertEquals(-1L, Currency.parse("   "));
	}

	@Test
	void malformedRejected() {
		assertEquals(-1L, Currency.parse("abc"));
		assertEquals(-1L, Currency.parse("-5"));
		assertEquals(-1L, Currency.parse("1x"));
		assertEquals(-1L, Currency.parse("1.5"));
		assertEquals(-1L, Currency.parse("g"));
	}

	@Test
	void overflowRejected() {
		assertEquals(-1L, Currency.parse("99999999999999999999999999"));
	}

}
