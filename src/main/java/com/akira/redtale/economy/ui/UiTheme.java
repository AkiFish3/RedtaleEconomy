package com.akira.redtale.economy.ui;

import com.akira.redtale.economy.RedtaleEconomy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 统一视觉（spec v0.21 §28）。
 *
 * <p>默认值写在这里，服主可用 {@code config/redtale/ui/theme.json5} 覆盖任意一项：
 * {@code { "background": "#3B2E23", "accent": "#C9A227", ... }}。
 * 颜色一律 {@code #RRGGBB} 或 {@code #AARRGGBB}。
 */
public final class UiTheme {

	public static final int DEFAULT_BACKGROUND = 0xFF3B2E23;
	public static final int DEFAULT_CONTENT = 0xFFE8DCC8;
	public static final int DEFAULT_BORDER = 0xFFC9A227;
	public static final int DEFAULT_TEXT_DARK = 0xFF2B2118;
	public static final int DEFAULT_TEXT_LIGHT = 0xFFF3EAD8;
	public static final int DEFAULT_ACCENT = 0xFFC9A227;
	public static final int DEFAULT_DANGER = 0xFFB4462F;

	private static int background = DEFAULT_BACKGROUND;
	private static int content = DEFAULT_CONTENT;
	private static int border = DEFAULT_BORDER;
	private static int textDark = DEFAULT_TEXT_DARK;
	private static int textLight = DEFAULT_TEXT_LIGHT;
	private static int accent = DEFAULT_ACCENT;
	private static int danger = DEFAULT_DANGER;

	private UiTheme() {
	}

	public static int background() { return background; }
	public static int content() { return content; }
	public static int border() { return border; }
	public static int textDark() { return textDark; }
	public static int textLight() { return textLight; }
	public static int accent() { return accent; }
	public static int danger() { return danger; }

	/** 读 {@code ui/theme.json5}；文件不存在或解析失败都保留默认值。 */
	public static synchronized void load() {
		background = DEFAULT_BACKGROUND;
		content = DEFAULT_CONTENT;
		border = DEFAULT_BORDER;
		textDark = DEFAULT_TEXT_DARK;
		textLight = DEFAULT_TEXT_LIGHT;
		accent = DEFAULT_ACCENT;
		danger = DEFAULT_DANGER;
		Path f = UiLayouts.dir().resolve("theme.json5");
		if (!Files.isRegularFile(f)) return;
		try {
			String json = Files.readString(f, StandardCharsets.UTF_8);
			// 极简解析：只认 "key": "#RRGGBB"，够用且不引依赖
			background = pick(json, "background", background);
			content = pick(json, "content", content);
			border = pick(json, "border", border);
			textDark = pick(json, "textDark", textDark);
			textLight = pick(json, "textLight", textLight);
			accent = pick(json, "accent", accent);
			danger = pick(json, "danger", danger);
		} catch (Exception e) {
			RedtaleEconomy.LOGGER.warn("[ui] theme.json5 读取失败，用默认配色：{}", e.getMessage());
		}
	}

	/** 解析 "#RRGGBB" / "#AARRGGBB"，非法返回 fallback。 */
	public static int parseColor(String raw, int fallback) {
		if (raw == null) return fallback;
		String s = raw.trim();
		if (s.startsWith("#")) s = s.substring(1);
		if (s.startsWith("0x") || s.startsWith("0X")) s = s.substring(2);
		try {
			if (s.length() == 6) return 0xFF000000 | Integer.parseInt(s, 16);
			if (s.length() == 8) return (int) Long.parseLong(s, 16);
		} catch (NumberFormatException ignored) {
			// 落到 fallback
		}
		return fallback;
	}

	private static int pick(String json, String key, int def) {
		String needle = "\"" + key + "\"";
		int i = json.indexOf(needle);
		if (i < 0) return def;
		int colon = json.indexOf(':', i + needle.length());
		if (colon < 0) return def;
		int q1 = json.indexOf('"', colon);
		if (q1 < 0) return def;
		int q2 = json.indexOf('"', q1 + 1);
		if (q2 < 0) return def;
		return parseColor(json.substring(q1 + 1, q2), def);
	}
}
