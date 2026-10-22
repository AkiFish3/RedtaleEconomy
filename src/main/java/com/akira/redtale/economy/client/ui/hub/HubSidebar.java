package com.akira.redtale.economy.client.ui.hub;

import com.akira.redtale.economy.client.balance.BalanceClientNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.List;

/**
 * 左侧栏（T32 批次1）：玩家区（头像/名字/余额）+ 页面列表 + 滚动条 + 点击涟漪。
 *
 * <p>从 {@code HubScreen} 整块搬出来，状态（滚动位置、余额滚动动画、涟漪）跟着一起搬，
 * 屏幕只负责「这一栏在哪个矩形里」。
 *
 * <p>tooltip 不在这里画：按硬规则浮层最后画，这里只登记 {@link #pendingTooltip}，
 * 由 {@code HubScreen} 在光标物品之后统一绘制。
 */
public final class HubSidebar {

	private int scroll;
	private int rippleRow = -1;
	private long rippleAt;
	private double rippleX;
	private double rippleY;
	private List<Text> pendingTooltip;
	/** 紧凑模式悬停头像时的自绘余额浮层（T55：浮层里也要有硬币图标）。 */
	private boolean moneyTooltip;
	private String moneyName = "";
	private long moneyRaw = -1;

	/** 侧边栏滚动偏移（dev 夹具要读）。 */
	public int scrollOffset() {
		return scroll;
	}

	public void setScroll(int v) {
		scroll = v;
	}

	/** 点某行时的视觉涟漪（目前没有调用方，保留给后续动画用）。 */
	public void ripple(int row, double x, double y) {
		rippleRow = row;
		rippleX = x;
		rippleY = y;
		rippleAt = System.currentTimeMillis();
	}

	/**
	 * 鼠标 Y 落在第几行（不考虑是否在栏内）；越界返回 -1。
	 *
	 * <p>★必须夹在 {@code [entryTop, entryBottom)}★：条目区在渲染时被 scissor 裁掉了，但命中
	 * 只按 Y 算行号 —— 底栏（状态行/提示，和侧栏同一列、在列表下方）算出来的行号仍然是
	 * 合法页号，点一下底部空白就悄悄切页（与「浮层命中没夹边界」同族）。
	 */
	public int rowAt(HubFrame f, double mouseY) {
		return rowAt(HubPage.visible().length, f.entryTop(), f.entryBottom(), scroll, mouseY);
	}

	/** 纯几何命中（可 headless 测）：{@code count} 个条目，条目区 {@code [entryTop, entryBottom)}。 */
	static int rowAt(int count, int entryTop, int entryBottom, int scroll, double mouseY) {
		if (mouseY < entryTop || mouseY >= entryBottom) return -1;
		int idx = ((int) mouseY - entryTop + scroll) / HubFrame.ROW_H;
		return (idx >= 0 && idx < count) ? idx : -1;
	}

	/** 第 index 行的垂直中心（真实命中路径用，含滚动偏移）。 */
	public double rowCenterY(HubFrame f, int index) {
		return f.entryTop() - scroll + index * HubFrame.ROW_H + HubFrame.ROW_H / 2.0;
	}

	/** 滚轮：负数向上。越界自动夹回。 */
	public void scrollBy(HubFrame f, double amount) {
		int ms = f.maxScroll();
		if (ms <= 0) return;
		scroll = Math.max(0, Math.min(ms, scroll - (int) (amount * HubFrame.ROW_H)));
	}

	public void render(DrawContext dc, TextRenderer tr, HubFrame f, HubPage page,
					   int mouseX, int mouseY) {
		pendingTooltip = null;
		moneyTooltip = false;
		renderProfile(dc, tr, f, mouseX, mouseY);
		renderEntries(dc, tr, f, page, mouseX, mouseY);
	}

	/**
	 * 浮层统一在最后画（顶栏/内容/底栏都可能覆盖侧边栏）。
	 *
	 * <p>T55：头像悬停的余额浮层是<b>自绘</b>的 —— 原版 tooltip 只吃 Text，
	 * 塞不进几何硬币；这里给「名字 + 三段余额」画一个小面板。
	 */
	public void drawPendingTooltip(DrawContext dc, TextRenderer tr, int mouseX, int mouseY) {
		if (moneyTooltip) {
			moneyTooltip = false;
			String line = moneyRaw < 0 ? "…" : HubMoney.fullText(moneyRaw);
			int w = Math.max(tr.getWidth(moneyName), HubMoney.fullWidth(tr, Math.max(0, moneyRaw))) + 10;
			w = Math.max(w, tr.getWidth(line) + 10);
			int h = 24;
			int bx = mouseX + 8;
			int by = mouseY + 8;
			dc.fill(bx, by, bx + w, by + h, HubTheme.PANEL);
			dc.fill(bx, by, bx + w, by + 1, HubTheme.OUTLINE);
			dc.fill(bx, by + h - 1, bx + w, by + h, HubTheme.OUTLINE);
			dc.fill(bx, by, bx + 1, by + h, HubTheme.OUTLINE);
			dc.fill(bx + w - 1, by, bx + w, by + h, HubTheme.OUTLINE);
			dc.drawText(tr, Text.literal(moneyName), bx + 5, by + 4, HubTheme.TEXT, false);
			if (moneyRaw < 0) {
				dc.drawText(tr, Text.literal("…"), bx + 5, by + 14, HubTheme.MUTED, false);
			} else {
				HubMoney.drawFull(dc, tr, bx + 5, by + 14, moneyRaw, w - 10);
			}
		}
		if (pendingTooltip == null) return;
		dc.drawTooltip(tr, pendingTooltip, mouseX, mouseY);
		pendingTooltip = null;
	}

