package com.akira.redtale.economy.shop;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** B21：错误码必须稳定、唯一，且每个 key 都有中英翻译。 */
class ShopErrorsTest {

	@Test
	void codesAreSnakeCaseAndUnique() {
		for (String code : ShopErrors.codes()) {
			assertEquals(code.toLowerCase(java.util.Locale.ROOT), code, "码必须小写：" + code);
			assertTrue(code.matches("[a-z0-9_]+"), "码必须是 snake_case：" + code);
		}
		// 同一个 key 必须映射到同一个码（防止大小写写歪导致两个码）
		assertEquals(ShopErrors.codeOf("shop.outOfStock"), ShopErrors.codeOf("shop.outOfStock"));
		assertNotEquals(ShopErrors.codeOf("shop.outOfStock"), ShopErrors.codeOf("shop.notEnoughStock"));
	}

	@Test
	void unknownKeyStillGetsStableCode() {
		assertEquals("some_new_error", ShopErrors.codeOf("shop.someNewError"));
		assertEquals("unknown", ShopErrors.codeOf(null));
		assertEquals("unknown", ShopErrors.codeOf(""));
	}

	@Test
	void everyKeyHasBothTranslations() throws Exception {
		JsonObject zh = lang("zh_cn");
		JsonObject en = lang("en_us");
		for (String key : ShopErrors.knownMessageKeys()) {
			assertTrue(zh.has(key), "zh_cn 缺 " + key);
			assertTrue(en.has(key), "en_us 缺 " + key);
		}
	}

	private static JsonObject lang(String locale) throws Exception {
		try (InputStream in = ShopErrorsTest.class.getResourceAsStream(
				"/assets/redtale_economy/lang/" + locale + ".json")) {
			assertTrue(in != null, "找不到语言文件 " + locale);
			return JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8))
					.getAsJsonObject();
		}
	}
}
