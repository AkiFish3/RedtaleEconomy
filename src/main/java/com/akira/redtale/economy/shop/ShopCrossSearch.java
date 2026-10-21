package com.akira.redtale.economy.shop;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 跨店搜索（T25）：在全部商店里找一件东西，而不是先选店再搜。
 *
 * <p>动机：服务器有 200+ 个商店、2800+ 件商品，玩家知道「我要橡木台阶」但不知道在哪家店。
 * 按店搜索要求先记住店名，等于把目录结构的成本转嫁给玩家。
 *
 * <p>本类只做<b>纯逻辑</b>（分词 / 排序 / 截断），目录扫描留在 {@link ShopCatalog}，
 * 这样排序规则可以在没有 MC 运行时的单测里验证。
 */
public final class ShopCrossSearch {

	/** 一条命中。{@code buy>0} 表示这家店能买（否则只能卖）。 */
	public record Hit(String shopId, String shopName, String itemId, String displayName,
			long buy, long sell, long stock) {

		/** 展示价：能买看买价，只能卖看卖价。 */
		public long price() {
			return buy > 0 ? buy : sell;
		}

		/** 现在能不能买到（有价且有货）。 */
		public boolean buyableNow() {
			return buy > 0 && stock != 0;
		}
	}

	/**
	 * 排序口径：<b>能立刻买的排前面</b>，然后便宜优先，最后按店名/物品 id 稳定排序。
	 *
	 * <p>为什么不是纯价格升序：缺货的 1 铜比有货的 50 铜更没用——玩家点进去只会看到「没货」。
	 */
	private static final Comparator<Hit> ORDER = Comparator
			.comparingInt((Hit h) -> h.buyableNow() ? 0 : (h.buy > 0 ? 1 : 2))
			.thenComparingLong(Hit::price)
			.thenComparing(Hit::shopId)
			.thenComparing(Hit::itemId);

	private ShopCrossSearch() {
	}

	/** 空格分词（AND），小写；空查询返回空数组。 */
	public static String[] tokens(String query) {
		if (query == null) return new String[0];
		String q = query.trim().toLowerCase(Locale.ROOT);
		if (q.isEmpty()) return new String[0];
		return q.split("\\s+");
	}

	/** 空查询不匹配任何东西（跨店搜索没有「列出全部」的语义，那是目录）。 */
	public static boolean matches(ShopSearch index, String[] tokens) {
		if (tokens == null || tokens.length == 0) return false;
		return index != null && index.matchesAll(tokens);
	}

	/** 排序 + 去重（同店同物品只留一条）+ 截断。 */
	public static List<Hit> rank(List<Hit> hits, int limit) {
		if (hits == null || hits.isEmpty() || limit <= 0) return List.of();
		// 先按「店 + 物品」去重（同一家店的文件里可能重复登记过），再去排序：
		// 排序键里有价格，重复项不会相邻，所以不能用「相邻比较」去重。
		java.util.Map<String, Hit> uniq = new java.util.LinkedHashMap<>();
		for (Hit h : hits) {
			uniq.putIfAbsent(h.shopId() + '\u0000' + h.itemId(), h);
		}
		List<Hit> sorted = new ArrayList<>(uniq.values());
		sorted.sort(ORDER);
		return sorted.size() <= limit ? sorted : new ArrayList<>(sorted.subList(0, limit));
	}
}
