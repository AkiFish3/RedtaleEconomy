package com.akira.redtale.economy.client.ui.hub;

/**
 * Hub 框架几何（T32 批次1）。
 *
 * <p>以前 {@code panelX + sidebarW() + 12} 这套四行坐标换算在 {@code HubScreen} 里抄了近二十份，
 * 改一处布局要全文件搜。现在统一在这里算：面板矩形、内容区矩形、关闭框、搜索框。
 *
 * <p>**只读计算结果，不做渲染**——渲染仍然在 {@code HubScreen} / {@link HubSidebar} 里，
 * 这样拆包的时候不会把布局和画法混在一起。
 */
public final class HubFrame {

	public static final int ROW_H = 24;
	public static final int TOP_BAR = 24;
	public static final int BOTTOM_BAR = 20;
	/** 头像卡高度：32 头像 + 16px 硬币 + 9px 数字 + 边距。*/
	public static final int PROFILE_H = 70;

	/** 面板左上角与尺寸（屏幕坐标）。 */
	public final int x;
	public final int y;
	public final int w;
	public final int h;
	/** 紧凑模式（屏幕太窄时侧边栏收成图标条）。 */
	public final boolean compact;

	public HubFrame(int x, int y, int w, int h, boolean compact) {
		this.x = x;
		this.y = y;
		this.w = w;
		this.h = h;
		this.compact = compact;
	}

	public int sidebarW() {
		return (ClientUiConfig.showLabels && !compact) ? 88 : 40;
	}

	public int entryTop() {
		return y + TOP_BAR + PROFILE_H;
	}

	public int entryBottom() {
		return y + h - BOTTOM_BAR;
	}

	public int maxScroll() {
		int visible = entryBottom() - entryTop();
		int total = HubPage.visible().length * ROW_H;
		return Math.max(0, total - visible);
	}

	/** 内容区（页面自绘区）左上角与尺寸。 */
	public int contentX() {
		return x + sidebarW() + 12;
	}

	public int contentY() {
		return y + TOP_BAR + 8;
	}

	public int contentW() {
		return w - sidebarW() - 24;
	}

	public int contentH() {
		return h - TOP_BAR - BOTTOM_BAR - 16;
	}

	/** 右上角关闭按钮的视觉方框（几何绘制，不用字形）。 */
	public int closeSize() {
		return 14;
	}

	public int closeX() {
		return x + w - closeSize() - 6;
	}

	public int closeY() {
		return y + (TOP_BAR - closeSize()) / 2;
	}

	/** 搜索框矩形：商店页 18 高贴顶，仓储页 16 高下移一行（页面自带分类行）。 */
	public int searchY(HubPage p) {
		return contentY() + (p == HubPage.SHOP ? 0 : 16);
	}

	public int searchH(HubPage p) {
		return p == HubPage.SHOP ? 18 : 16;
	}

	public int searchW(HubPage p) {
		return contentW() - (p == HubPage.SHOP ? 40 : 92);
	}
}