	private void renderProfile(DrawContext dc, TextRenderer tr, HubFrame f, int mouseX, int mouseY) {
		int x = f.x;
		int y = f.y + HubFrame.TOP_BAR;
		int sidebarW = f.sidebarW();
		dc.fill(x, y, x + sidebarW, y + HubFrame.PROFILE_H,
				"vanilla".equals(ClientUiConfig.theme) ? HubTheme.PANEL : HubTheme.BAR);
		boolean withText = ClientUiConfig.showLabels && !f.compact;
		int headSize = withText ? 32 : 26;
		int headX = withText ? x + 6 : x + (sidebarW - headSize) / 2;
		int headY = withText ? y + 6 : y + 8;
		dc.fill(headX - 1, headY - 1, headX + headSize + 1, headY + headSize + 1,
				"vanilla".equals(ClientUiConfig.theme) ? HubTheme.LINE : HubTheme.SLOT);
		drawHead(dc, headX, headY, headSize);
		if (withText) {
			MinecraftClient mc = MinecraftClient.getInstance();
			String name = mc.player == null ? "—" : mc.player.getGameProfile().getName();
			dc.drawText(tr, Text.literal(HubText.trim(tr, name, sidebarW - 44)),
					x + 42, y + 8, HubTheme.TEXT, false);
			long raw = BalanceClientNetworking.RAW;
			// 余额变化只在「服务端确认后」才动：下跌先沉降 300ms，失败回滚永远落不到屏幕上
			// （A 第 N+78 轮：转账失败时金额先掉一截再弹回，就是这里直接拿 RAW 插值造成的）
			long now = System.currentTimeMillis();
			BalanceDisplay.feed(raw, now);
			long shown = BalanceDisplay.value(now);
			drawCoins(dc, tr, x + 3, y + 38, sidebarW - 6, shown);
		}
		boolean hoverHead = !withText
				&& mouseX >= headX && mouseX < headX + headSize
				&& mouseY >= headY && mouseY < headY + headSize;
		if (hoverHead) {
			MinecraftClient mc2 = MinecraftClient.getInstance();
			moneyName = mc2.player == null ? "—" : mc2.player.getGameProfile().getName();
			moneyRaw = BalanceDisplay.value(System.currentTimeMillis());
			moneyTooltip = true;
		}
		dc.fill(x, y, x + sidebarW, y + 1, HubTheme.LINE);                        // 顶边
		dc.fill(x, y, x + 1, y + HubFrame.PROFILE_H, HubTheme.LINE);              // 左边
		dc.fill(x, y + HubFrame.PROFILE_H - 1, x + sidebarW, y + HubFrame.PROFILE_H,
				HubTheme.LINE);                                                   // 底边
	}

	/**
	 * 余额（A 第 N+79 轮）：<b>用 Numismatic 的原生硬币图标（16px）</b>，三列「图标在上、数字在下」。
	 *
	 * <p>★为什么不再是一行三段★：一行三段在 11px 的预算下要 85px，而侧栏内宽只有 80px ——
	 * 放不下就静默换成了 T67 硬币<b>字形</b>（我们自己画的位图），Aki 一眼看出「不是 MOD 的图标」。
	 * 三列布局把每段压到 24px，16px 贴图既能原样不缩放地画出来，又能带上金/银/铜。
	 */
	private void drawCoins(DrawContext dc, TextRenderer tr, int x, int y, int w, long raw) {
		if (raw < 0) {
			dc.drawText(tr, Text.literal("…"), x, y, HubTheme.TEXT, false);
			return;
		}
		long[] p = HubMoney.split(raw);
		int gap = 4;
		int colW = Math.max(HubMoney.ICON_BIG + 2, (w - gap * 2) / 3);
		int iconY = y + 2;
		int textY = iconY + HubMoney.ICON_BIG + 1;
		for (int i = 0; i < 3; i++) {
			int cx = x + i * (colW + gap);
			HubMoney.coinIcon(dc, cx + (colW - HubMoney.ICON_BIG) / 2, iconY, i);
			String n = Long.toString(p[i]);
			String s = tr.getWidth(n + HubMoney.unitOf(i)) <= colW ? n + HubMoney.unitOf(i) : n;
			int nx = cx + Math.max(0, (colW - tr.getWidth(s)) / 2);
			dc.drawText(tr, Text.literal(s), nx, textY,
					i == 0 ? HubTheme.GOLD : HubTheme.TEXT, false);
		}
	}

