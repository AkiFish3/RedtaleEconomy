package com.akira.redtale.economy.client;

import com.akira.redtale.economy.net.ShopProtocol;

/**
 * 客户端商店状态缓存（只存展示用数据，不含任何可信数值）。
 *
 * <p>服务端才是权威：余额、库存、能不能买都以服务端返回的 {@code ActionResult} 为准。
 */
public final class ShopClientState {

	private static volatile ShopProtocol.Page page = ShopProtocol.Page.empty("", "");
	private static volatile ShopProtocol.ActionResult lastAction;
	private static volatile int visibleRows = 9;
	private static volatile long lastActionAt;

	private ShopClientState() {
	}

	/** 一屏行数：由服务端 shop/settings.json 的 visibleRows 下发（默认 9）。 */
	public static int visibleRows() {
		return visibleRows;
	}

	public static void setVisibleRows(int rows) {
		visibleRows = rows;
	}

	public static ShopProtocol.Page page() {
		return page;
	}

	public static void setPage(ShopProtocol.Page p) {
		page = p;
	}

	public static ShopProtocol.ActionResult lastAction() {
		return lastAction;
	}

	public static long lastActionAt() {
		return lastActionAt;
	}

	public static void setAction(ShopProtocol.ActionResult r) {
		lastAction = r;
		lastActionAt = System.currentTimeMillis();
	}

	public static void clear() {
		page = ShopProtocol.Page.empty("", "");
		lastAction = null;
	}
}
