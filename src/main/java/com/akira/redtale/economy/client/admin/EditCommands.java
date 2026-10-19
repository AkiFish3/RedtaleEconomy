package com.akira.redtale.economy.client.admin;

/**
 * 编辑界面用到的管理命令拼装（R16a）。
 *
 * <p>界面只负责拼命令、发命令；权限、价格护栏、回收站删除都在服务端那一份逻辑里，
 * 客户端不复制任何校验。抽成纯函数是为了让命令格式可以被单测钉死——
 * 子命令名一旦写错，玩家只会看到「未知命令」，很难在界面里发现。
 */
public final class EditCommands {

	private EditCommands() {
	}

	public static String price(String shopId, String itemId, long buy, long sell) {
		return "shop admin price " + shopId + " " + itemId + " " + Math.max(0, buy) + " " + Math.max(0, sell);
	}

	public static String stock(String shopId, String itemId, long count) {
		// -1 表示无限，其它负数一律按 -1
		long v = count < 0 ? -1 : count;
		return "shop admin stock " + shopId + " " + itemId + " " + v;
	}

	public static String toggle(String shopId, String itemId) {
		return "shop admin toggle " + shopId + " " + itemId;
	}

	public static String remove(String shopId, String itemId) {
		return "shop admin remove " + shopId + " " + itemId;
	}

	public static String add(String shopId, String itemId, long buy, long sell) {
		return "shop admin add " + shopId + " " + itemId + " " + Math.max(0, buy) + " " + Math.max(0, sell);
	}
}
