package com.akira.redtale.economy.shop;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** T10：批量导入的校验与合并（纯 JSON，不碰盘）。 */
class ShopBulkJsonTest {

	private static final ShopBulkJson.Limits LIM = new ShopBulkJson.Limits(0, 1_000_000, 5);

	private static JsonObject doc(String json) {
		return JsonParser.parseString(json).getAsJsonObject();
	}

	private static JsonObject item(String id, long buy, long sell) {
		JsonObject o = new JsonObject();
		o.addProperty("id", id);
		o.addProperty("buy", buy);
		o.addProperty("sell", sell);
		return o;
	}

	private static JsonObject single(String shopId, JsonArray items) {
		JsonObject shop = new JsonObject();
		shop.addProperty("displayName", "店");
		shop.add("items", items);
		JsonObject shops = new JsonObject();
		shops.add(shopId, shop);
		JsonObject root = new JsonObject();
		root.addProperty("_format", ShopBulkJson.FORMAT);
		root.add("shops", shops);
		return root;
	}

	@Test
	void acceptsValidFileAndFillsDefaults() {
		JsonArray items = new JsonArray();
		items.add(item("minecraft:stone", 100, 90));
		ShopBulkJson.Parsed p = ShopBulkJson.parse(single("shop_a", items), LIM);
		assertTrue(p.ok(), p.errors().toString());
		JsonObject it = p.shops().getAsJsonObject("shop_a").getAsJsonArray("items")
				.get(0).getAsJsonObject();
		assertEquals(100, it.get("buy").getAsLong());
		// 文件没写 stock/dailyBuyLimit 就不带这两个键（避免手写补丁把库存冲回 -1）
		assertFalse(it.has("stock"));
		assertFalse(it.has("dailyBuyLimit"));
	}

	@Test
	void rejectsItemWithoutNamespace() {
		JsonArray items = new JsonArray();
		items.add(item("stone", 100, 90));
		ShopBulkJson.Parsed p = ShopBulkJson.parse(single("shop_a", items), LIM);
		assertEquals(1, p.errors().size());
		assertTrue(p.errors().get(0).contains("命名空间"));
		assertEquals(0, p.shops().getAsJsonObject("shop_a").getAsJsonArray("items").size());
	}

	@Test
	void rejectsSellAboveBuy() {
		JsonArray items = new JsonArray();
		items.add(item("minecraft:stone", 100, 100));      // 价差 0% < 5%
		items.add(item("minecraft:dirt", 100, 90));        // 合法
		ShopBulkJson.Parsed p = ShopBulkJson.parse(single("shop_a", items), LIM);
		assertEquals(1, p.errors().size());
		assertTrue(p.errors().get(0).contains("卖价"));
		assertEquals(1, p.shops().getAsJsonObject("shop_a").getAsJsonArray("items").size());
	}

	@Test
	void duplicateItemsKeepFirst() {
		JsonArray items = new JsonArray();
		items.add(item("minecraft:stone", 100, 90));
		items.add(item("minecraft:stone", 50, 40));
		ShopBulkJson.Parsed p = ShopBulkJson.parse(single("shop_a", items), LIM);
		assertEquals(1, p.errors().size());
		JsonArray kept = p.shops().getAsJsonObject("shop_a").getAsJsonArray("items");
		assertEquals(1, kept.size());
		assertEquals(100, kept.get(0).getAsJsonObject().get("buy").getAsLong());
	}

	@Test
	void rejectsUnknownFormatAndBadShopId() {
		JsonObject bad = single("shop_a", new JsonArray());
		bad.addProperty("_format", "something-else");
		assertTrue(ShopBulkJson.parse(bad, LIM).errors().get(0).contains("_format"));

		JsonObject badId = single("../escape", new JsonArray());
		ShopBulkJson.Parsed p = ShopBulkJson.parse(badId, LIM);
		assertFalse(p.ok());
		assertTrue(p.errors().get(0).contains("id 非法"));
	}