	/** 玩家头像（用皮肤贴图的 8×8 脸部放大）。 */
	private void drawHead(DrawContext dc, int x, int y, int size) {
		try {
			MinecraftClient mc = MinecraftClient.getInstance();
			if (mc.player == null) throw new IllegalStateException();
			var tex = mc.getSkinProvider().loadSkin(mc.player.getGameProfile());
			var m = dc.getMatrices();
			m.push();
			m.translate(x, y, 0);
			float k = size / 8f;
			m.scale(k, k, 1f);
			dc.drawTexture(tex, 0, 0, 8, 8, 8, 8, 64, 64);
			// 外层（帽子）叠在脸上，位置 40,8
			dc.drawTexture(tex, 0, 0, 40, 8, 8, 8, 64, 64);
			m.pop();
		} catch (Exception ex) {
			dc.fill(x, y, x + size, y + size, HubTheme.SLOT);
		}
	}

	private void drawIcon(DrawContext dc, String itemId, int x, int y) {
		try {
			var item = Registries.ITEM.get(new Identifier(itemId));
			if (item != null && item != net.minecraft.item.Items.AIR) {
				dc.drawItem(new ItemStack(item), x, y);
			}
		} catch (Exception ignored) {
			// 图标缺失不影响文字
		}
	}

	private void renderEntries(DrawContext dc, TextRenderer tr, HubFrame f, HubPage page,
							   int mouseX, int mouseY) {
		int top = f.entryTop();
		int bottom = f.entryBottom();
		int sidebarW = f.sidebarW();
		boolean clip = bottom > top;
		// 模块被关掉会让页数变少 ⇒ 旧的 scroll 可能超过新上限，不夹的话列表整段画到上面看不见
		scroll = Math.max(0, Math.min(scroll, f.maxScroll()));
		// ★裁剪★：不让滚动时条目画到顶栏/底栏上
		if (clip) dc.enableScissor(f.x, top, f.x + sidebarW, bottom);
		int y = top - scroll;
		for (HubPage p : HubPage.visible()) {
			if (y + HubFrame.ROW_H > top && y < bottom) {
				boolean sel = p == page;
				boolean hovered = mouseX >= f.x && mouseX < f.x + sidebarW
						&& mouseY >= y && mouseY < y + HubFrame.ROW_H;
				if ("vanilla".equals(ClientUiConfig.theme)) {
					if (sel || hovered) {
						HubVanilla.button(dc, f.x + 2, y + 2, sidebarW - 4, 20, hovered, false);
					}
				} else if (sel) {
					HubWidgets.roundRect(dc, f.x + 2, y + 2, sidebarW - 4, HubFrame.ROW_H - 4,
							2, HubTheme.SELECT);
					HubWidgets.indicator(dc, f.x + 2, y + 2, HubFrame.ROW_H - 4);
				} else if (hovered) {
					HubWidgets.roundRect(dc, f.x + 2, y + 2, sidebarW - 4, HubFrame.ROW_H - 4,
							2, HubTheme.HOVER);
				}
				int color = sel ? HubTheme.PRIMARY_SOFT_TEXT : (hovered ? HubTheme.TITLE : HubTheme.TEXT);
				drawIcon(dc, p.icon, f.x + (sidebarW > 44 ? 8 : 11), y + 5);
				if (sidebarW > 44) {
					dc.drawText(tr, Text.literal(p.label), f.x + 28, y + 8, color, false);
				} else if (hovered) {
					pendingTooltip = List.of(Text.literal(p.label));
				}
			}
			y += HubFrame.ROW_H;
		}
		// 溢出时画细滚动条
		int ms = f.maxScroll();
		if (ms > 0) {
			int barH = Math.max(12, (bottom - top) * (bottom - top)
					/ (HubPage.visible().length * HubFrame.ROW_H));
			int barY = top + (bottom - top - barH) * scroll / ms;
			dc.fill(f.x + sidebarW - 3, top, f.x + sidebarW - 1, bottom, HubTheme.ROW_HOVER);
			dc.fill(f.x + sidebarW - 3, barY, f.x + sidebarW - 1, barY + barH, HubTheme.MUTED);
		}
		if (rippleRow >= 0) {
			long dt = System.currentTimeMillis() - rippleAt;
			if (dt < 260) {
				int ry = top - scroll + rippleRow * HubFrame.ROW_H;
				HubWidgets.ripple(dc, f.x + 2, ry + 2, sidebarW - 4, HubFrame.ROW_H - 4,
						rippleX, rippleY, dt / 260f, 0xFFFFFF);
			} else {
				rippleRow = -1;
			}
		}
		if (clip) dc.disableScissor();
	}
}
