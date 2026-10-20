package com.akira.redtale.economy.shop;

import com.akira.redtale.economy.config.ConfigManager;
import com.akira.redtale.economy.config.ShopConfig;
import com.akira.redtale.economy.net.ShopProtocol;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B18：2803 条商品的分页压测。
 *
 * <p>测的是生产路径（{@link ShopCatalog#count} / {@link ShopCatalog#slice} + {@link ShopProtocol#clampPage}），
 * 不是另写一套模拟。断言给的是<b>宽松上界</b>（整库翻完 < 1000ms），只用来抓「O(n²) 或每次翻页重扫全库」
 * 这类退化，不断言具体耗时（机器差异太大，写死数字只会变成噪音）。
 */
class ShopPagingPerfTest {

	private static final int ITEMS = 2803;
	private static final int PAGE_SIZE = 40;

	@TempDir
	static Path dir;

	@BeforeAll
	static void load() throws Exception {
		Path shops = dir.resolve("config").resolve("shop").resolve("shops");
		Files.createDirectories(shops);
		Files.writeString(dir.resolve("config").resolve(ShopConfig.FILE),
				"{\"_version\":1,\"pageSize\":40}", StandardCharsets.UTF_8);

		JsonArray items = new JsonArray();
		for (int i = 0; i < ITEMS; i++) {
			JsonObject it = new JsonObject();
			it.addProperty("id", "minecraft:item_" + i);
			it.addProperty("category", "cat" + (i % 7));
			it.addProperty("buy", 10 + i % 90);
			it.addProperty("sell", 5 + i % 40);
			it.addProperty("stock", -1);
			items.add(it);
		}
		JsonObject shop = new JsonObject();
		shop.addProperty("displayName", "压测商店");
		shop.add("items", items);
		JsonObject shopsObj = new JsonObject();
		shopsObj.add("perf", shop);
		JsonObject root = new JsonObject();
		root.add("shops", shopsObj);
		Files.writeString(shops.resolve("perf.json"), root.toString(), StandardCharsets.UTF_8);

		ConfigManager.createAt(dir.resolve("config"));
		ConfigManager.get().define(ShopConfig.FILE, ShopConfig.SCHEMA);
		ShopConfig.store().load();
		ShopCatalog.reload();
	}

	@AfterAll
	static void tearDown() {
		ConfigManager.clearInstance();
	}

	@Test
	void everyPageIsServedAndComplete() {
		ShopCatalog c = ShopCatalog.create();
		long t0 = System.nanoTime();
		int total = c.count("perf", "");
		long tCount = System.nanoTime() - t0;
		assertEquals(ITEMS, total);

		int pages = ShopProtocol.totalPages(total, PAGE_SIZE);
		assertEquals((ITEMS + PAGE_SIZE - 1) / PAGE_SIZE, pages);

		java.util.Set<String> seen = new java.util.HashSet<>();
		long t0b = System.nanoTime();
		for (int p = 0; p < pages; p++) {
			int clamped = ShopProtocol.clampPage(p, total, PAGE_SIZE);
			assertEquals(p, clamped);
			List<ShopProtocol.Entry> rows = c.slice("perf", "", p * PAGE_SIZE, PAGE_SIZE);
			assertTrue(rows.size() <= PAGE_SIZE);
			rows.forEach(e -> seen.add(e.itemId()));
		}
		long tSlice = System.nanoTime() - t0b;
		assertEquals(ITEMS, seen.size(), "翻完所有页必须不重不漏");

		System.out.printf("[B18] items=%d pages=%d count=%.1fms fullScan=%.1fms%n",
				ITEMS, pages, tCount / 1e6, tSlice / 1e6);
		assertTrue(tSlice / 1e6 < 1000.0, "翻完 " + pages + " 页超过 1000ms，疑似退化：" + tSlice / 1e6 + "ms");
	}

	@Test
	void searchNarrowsAndStaysPaged() {
		ShopCatalog c = ShopCatalog.create();
		int hits = c.count("perf", "item_1");
		assertTrue(hits > 0 && hits < ITEMS);
		List<ShopProtocol.Entry> first = c.slice("perf", "item_1", 0, PAGE_SIZE);
		assertTrue(first.size() <= PAGE_SIZE);
		assertTrue(first.stream().allMatch(e -> e.itemId().contains("item_1")));
	}

	@Test
	void outOfRangePageReturnsEmptyNotCrash() {
		ShopCatalog c = ShopCatalog.create();
		assertTrue(c.slice("perf", "", ITEMS + 100, PAGE_SIZE).isEmpty());
		assertTrue(c.slice("perf", "", -5, PAGE_SIZE).isEmpty());
		assertEquals(0, c.count("nope", ""), "不存在的商店返回 0 而不是异常");
	}
}
