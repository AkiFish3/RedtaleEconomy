package com.akira.redtale.economy.shop;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * T11：批量操作的商品 id 解析（纯函数，可单测）。
 *
 * <p>命令面是 {@code greedyString}（带 {@code :} 的 id 不能用 {@code word()}，
 * 见 {@code ItemIdArgument} 的教训），因此分隔与去重全在这里做：
 * 逗号 / 分号 / 空白都算分隔符，重复 id 只保留第一次出现的顺序。
 *
 * <p>界面上「批量上架」把 id 拼成同样的串再发命令 —— 两端共用本类，
 * 分隔符一旦不一致就会出现「界面勾了 5 件、服务端只删 1 件」这种极难复现的偏差。
 */
public final class BatchIds {

	private BatchIds() {
	}

	public static List<String> parse(String raw) {
		LinkedHashSet<String> out = new LinkedHashSet<>();
		if (raw == null) return new ArrayList<>();
		StringBuilder cur = new StringBuilder();
		for (int i = 0; i < raw.length(); i++) {
			char c = raw.charAt(i);
			boolean sep = c == ',' || c == ';' || c == '，' || c == '；' || c == '、'
					|| Character.isWhitespace(c);
			if (sep) {
				flush(cur, out);
			} else {
				cur.append(c);
			}
		}
		flush(cur, out);
		return new ArrayList<>(out);
	}

	private static void flush(StringBuilder cur, LinkedHashSet<String> out) {
		String s = cur.toString().trim();
		cur.setLength(0);
		if (!s.isEmpty()) out.add(s);
	}

	/** 反向：把一串 id 拼成命令参数（保持顺序、去重）。 */
	public static String join(Collection<String> ids) {
		if (ids == null || ids.isEmpty()) return "";
		LinkedHashSet<String> uniq = new LinkedHashSet<>();
		for (String s : ids) {
			if (s != null && !s.isBlank()) uniq.add(s.trim());
		}
		return String.join(",", uniq);
	}

	/** 商店文件里可能写 {} 之外的空白 id；上架/移除前统一剔除。 */
	public static List<String> validOnly(Collection<String> ids) {
		List<String> out = new ArrayList<>();
		if (ids == null) return out;
		for (String s : ids) {
			if (s == null) continue;
			String t = s.trim();
			if (t.isEmpty()) continue;
			out.add(t);
		}
		return out;
	}
}
