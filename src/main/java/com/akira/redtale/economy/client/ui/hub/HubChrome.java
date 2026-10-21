package com.akira.redtale.economy.client.ui.hub;

import com.akira.redtale.economy.client.ShopClientState;
import com.akira.redtale.economy.client.balance.BalanceClientNetworking;
import com.akira.redtale.economy.economy.Currency;
import com.akira.redtale.economy.net.ShopProtocol;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;

/**
 * Hub 外框绘制（T32 批次1）：面板底、标题/余额、关闭按钮、三条分隔线、侧栏底色、搜索框。
 *
 * <p>全是无状态渲染，参数进来就画 —— 拆出来的目的只是让 {@code HubScreen} 不再混着布局、外框、交互。
 * 关闭按钮的命中判定 {@link #closeHit} 与绘制共用 {@link HubFrame} 的坐标，避免两套数字漂移。
 */
public final class HubChrome {

	private HubChrome() {
	}

	/** 面板底 + 顶栏 + 侧栏底色 + 分隔线（背景由调用方先画：非原版主题要走 Screen#renderBackground）。 */
	public static void render(DrawContext dc, TextRenderer tr, HubFrame f, HubPage page,
							boolean vanilla, int screenW, int screenH, String head,
							int mouseX, int mouseY) {
		if (vanilla) {
			HubVanilla.background(dc, screenW, screenH);
			HubVanilla.panel(dc, f.x, f.y, f.w, f.h);
		} else {
			dc.fill(f.x - 1, f.y - 1, f.x + f.w + 1, f.y + f.h + 1, HubTheme.LINE);
			dc.fill(f.x, f.y, f.x + f.w, f.y + f.h, HubTheme.PANEL);
		}

		// 顶部：当前页名（左）
		dc.drawText(tr, Text.literal(head), f.x + 10, f.y + 8, HubTheme.TITLE, false);

		// 顶部：余额（右）—— §37.3「标题左、状态右，不随内容滚动」
		// 窄面板/紧凑模式下会挤掉标题，所以只在够宽时画。
		long bal = BalanceClientNetworking.RAW;
		String balText = bal < 0 ? "" : Currency.format(bal);
		if (!balText.isEmpty()) {
			int bw = tr.getWidth(balText);
			int bx = f.x + f.w - 24 - bw;
			int titleEnd = f.x + 12 + tr.getWidth(head);
			if (bx > titleEnd + 8) {
				dc.drawText(tr, Text.literal(balText), bx, f.y + 8, HubTheme.GOLD, false);
			}
		}

		renderClose(dc, f, vanilla, mouseX, mouseY);

		// ★顶栏下 / 底栏上 / 侧边栏右 三条分隔线（三主题通用）★
		dc.fill(f.x, f.y + HubFrame.TOP_BAR - 1, f.x + f.w, f.y + HubFrame.TOP_BAR, HubTheme.OUTLINE);
		dc.fill(f.x, f.y + f.h - HubFrame.BOTTOM_BAR, f.x + f.w,
				f.y + f.h - HubFrame.BOTTOM_BAR + 1, HubTheme.OUTLINE);
		dc.fill(f.x + f.sidebarW() - 1, f.y + HubFrame.TOP_BAR, f.x + f.sidebarW(),
				f.y + f.h - HubFrame.BOTTOM_BAR, HubTheme.OUTLINE);

		// 侧边栏底色（原版主题交给 HubVanilla 的面板）
		if (!vanilla) {
			dc.fill(f.x, f.y + HubFrame.TOP_BAR, f.x + f.sidebarW(),
					f.y + f.h - HubFrame.BOTTOM_BAR, HubTheme.BAR);
		}
		dc.fill(f.x + f.sidebarW(), f.y + HubFrame.TOP_BAR, f.x + f.sidebarW() + 1,
				f.y + f.h - HubFrame.BOTTOM_BAR, HubTheme.LINE);
	}

	/** 右上角关闭：几何绘制（两条斜线），不用字形，避免字形不居中导致的偏移。 */
	private static void renderClose(DrawContext dc, HubFrame f, boolean vanilla,
									int mouseX, int mouseY) {
		int closeX = f.closeX();
		int closeY = f.closeY();
		int closeSize = f.closeSize();
		boolean hover = closeHit(f, mouseX, mouseY);
		if (vanilla) {
			// 原版按钮固定 20 高：以关闭框中心对齐，别再用 -3 硬偏移
			HubVanilla.button(dc, closeX - 3, closeY + closeSize / 2 - 10,
					closeSize + 6, 20, hover, false);
		} else {
			HubWidgets.roundRect(dc, closeX - 3, closeY - 3, closeSize + 6, closeSize + 6, 2,
					hover ? HubTheme.DANGER_HOVER : HubTheme.INNER);
		}
		int col = hover ? 0xFFFFFFFF : HubTheme.TEXT;
		for (int i = 0; i < closeSize - 4; i++) {
			int px1 = closeX + 2 + i;
			int px2 = closeX + closeSize - 3 - i;
			int py = closeY + 2 + i;
			dc.fill(px1, py, px1 + 1, py + 1, col);
			dc.fill(px2, py, px2 + 1, py + 1, col);
		}
	}

	/** 关闭按钮命中区（含 3px 外扩），渲染与点击共用。 */
	public static boolean closeHit(HubFrame f, double mouseX, double mouseY) {
		return mouseX >= f.closeX() - 3 && mouseX < f.closeX() + f.closeSize() + 3
				&& mouseY >= f.closeY() - 3 && mouseY < f.closeY() + f.closeSize() + 3;
	}

	/**
	 * 商店/仓储页的搜索框统一重绘：聚焦时显示光标并隐藏占位文字。
	 *
	 * <p>页面自己的 render 只画标签，输入框在这里画，保证两页外观一致。
	 */
	public static void renderSearch(DrawContext dc, TextRenderer tr, HubFrame f, HubPage page,
									String q, boolean focused, boolean vanilla) {
		int bx = f.contentX();
		int by = f.searchY(page);
		int bw = f.searchW(page);
		int bh = f.searchH(page);
		if (vanilla) {
			HubVanilla.field(dc, bx, by, bw, bh, focused);
		} else {
			dc.fill(bx, by, bx + bw, by + bh, focused ? 0xCC000000 : 0x99000000);
			dc.fill(bx, by, bx + bw, by + 1, focused ? HubTheme.GOLD : HubTheme.LINE);
			dc.fill(bx, by + bh - 1, bx + bw, by + bh, HubTheme.LINE);
		}
		if (!focused) return;
		dc.drawText(tr, Text.literal(q), bx + 4, by + (bh - 8) / 2,
				vanilla ? 0xFFFFFFFF : HubTheme.TEXT, false);
		if ((System.currentTimeMillis() / 500) % 2 == 0) {
			int cx = bx + 4 + tr.getWidth(q);
			dc.fill(cx, by + 3, cx + 1, by + bh - 3, vanilla ? 0xFFFFFFFF : HubTheme.GOLD);
		}
	}

	/** 商店页标题要带店名，否则只有「商店」两个字看不出是哪家。 */
	public static String headText(HubPage page) {
		String head = page.label;
		if (page == HubPage.SHOP) {
			ShopProtocol.Page sp = ShopClientState.page();
			String sn = sp == null ? "" : sp.shopName();
			if (sn != null && !sn.isBlank()) head = page.label + " · " + sn;
		}
		return head;
	}
}
