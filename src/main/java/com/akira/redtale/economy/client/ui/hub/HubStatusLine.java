package com.akira.redtale.economy.client.ui.hub;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;

/**
 * 底部状态/提示行（T32 批次1）。
 *
 * <p>状态优先（5 秒），空闲出 tip 轮播。状态文案全部来自界面内操作 —— 这里不碰聊天。
 */
public final class HubStatusLine {

	private static final long STATUS_MS = 5000L;

	private String text = "";
	private long until;
	private int tipIndex;
	private long tipSwitchAt;

	/** 界面打开时调用：从头开始算 tip 间隔。 */
	public void resetTipTimer() {
		tipSwitchAt = System.currentTimeMillis() + Tips.intervalSeconds() * 1000L;
	}

	public void set(String t) {
		this.text = t == null ? "" : t;
		this.until = System.currentTimeMillis() + STATUS_MS;
	}

	public void clear() {
		text = "";
		until = 0;
	}

	/** 当前状态文字（夹具取证用：截图里看不清的东西靠日志断言）。 */
	public String text() {
		return text;
	}

	public void render(DrawContext dc, TextRenderer tr, HubFrame f) {
		int y = f.y + f.h - HubFrame.BOTTOM_BAR;
		if (!"vanilla".equals(ClientUiConfig.theme)) {
			dc.fill(f.x, y, f.x + f.w, f.y + f.h, HubTheme.BAR);
			dc.fill(f.x, y, f.x + f.w, y + 1, HubTheme.LINE);
		}

		long now = System.currentTimeMillis();
		boolean showStatus = now < until && !text.isEmpty();
		String shown;
		int color;
		if (showStatus) {
			shown = text;
			color = HubTheme.MUTED;
		} else if (!ClientUiConfig.tipsEnabled) {
			return;
		} else {
			if (now > tipSwitchAt) {
				tipIndex++;
				tipSwitchAt = now + Tips.intervalSeconds() * 1000L;
			}
			Tips.Tip tip = Tips.at(tipIndex);
			shown = tip.text();
			color = tip.ad() ? HubTheme.GOLD : HubTheme.MUTED;
		}
		if (!shown.isEmpty()) {
			dc.drawText(tr, Text.literal(HubText.trim(tr, shown, f.w - 20)),
					f.x + 10, y + 6, color, false);
		}
	}
}
