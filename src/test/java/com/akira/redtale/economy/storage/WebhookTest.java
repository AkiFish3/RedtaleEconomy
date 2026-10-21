package com.akira.redtale.economy.storage;

import com.akira.redtale.economy.shop.ShopEvents;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** T15：外部告警 —— payload 格式、URL 校验、限流、大额门槛（不真的发网络请求）。 */
class WebhookTest {

	private final List<String> sent = new CopyOnWriteArrayList<>();

	@BeforeEach
	void setUp() {
		sent.clear();
		ShopEvents.clearAll();
		Webhook.resetForTest();
		Webhook.setPosterForTest((url, body, timeout) -> {
			sent.add(body);
			return true;
		});
	}

	@AfterEach
	void tearDown() {
		ShopEvents.clearAll();
		Webhook.resetForTest();
		Webhook.setPosterForTest(null);
	}

	private static Webhook.Settings on(int perMinute, long threshold, boolean largeShop) {
		return new Webhook.Settings(true, "https://example.invalid/hook", Webhook.Format.ONEBOT,
				1000, perMinute, threshold, largeShop, true, false);
	}

	@Test
	void payloadShapesPerFormat() {
		assertEquals("{\"message\":\"[shop.large] hi\"}",
				Webhook.payload(Webhook.Format.ONEBOT, "shop.large", "hi"));
		JsonObject qq = JsonParser.parseString(Webhook.payload(Webhook.Format.QQ, "e", "t")).getAsJsonObject();
		assertEquals("[e] t", qq.get("content").getAsString(), "qq 格式同样带事件前缀，便于群里定位");
		assertEquals(0, qq.get("msg_type").getAsInt());
		JsonObject js = JsonParser.parseString(Webhook.payload(Webhook.Format.JSON, "e", "t")).getAsJsonObject();
		assertEquals("e", js.get("event").getAsString());
		assertEquals("t", js.get("text").getAsString());
		assertEquals("[e] t", Webhook.payload(Webhook.Format.PLAIN, "e", "t"));
		assertEquals("t", Webhook.payload(Webhook.Format.PLAIN, "", "t"), "空事件名不留空括号");
	}

	@Test
	void formatParseDefaultsToOnebot() {
		assertEquals(Webhook.Format.ONEBOT, Webhook.Format.parse(null));
		assertEquals(Webhook.Format.ONEBOT, Webhook.Format.parse("nope"));
		assertEquals(Webhook.Format.QQ, Webhook.Format.parse("QQ"));
		assertEquals(Webhook.Format.PLAIN, Webhook.Format.parse(" text "));
	}

	@Test
	void urlValidationRejectsNonHttp() {
		assertTrue(Webhook.validUrl("http://127.0.0.1:3000/hook"));
		assertTrue(Webhook.validUrl("https://bot.example.com/x?access_token=abc"));
		assertFalse(Webhook.validUrl(null));
		assertFalse(Webhook.validUrl(""));
		assertFalse(Webhook.validUrl("file:///etc/passwd"));
		assertFalse(Webhook.validUrl("ftp://host/x"));
		assertFalse(Webhook.validUrl("not a url"));
		assertFalse(Webhook.validUrl("http:///nohost"));
	}

	@Test
	void disabledNeverPosts() {
		Webhook.apply(Webhook.Settings.disabled());
		assertFalse(Webhook.notify("shop.large", "x"));
		assertFalse(Webhook.sendNow("shop.large", "x"));
		assertTrue(sent.isEmpty());
	}

	@Test
	void enabledButBadUrlNeverPosts() {
		Webhook.apply(new Webhook.Settings(true, "file:///tmp/x", Webhook.Format.ONEBOT, 1000, 5, 0, true, true, false));
		assertFalse(Webhook.enabled());
		assertFalse(Webhook.notify("shop.large", "x"));
		assertTrue(sent.isEmpty());
	}

	@Test
	void rateLimitDropsBeyondPerMinute() {
		Webhook.apply(on(2, 0, true));
		long now = 1_000_000L;
		assertTrue(Webhook.allow(now));
		assertTrue(Webhook.allow(now + 1_000));
		assertFalse(Webhook.allow(now + 2_000), "第 3 条应被限流丢弃");
		assertTrue(Webhook.allow(now + 61_000), "窗口滑过后应恢复");
	}

	@Test
	void eventSwitchSilencesChannel() {
		Webhook.apply(new Webhook.Settings(true, "https://example.invalid/h", Webhook.Format.ONEBOT,
				1000, 10, 0, false, true, false));
		assertFalse(Webhook.notify("shop.large", "x"), "largeShop 关闭时不应排队");
		assertTrue(Webhook.notify("alt.block", "x"));
	}

	@Test
	void largeShopThresholdFiltersLowValue() throws Exception {
		Webhook.apply(on(10, 1_000, true));
		Webhook.installShopAlerts();

		ShopEvents.fire(new ShopEvents.Transaction(UUID.randomUUID(), "s", "minecraft:dirt", 1,
				10, 10, 0, true));           // 低于阈值
		ShopEvents.fire(new ShopEvents.Transaction(UUID.randomUUID(), "s", "minecraft:diamond", 2,
				5_000, 10_000, 0, true));    // 命中
		assertTrue(waitForSends(1), "大额成交应发一条");
		assertEquals(1, sent.size(), "低于阈值的那笔不应发送");
		assertTrue(sent.get(0).contains("minecraft:diamond"));
	}

	@Test
	void maskHidesToken() {
		String masked = Webhook.mask("https://bot.q.qq.com/cgi-bin/webhook/send?access_token=SECRET123456");
		assertFalse(masked.contains("SECRET123456"));
		assertTrue(masked.contains("…"));
		assertEquals("short", Webhook.mask("short"));
	}

	@Test
	void describeNeverLeaksToken() {
		Webhook.apply(new Webhook.Settings(true,
				"https://bot.q.qq.com/cgi-bin/webhook/send?access_token=SECRET", Webhook.Format.QQ,
				1000, 5, 100, true, true, false));
		String d = Webhook.describe();
		assertTrue(d.contains("QQ"));
		assertFalse(d.contains("SECRET"));
	}

	/** 异步发送是单线程队列：轮询到最多 2 秒，避免用 sleep 假等待。 */
	private boolean waitForSends(int n) throws InterruptedException {
		for (int i = 0; i < 100; i++) {
			if (sent.size() >= n) return true;
			Thread.sleep(20);
		}
		return false;
	}
}
