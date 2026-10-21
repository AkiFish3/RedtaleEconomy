package com.akira.redtale.economy.module.impl;

/**
 * 称号样式（T23）：颜色 / 渐变 / 前后缀。
 *
 * <p>纯函数、零 MC 依赖，服务端渲染 display_name 与客户端预览共用同一套算法，
 * 避免「界面看着是渐变、聊天里是纯色」这类口径分叉。
 *
 * <p>聊天侧产出的是 StyledChat 的标签文本（{@code <c:#RRGGBB>…</c>}）。渐变用**逐字标签**拼，
 * 不依赖模组是否支持 gradient 标签 —— 只要求它认得十六进制颜色；若某服 StyledChat 不认，
 * 把 {@code title.json → styling.enabled} 关掉即可退回纯文本（界面颜色不受影响）。
 */
public final class TitleStyle {

	public static final String SOLID = "solid";
	public static final String GRADIENT = "gradient";

	public static final int GOLD = 0xFFD700;
	public static final int VIOLET = 0x8A2BE2;

	private TitleStyle() {
	}

	/** 解析 {@code #RRGGBB} / {@code RRGGBB}；非法值返回 fallback（不抛异常）。 */
	public static int parse(String s, int fallback) {
		if (s == null) return fallback;
		String t = s.trim();
		if (t.startsWith("#")) t = t.substring(1);
		if (t.length() != 6) return fallback;
		try {
			return Integer.parseInt(t, 16) & 0xFFFFFF;
		} catch (NumberFormatException e) {
			return fallback;
		}
	}

	public static String hex(int rgb) {
		return String.format("#%06X", rgb & 0xFFFFFF);
	}

	/** 两个 0xRRGGBB 之间按 t∈[0,1] 线性插值。 */
	public static int lerp(int a, int b, double t) {
		double k = Math.max(0.0, Math.min(1.0, t));
		int r = (int) Math.round(((a >> 16) & 0xFF) * (1 - k) + ((b >> 16) & 0xFF) * k);
		int g = (int) Math.round(((a >> 8) & 0xFF) * (1 - k) + ((b >> 8) & 0xFF) * k);
		int bl = (int) Math.round((a & 0xFF) * (1 - k) + (b & 0xFF) * k);
		return (r << 16) | (g << 8) | bl;
	}

	/** 第 {@code i} 个字符（共 {@code len} 个）的颜色；单字符直接取起始色。 */
	public static int at(boolean gradient, int c1, int c2, int i, int len) {
		if (!gradient || len <= 1) return c1;
		return lerp(c1, c2, (double) i / (double) (len - 1));
	}

	/** 客户端渲染用：整段文字逐字着色到 {@code sink}（下标, 0xRRGGBB）。 */
	public interface Sink {
		void accept(int index, int rgb);
	}

	public static void each(boolean gradient, int c1, int c2, int len, Sink sink) {
		for (int i = 0; i < len; i++) sink.accept(i, at(gradient, c1, c2, i, len));
	}

	/**
	 * 生成聊天用的标签文本。{@code gradient=false} 时整段包一个颜色标签（最短）；
	 * 开启渐变时逐字包 —— display_name 会变长，所以上限由 {@code styling.chatGradient} 控制。
	 */
	public static String chat(String text, boolean gradient, int c1, int c2) {
		if (text == null || text.isEmpty()) return "";
		if (!gradient || text.length() <= 1) {
			return "<c:" + hex(c1) + ">" + escape(text) + "</c>";
		}
		StringBuilder sb = new StringBuilder(text.length() * 24);
		int len = text.length();
		for (int i = 0; i < len; i++) {
			int rgb = at(true, c1, c2, i, len);
			sb.append("<c:").append(hex(rgb)).append('>')
					.append(escape(String.valueOf(text.charAt(i))))
					.append("</c>");
		}
		return sb.toString();
	}

	/** 称号是玩家可控文本：把标签语法字符转义，避免玩家用 {@code <c:red>} 自造样式。 */
	public static String escape(String s) {
		if (s == null) return "";
		StringBuilder sb = new StringBuilder(s.length() + 8);
		for (int i = 0; i < s.length(); i++) {
			char ch = s.charAt(i);
			if (ch == '<') sb.append("\\<");
			else if (ch == '>') sb.append("\\>");
			else if (ch == '\\') sb.append("\\\\");
			else sb.append(ch);
		}
		return sb.toString();
	}

	/** 纯文本预览：只摘掉我们自己生成的 {@code <c:#RRGGBB>}/{@code </c>}，再还原玩家文本里的转义。 */
	public static String strip(String s) {
		if (s == null) return "";
		String out = s.replaceAll("<c:#[0-9A-Fa-f]{6}>", "").replace("</c>", "");
		return out.replace("\\<", "<").replace("\\>", ">").replace("\\\\", "\\");
	}
}