	@Test
	void mergeAddsNewItemsAndCountsSame() {
		JsonObject target = doc("{\"shops\":{\"s\":{\"displayName\":\"老名\",\"items\":["
				+ "{\"id\":\"minecraft:stone\",\"buy\":100,\"sell\":90,\"stock\":5}]}}}");
		JsonArray items = new JsonArray();
		items.add(item("minecraft:stone", 100, 90));
		JsonObject incoming = ShopBulkJson.parse(single("s", items), LIM)
				.shops().getAsJsonObject("s");

		ShopBulkJson.Merged m = ShopBulkJson.merge(target, "s", incoming, false);
		assertEquals(0, m.itemsNew());
		assertEquals(1, m.itemsUnchanged());
		assertEquals(5, target.getAsJsonObject("shops").getAsJsonObject("s")
				.getAsJsonArray("items").get(0).getAsJsonObject().get("stock").getAsLong());
	}

	@Test
	void mergeUpdatesOnlyProvidedFields() {
		JsonObject target = doc("{\"shops\":{\"s\":{\"items\":["
				+ "{\"id\":\"minecraft:stone\",\"buy\":100,\"sell\":90,\"stock\":5}]}}}");
		JsonObject incoming = doc("{\"displayName\":\"s\",\"items\":["
				+ "{\"id\":\"minecraft:stone\",\"buy\":120,\"sell\":100}]}");
		ShopBulkJson.Merged m = ShopBulkJson.merge(target, "s", incoming, false);
		assertEquals(1, m.itemsUpdated());
		JsonObject it = target.getAsJsonObject("shops").getAsJsonObject("s")
				.getAsJsonArray("items").get(0).getAsJsonObject();
		assertEquals(120, it.get("buy").getAsLong());
		assertEquals(100, it.get("sell").getAsLong());
		assertEquals(5, it.get("stock").getAsLong());
	}

	@Test
	void mergeReplaceRemovesAbsentItems() {
		JsonObject target = doc("{\"shops\":{\"s\":{\"items\":["
				+ "{\"id\":\"minecraft:stone\",\"buy\":100,\"sell\":90},"
				+ "{\"id\":\"minecraft:dirt\",\"buy\":10,\"sell\":5}]}}}");
		JsonArray items = new JsonArray();
		items.add(item("minecraft:stone", 100, 90));
		JsonObject incoming = ShopBulkJson.parse(single("s", items), LIM)
				.shops().getAsJsonObject("s");

		ShopBulkJson.Merged m = ShopBulkJson.merge(target, "s", incoming, true);
		assertEquals(0, m.itemsNew());
		assertEquals(1, m.itemsRemoved());
		JsonArray left = target.getAsJsonObject("shops").getAsJsonObject("s")
				.getAsJsonArray("items");
		assertEquals(1, left.size());
		assertEquals("minecraft:stone", left.get(0).getAsJsonObject().get("id").getAsString());
	}

	@Test
	void mergeCreatesShopAndUnionsCategories() {
		JsonObject target = doc("{\"shops\":{}}");
		JsonObject incoming = doc("{\"displayName\":\"新店\",\"icon\":\"minecraft:chest\","
				+ "\"categories\":[\"建材\"],\"items\":[]}");
		ShopBulkJson.Merged m = ShopBulkJson.merge(target, "newbie", incoming, false);
		assertEquals(0, m.itemsNew());
		JsonObject shop = target.getAsJsonObject("shops").getAsJsonObject("newbie");
		assertNotNull(shop);
		assertEquals("新店", shop.get("displayName").getAsString());
		assertEquals(1, shop.getAsJsonArray("categories").size());

		ShopBulkJson.merge(target, "newbie", incoming, false);
		assertEquals(1, shop.getAsJsonArray("categories").size(), "重复导入不应重复堆分类");
	}

	@Test
	void shopIdValidationMatchesCreateShop() {
		assertTrue(ShopBulkJson.validShopId("testshop"));
		assertTrue(ShopBulkJson.validShopId("a.b-c_1"));
		assertFalse(ShopBulkJson.validShopId(""));
		assertFalse(ShopBulkJson.validShopId("a/b"));
		assertFalse(ShopBulkJson.validShopId(".."));
		assertFalse(ShopBulkJson.validShopId("x".repeat(65)));
	}
}
