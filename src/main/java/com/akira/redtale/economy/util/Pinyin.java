package com.akira.redtale.economy.util;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 汉字 → 拼音（U11 拼音搜索）。
 *
 * <p>表来自 classpath 资源 {@code /redtale_pinyin.tsv}（6763 个 GB2312 汉字，每行 汉字\tab 全拼），
 * <b>不是</b> {@code assets/} 下 —— 专用服务端不加载 assets，放根目录才能两端都读到。
 *
 * <p>取不到资源时（被裁剪的包）所有转换原样返回，搜索退化成「只匹配 id/category」，不抛异常。
 */
public final class Pinyin {

	private static final String RESOURCE = "/redtale_pinyin.tsv";
	/** 转换缓存：搜索每帧都会调，按字符串记住结果（上限后整体清空，避免无界增长）。 */
	private static final int CACHE_LIMIT = 8192;

	private static volatile Map<Character, String> table;
	private static final Map<String, String> INITIAL_CACHE = new ConcurrentHashMap<>();
	private static final Map<String, String> FULL_CACHE = new ConcurrentHashMap<>();

	private Pinyin() {
	}

	/** 表是否可用（自检/命令诊断用）。 */
	public static boolean ready() {
		return !loadTable().isEmpty();
	}

	public static int size() {
		return loadTable().size();
	}

	private static Map<Character, String> loadTable() {
		Map<Character, String> t = table;
		if (t != null) return t;
		synchronized (Pinyin.class) {
			if (table != null) return table;
			Map<Character, String> map = new HashMap<>();
			try (InputStream in = Pinyin.class.getResourceAsStream(RESOURCE)) {
				if (in != null) {
					try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
						String line;
						while ((line = r.readLine()) != null) {
							int tab = line.indexOf('\t');
							if (tab <= 0 || tab + 1 >= line.length()) continue;
							if (tab != 1) continue; // 只收单字
							map.put(line.charAt(0), line.substring(tab + 1).trim().toLowerCase());
						}
					}
				}
			} catch (Exception ignored) {
				// 资源缺失：退化为无拼音
			}
			table = map;
			return map;
		}
	}

	/** 是否含汉字（CJK 统一表意文字）。 */
	public static boolean hasHan(String s) {
		if (s == null) return false;
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (c >= 0x4E00 && c <= 0x9FFF) return true;
		}
		return false;
	}

	/** 拼音首字母串：{@code "橡木台阶"} → {@code "xmjt"}；非汉字原样保留（小写）。 */
	public static String initials(String s) {
		if (s == null || s.isEmpty()) return "";
		String cached = INITIAL_CACHE.get(s);
		if (cached != null) return cached;
		Map<Character, String> t = loadTable();
		StringBuilder sb = new StringBuilder(s.length());
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (Character.isWhitespace(c)) continue;
			String py = t.get(c);
			if (py != null && !py.isEmpty()) {
				sb.append(py.charAt(0));
			} else {
				sb.append(Character.toLowerCase(c));
			}
		}
		String out = sb.toString();
		cache(INITIAL_CACHE, s, out);
		return out;
	}

	/** 全拼串（无空格、无声调）：{@code "橡木台阶"} → {@code "xiangmutaijie"}。 */
	public static String full(String s) {
		if (s == null || s.isEmpty()) return "";
		String cached = FULL_CACHE.get(s);
		if (cached != null) return cached;
		Map<Character, String> t = loadTable();
		StringBuilder sb = new StringBuilder(s.length() * 2);
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (Character.isWhitespace(c)) continue;
			String py = t.get(c);
			if (py != null && !py.isEmpty()) {
				sb.append(py);
			} else {
				sb.append(Character.toLowerCase(c));
			}
		}
		String out = sb.toString();
		cache(FULL_CACHE, s, out);
		return out;
	}

	private static void cache(Map<String, String> m, String key, String val) {
		if (m.size() >= CACHE_LIMIT) m.clear();
		m.put(key, val);
	}

	/** 测试/重载用：清空缓存（不动已载入的表）。 */
	public static void clearCache() {
		INITIAL_CACHE.clear();
		FULL_CACHE.clear();
	}
}
