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
	/** U11b：当前筛选条件（mod/类型/价格/库存），随请求一起发给服务端 */
	private static volatile com.akira.redtale.economy.net.ShopFilter filter =
			com.akira.redtale.economy.net.ShopFilter.NONE;
	private static volatile long lastActionAt;
	/** 服务端 S2C_OPEN 指定的当前商店 id —— Hub 模式下 S2C_OPEN 只带 id 不带数据，必须记住它才请求得到页。 */
	private static volatile String activeShop = "";

	private ShopClientState() {
	}

	public static String activeShop() {
		return activeShop;
	}

	public static void setActiveShop(String id) {
		activeShop = id == null ? "" : id;
	}

	/** 当前该向服务端请求哪个商店：优先服务端点名的，其次上一页自带的。 */
	public static String shopIdToRequest() {
		if (!activeShop.isEmpty()) return activeShop;
		ShopProtocol.Page p = page;
		return p == null ? "" : p.shopId();
	}

	/** 一屏行数：由服务端 shop/settings.json 的 visibleRows 下发（默认 9）。 */
	public static int visibleRows() {
		return visibleRows;
	}

	public static void setVisibleRows(int rows) {
		visibleRows = rows;
	}

	public static com.akira.redtale.economy.net.ShopFilter filter() {
		return filter;
	}

	public static void setFilter(com.akira.redtale.economy.net.ShopFilter f) {
		filter = f == null ? com.akira.redtale.economy.net.ShopFilter.NONE : f;
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
		filter = com.akira.redtale.economy.net.ShopFilter.NONE;
		lastAction = null;
	}
}
