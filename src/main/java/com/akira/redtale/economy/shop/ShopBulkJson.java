package com.akira.redtale.economy.shop;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * T10：商店批量导入/导出的**纯 JSON 逻辑**（不碰文件系统、不碰 Minecraft，可单测）。
 *
 * <p>批量文件就是「一个合法商店文件」：
 *
 * <pre>
 * {
 *   "_format": "redtale-shop-bulk",   // 可省（省了也按商店文件解析）
 *   "_version": 1,
 *   "shops": { "&lt;shopId&gt;": { "displayName": ..., "items": [ ... ] } }
 * }
 * </pre>
 *
 * <p>校验口径与 {@code /shop admin add} 一致（漏一条就会写出「加载时被钳制」的脏数据）：
 * <ul>
 *   <li>id 不能空、不能含 {@code / \} 或 {@code ..}、长度 ≤64（{@link ShopCatalog#createShop} 同款）</li>
 *   <li>商品 id 必须带命名空间 {@code ns:path}（H02 的教训）</li>
 *   <li>价格落在 {@code [minPrice, maxPrice]}，且过 E1 价格护栏（{@link PricePolicy}）</li>
 * </ul>
 * 单个商品不合规只记一条错误并跳过，不整店丢弃 —— 2803 件里有一条坏的也要能导进去。
 */
public final class ShopBulkJson {

	public static final String FORMAT = "redtale-shop-bulk";
	public static final int VERSION = 1;
	/** 错误列表上限：够定位问题，又不会在聊天栏刷屏。 */
	public static final int MAX_ERRORS = 40;

	/** 校验阈值（来自 {@code shop/settings.json}，测试里直接给常量）。 */
	public record Limits(long minPrice, long maxPrice, int marginPercent) {
		public static Limits none() {
			return new Limits(0, Long.MAX_VALUE, 0);
		}
	}

	/** 解析结果：{@code shops} 只含通过校验的商店与商品，{@code errors} 是给人看的原因。 */
	public record Parsed(JsonObject shops, List<String> errors) {
		public boolean ok() {
			return errors.isEmpty();
		}
	}

	/** 单店合并计数。 */
	public record Merged(int itemsNew, int itemsUpdated, int itemsUnchanged, int itemsRemoved) {
	}

	private ShopBulkJson() {
	}

	// ---------------------------------------------------------------- 解析/校验

	/**
	 * 校验并规范化一份批量文件。
	 *
	 * @param doc 已解析的 JSON 根对象（可以是 null）
	 * @param lim 价格阈值
	 */
	public static Parsed parse(JsonObject doc, Limits lim) {
		List<String> errors = new ArrayList<>();
		JsonObject out = new JsonObject();
		if (doc == null) {
			errors.add("文件内容不是 JSON 对象");
			return new Parsed(out, errors);
		}
		String format = str(doc, "_format");
		if (!format.isEmpty() && !FORMAT.equals(format)) {
			errors.add("_format 不认识：" + format + "（期望 " + FORMAT + "）");
			return new Parsed(out, errors);
		}
		JsonElement shopsEl = doc.get("shops");
		if (shopsEl == null || !shopsEl.isJsonObject()) {
			errors.add("缺少 shops 对象");
			return new Parsed(out, errors);
		}
		JsonObject shops = shopsEl.getAsJsonObject();
		if (shops.size() == 0) {
			errors.add("shops 是空的，没有可导入的商店");
			return new Parsed(out, errors);
		}
		for (Map.Entry<String, JsonElement> e : shops.entrySet()) {
			String shopId = e.getKey();
			if (!validShopId(shopId)) {
				errors.add(shopId + "：商店 id 非法（不能含 / \\ 或 ..，长度 1~64）");
				continue;
			}
			if (!e.getValue().isJsonObject()) {
				errors.add(shopId + "：商店定义不是对象");
				continue;
			}
			JsonObject src = e.getValue().getAsJsonObject();
			JsonObject shop = new JsonObject();
			String name = str(src, "displayName");
			shop.addProperty("displayName", name.isEmpty() ? shopId : name);
			String icon = str(src, "icon");
			if (icon.isEmpty()) {
				shop.addProperty("icon", "minecraft:chest");
			} else if (icon.indexOf(':') < 0) {
				errors.add(shopId + "：icon 缺命名空间：" + icon);
				shop.addProperty("icon", "minecraft:chest");
			} else {
				shop.addProperty("icon", icon);
			}
			shop.addProperty("requirePremium", bool(src, "requirePremium"));

			JsonArray cats = new JsonArray();
			JsonElement catsEl = src.get("categories");
			if (catsEl != null && catsEl.isJsonArray()) {
				for (JsonElement c : catsEl.getAsJsonArray()) {
					if (c.isJsonPrimitive()) {
						String s = c.getAsString().trim();
						if (!s.isEmpty()) cats.add(s);
					}
				}
			}
			shop.add("categories", cats);

			JsonArray items = new JsonArray();
			JsonElement itemsEl = src.get("items");
			if (itemsEl != null && !itemsEl.isJsonArray()) {
				errors.add(shopId + "：items 不是数组");
			} else if (itemsEl != null) {
				java.util.Set<String> seen = new java.util.LinkedHashSet<>();
				for (JsonElement el : itemsEl.getAsJsonArray()) {
					if (!el.isJsonObject()) {
						errors.add(shopId + "：有一件商品不是对象");
						continue;
					}
					JsonObject raw = el.getAsJsonObject();
					String itemId = str(raw, "id");
					if (itemId.isEmpty()) {
						errors.add(shopId + "：有商品缺 id");
						continue;
					}
					if (itemId.indexOf(':') < 0) {
						errors.add(shopId + "/" + itemId + "：物品 id 缺命名空间（应写 ns:path）");
						continue;
					}
					if (!seen.add(itemId)) {
						errors.add(shopId + "/" + itemId + "：文件内重复，只取第一条");
						continue;
					}
					long buy = num(raw, "buy", 0);
					long sell = num(raw, "sell", 0);
					if (buy < 0 || sell < 0) {
						errors.add(shopId + "/" + itemId + "：价格不能为负");
						continue;
					}
					if (buy < lim.minPrice() || buy > lim.maxPrice()
							|| sell < lim.minPrice() || sell > lim.maxPrice()) {
						errors.add(shopId + "/" + itemId + "：价格超出 [" + lim.minPrice()
								+ ", " + lim.maxPrice() + "]");
						continue;
					}
					if (sell > 0 && buy > 0 && PricePolicy.violates(buy, sell, lim.marginPercent())) {
						errors.add(shopId + "/" + itemId + "：卖价 " + sell + " 必须低于买价 " + buy
								+ "（价差至少 " + lim.marginPercent() + "%）");
						continue;
					}
					JsonObject it = new JsonObject();
					it.addProperty("id", itemId);
					// 只有文件里**写了**的字段才带上：手写补丁只改价格时不会把库存冲回 -1
					if (raw.has("category")) it.addProperty("category", str(raw, "category"));
					it.addProperty("buy", buy);
					it.addProperty("sell", sell);
					if (raw.has("stock")) it.addProperty("stock", num(raw, "stock", -1));
					if (raw.has("dailyBuyLimit")) {
						it.addProperty("dailyBuyLimit", num(raw, "dailyBuyLimit", -1));
					}
					if (raw.has("enabled")) it.addProperty("enabled", bool(raw, "enabled"));
					String itemName = str(raw, "name");
					if (!itemName.isEmpty()) it.addProperty("name", itemName);
					items.add(it);
				}
			}
			shop.add("items", items);
			out.add(shopId, shop);
		}
		return new Parsed(out, errors);
	}

	// ---------------------------------------------------------------- 合并

	/**
	 * 把解析后的一店合并进目标文件根对象（就地修改）。
	 *
	 * <p>只覆盖文件里**写了**的字段：手写补丁只改价格时不会把库存冲回 -1。
	 *
	 * @param fileRoot     目标商店文件根（{@code {"shops":{...}}}）
	 * @param shopId       商店 id
	 * @param incoming     {@link #parse} 规范化后的单店对象
	 * @param replaceItems true = 文件里没有的商品视为下架（从文件移除，写前已备份）
	 */
	public static Merged merge(JsonObject fileRoot, String shopId, JsonObject incoming,
			boolean replaceItems) {
		JsonObject shops;
		JsonElement shopsEl = fileRoot.get("shops");
		if (shopsEl == null || !shopsEl.isJsonObject()) {
			shops = new JsonObject();
			fileRoot.add("shops", shops);
		} else {
			shops = shopsEl.getAsJsonObject();
		}
		JsonObject shop;
		JsonElement shopEl = shops.get(shopId);
		if (shopEl == null || !shopEl.isJsonObject()) {
			shop = new JsonObject();
			shops.add(shopId, shop);
		} else {
			shop = shopEl.getAsJsonObject();
		}
		copyIfPresent(incoming, shop, "displayName");
		copyIfPresent(incoming, shop, "icon");
		copyIfPresent(incoming, shop, "requirePremium");

		// 分类取并集：导入一份新分类表不应把老分类抹掉（目录树会空）
		JsonArray cats = arrayOf(shop, "categories");
		java.util.Set<String> catSeen = new java.util.LinkedHashSet<>();
		for (JsonElement c : cats) {
			if (c.isJsonPrimitive()) catSeen.add(c.getAsString());
		}
		for (JsonElement c : arrayOf(incoming, "categories")) {
			if (c.isJsonPrimitive() && catSeen.add(c.getAsString())) cats.add(c.getAsString());
		}
		shop.add("categories", cats);

		if (!shop.has("items") || !shop.get("items").isJsonArray()) shop.add("items", new JsonArray());
		JsonArray items = shop.getAsJsonArray("items");
		Map<String, JsonObject> index = new LinkedHashMap<>();
		for (JsonElement el : items) {
			if (el.isJsonObject()) index.put(str(el.getAsJsonObject(), "id"), el.getAsJsonObject());
		}

		int nNew = 0;
		int nUpdated = 0;
		int nSame = 0;
		java.util.Set<String> inFile = new java.util.LinkedHashSet<>();
		for (JsonElement el : arrayOf(incoming, "items")) {
			if (!el.isJsonObject()) continue;
			JsonObject src = el.getAsJsonObject();
			String id = str(src, "id");
			if (id.isEmpty()) continue;
			inFile.add(id);
			JsonObject dst = index.get(id);
			if (dst == null) {
				items.add(src.deepCopy());
				nNew++;
				continue;
			}
			String before = dst.toString();
			for (String k : new String[] { "category", "buy", "sell", "stock", "dailyBuyLimit",
					"enabled", "name" }) {
				if (src.has(k)) dst.add(k, src.get(k));
			}
			if (before.equals(dst.toString())) nSame++;
			else nUpdated++;
		}

		int nRemoved = 0;
		if (replaceItems) {
			JsonArray kept = new JsonArray();
			for (JsonElement el : items) {
				if (el.isJsonObject() && !inFile.contains(str(el.getAsJsonObject(), "id"))) {
					nRemoved++;
					continue;
				}
				kept.add(el);
			}
			if (nRemoved > 0) shop.add("items", kept);
		}
		return new Merged(nNew, nUpdated, nSame, nRemoved);
	}

	// ---------------------------------------------------------------- 小工具

	/** 与 {@link ShopCatalog#createShop} 同款 id 约束。 */
	public static boolean validShopId(String id) {
		if (id == null) return false;
		if (id.isEmpty() || id.length() > 64) return false;
		return !id.contains("/") && !id.contains("\\") && !id.contains("..");
	}

	private static void copyIfPresent(JsonObject from, JsonObject to, String key) {
		if (from.has(key)) to.add(key, from.get(key));
	}

	private static JsonArray arrayOf(JsonObject o, String key) {
		JsonElement el = o.get(key);
		return el != null && el.isJsonArray() ? el.getAsJsonArray() : new JsonArray();
	}

	private static String str(JsonObject o, String k) {
		JsonElement el = o.get(k);
		return el != null && el.isJsonPrimitive() ? el.getAsString() : "";
	}

	private static long num(JsonObject o, String k, long def) {
		JsonElement el = o.get(k);
		if (el == null || !el.isJsonPrimitive()) return def;
		try {
			return el.getAsLong();
		} catch (Exception e) {
			return def;
		}
	}

	private static boolean bool(JsonObject o, String k) {
		JsonElement el = o.get(k);
		if (el == null || !el.isJsonPrimitive()) return false;
		try {
			return el.getAsBoolean();
		} catch (Exception e) {
			return false;
		}
	}
}
