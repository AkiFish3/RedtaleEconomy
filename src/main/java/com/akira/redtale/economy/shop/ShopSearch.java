package com.akira.redtale.economy.shop;

import com.akira.redtale.economy.util.Pinyin;

import java.util.Locale;

/**
 * 一件商品的搜索键（U11 收尾：显示名 + 拼音）。
 *
 * <p>纯逻辑，不碰 Minecraft 类 —— 单测直接构造。
 *
 * <p>命中范围（任一命中即算该 token 命中；多 token 是 AND）：
 * <ol>
 *   <li>物品 id（含下划线被当作空格的紧凑写法）</li>
 *   <li>分类（category，生产数据里通常是中文页签名）</li>
 *   <li>配置里写的 {@code name} / {@code displayName}</li>
 *   <li>显示名（服务端解析：模组 jar 的 zh_cn → 服务端语言）</li>
 *   <li>上述中文的<b>拼音首字母</b>（{@code xmjt} → 橡木台阶）与<b>全拼</b>（{@code xiangmutaijie}）</li>
 * </ol>
 */
public final class ShopSearch {

	private final String id;
	private final String idCompact;
	/** 中文（分类 + 配置名 + 显示名）拼接，小写。 */
	private final String zh;
	private final String initials;
	private final String full;

	private ShopSearch(String id, String zh) {
		this.id = id;
		this.idCompact = id.replace('_', ' ').replace(" ", "");
		this.zh = zh;
		this.initials = Pinyin.hasHan(zh) ? Pinyin.initials(zh) : "";
		this.full = Pinyin.hasHan(zh) ? Pinyin.full(zh) : "";
	}

	public static ShopSearch of(String itemId, String category, String name, String display) {
		String id = itemId == null ? "" : itemId.toLowerCase(Locale.ROOT);
		StringBuilder sb = new StringBuilder();
		append(sb, category);
		append(sb, name);
		append(sb, display);
		return new ShopSearch(id, sb.toString());
	}

	private static void append(StringBuilder sb, String s) {
		if (s == null || s.isBlank()) return;
		if (sb.length() > 0) sb.append(' ');
		sb.append(s.trim().toLowerCase(Locale.ROOT));
	}

	/** 单 token 是否命中。 */
	public boolean matches(String token) {
		if (token == null || token.isEmpty()) return true;
		String t = token.toLowerCase(Locale.ROOT);
		if (id.contains(t) || idCompact.contains(t)) return true;
		if (!zh.isEmpty() && zh.contains(t)) return true;
		if (!initials.isEmpty() && initials.contains(t)) return true;
		return !full.isEmpty() && full.contains(t);
	}

	/** 全部 token 命中才算（与 {@code ShopCatalog.filtered} 的 AND 语义一致）。 */
	public boolean matchesAll(String[] tokens) {
		for (String t : tokens) {
			if (!matches(t)) return false;
		}
		return true;
	}

	/** 诊断用：把索引的三段拼成一行（{@code /rt admin shop search} 展示）。 */
	public String describe() {
		return id + " | " + zh + " | " + initials + " | " + full;
	}

	/** 诊断用：拼音索引是否已建（中文为空时为 false）。 */
	public boolean hasPinyin() {
		return !initials.isEmpty();
	}
}
