package com.akira.redtale.economy.shop;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

/**
 * 商店目录树（U11 §45.2 的多级目录）。
 *
 * <p>没有独立的树配置：树的形状<b>就是</b>商品 {@code category} 字段本身。
 * 用 {@code /} 分段即多级，例如 {@code 建材/木材/台阶} ⇒ 根 → 建材 → 木材 → 台阶。
 * 这样服主只要在商品里写路径就能长出树，不需要再维护一份会跟商品不同步的目录表。
 *
 * <p>纯逻辑、零 MC 依赖，服务端与客户端共用同一套判定（客户端只拿 facet 列表在本地展开，
 * 服务端用 {@link #matches(String, String)} 做前缀匹配）——两边规则必须一致，否则
 * 「树里点得到、列表里查不出」。
 */
public final class ShopTree {

	/** §45.2「建议最多 3 级」。超过的段不再展开，但过滤仍然包含整棵子树。 */
	public static final int MAX_DEPTH = 3;

	private ShopTree() {
	}

	/** 当前路径下的直接子节点名（按字典序，去重）。已到 {@link #MAX_DEPTH} 时返回空。 */
	public static List<String> children(List<String> categories, String path) {
		String base = norm(path);
		int baseDepth = depth(base);
		if (baseDepth >= MAX_DEPTH) return List.of();
		TreeSet<String> out = new TreeSet<>();
		for (String raw : categories) {
			String c = norm(raw);
			if (c.isEmpty()) continue;
			String rest;
			if (base.isEmpty()) {
				rest = c;
			} else if (c.equals(base) || !c.startsWith(base + "/")) {
				// 自身不是子节点；不同前缀（含 wood vs wooden 这种假前缀）都不算
				continue;
			} else {
				rest = c.substring(base.length() + 1);
			}
			int slash = rest.indexOf('/');
			String seg = slash < 0 ? rest : rest.substring(0, slash);
			if (!seg.isEmpty()) out.add(seg);
		}
		return new ArrayList<>(out);
	}

	/** 该节点是否还有下一级（决定画不画 {@code ›}）。 */
	public static boolean hasChildren(List<String> categories, String path) {
		return !children(categories, path).isEmpty();
	}

	/** 拼子路径；path 为空时返回 name 本身。 */
	public static String join(String path, String name) {
		String base = norm(path);
		return base.isEmpty() ? norm(name) : base + "/" + norm(name);
	}

	/** 上一级路径（根返回空串）。 */
	public static String parent(String path) {
		String p = norm(path);
		int i = p.lastIndexOf('/');
		return i < 0 ? "" : p.substring(0, i);
	}

	/** 末段名（面包屑/标题用）。 */
	public static String label(String path) {
		String p = norm(path);
		int i = p.lastIndexOf('/');
		return i < 0 ? p : p.substring(i + 1);
	}

	/** 面包屑分段：{@code 建材/木材} → ["建材", "木材"]。 */
	public static List<String> crumbs(String path) {
		String p = norm(path);
		List<String> out = new ArrayList<>();
		if (p.isEmpty()) return out;
		for (String s : p.split("/")) {
			if (!s.isEmpty()) out.add(s);
		}
		return out;
	}

	public static int depth(String path) {
		String p = norm(path);
		if (p.isEmpty()) return 0;
		int n = 1;
		for (int i = 0; i < p.length(); i++) {
			if (p.charAt(i) == '/') n++;
		}
		return n;
	}

	/**
	 * 前缀匹配：选中「木材」要包含「木材/台阶」，但「wood」不能命中「wooden」。
	 *
	 * <p>分隔符整段对齐是这里唯一的正确性要点 —— 用裸 {@code startsWith} 会让
	 * 「石」把「石英」也拉进来。
	 */
	public static boolean matches(String itemCategory, String selected) {
		String c = norm(itemCategory).toLowerCase(Locale.ROOT);
		String s = norm(selected).toLowerCase(Locale.ROOT);
		if (s.isEmpty()) return true;
		return c.equals(s) || c.startsWith(s + "/");
	}

	private static String norm(String s) {
		return s == null ? "" : s.trim();
	}
}
