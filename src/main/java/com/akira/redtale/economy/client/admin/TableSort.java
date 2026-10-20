package com.akira.redtale.economy.client.admin;

import com.akira.redtale.economy.net.ShopProtocol;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 表格编辑器的排序/过滤（R16）。
 *
 * <p>抽成纯函数是为了能单测：界面里点表头排序，如果比较器写反了，
 * 表现只是「排序好像有点怪」，很难发现；这里钉死成测试。
 */
public final class TableSort {

	private TableSort() {
	}

	public static List<ShopProtocol.Entry> sort(List<ShopProtocol.Entry> in, String key, boolean asc) {
		List<ShopProtocol.Entry> out = new ArrayList<>(in);
		Comparator<ShopProtocol.Entry> c = switch (key == null ? "" : key) {
			case "buy" -> Comparator.comparingLong(ShopProtocol.Entry::buyPrice);
			case "sell" -> Comparator.comparingLong(ShopProtocol.Entry::sellPrice);
			case "stock" -> Comparator.comparingLong(ShopProtocol.Entry::stock);
			default -> Comparator.comparing(e -> e.itemId().toLowerCase(Locale.ROOT));
		};
		out.sort(asc ? c : c.reversed());
		return out;
	}

	public static List<ShopProtocol.Entry> filter(List<ShopProtocol.Entry> in, String query) {
		if (query == null || query.isBlank()) return new ArrayList<>(in);
		String q = query.trim().toLowerCase(Locale.ROOT);
		List<ShopProtocol.Entry> out = new ArrayList<>();
		for (ShopProtocol.Entry e : in) {
			if (e.itemId().toLowerCase(Locale.ROOT).contains(q)) out.add(e);
		}
		return out;
	}
}
