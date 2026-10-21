package com.akira.redtale.economy.client.ui.hub;

import net.minecraft.client.font.TextRenderer;

/** 文本小工具（T32 批次1）。 */
public final class HubText {

	private HubText() {
	}

	/** 按像素宽度截断并补省略号；装得下就原样返回。 */
	public static String trim(TextRenderer tr, String s, int maxWidth) {
		if (s == null) return "";
		if (tr.getWidth(s) <= maxWidth) return s;
		String out = s;
		while (out.length() > 1 && tr.getWidth(out + "…") > maxWidth) {
			out = out.substring(0, out.length() - 1);
		}
		return out + "…";
	}
}
