package com.akira.redtale.economy.shop;

import com.akira.redtale.economy.config.ShopConfig;

/**
 * 价格护栏（E1）：强制 {@code sell < buy}，防止「买低卖高」刷钱。
 *
 * <p>三种入口都要过这里，缺一就会漏：
 * <ul>
 *   <li>写盘路径 —— {@link ShopCatalog#updateItem} / {@link ShopCatalog#addItem}（拒绝保存）</li>
 *   <li>读盘路径 —— {@link ShopCatalog#sellPrice}（历史文件里已经写错的按价差上限钳制）</li>
 *   <li>兜底系数 —— {@code sellPriceRatio >= 1} 时同样钳制，否则「sell=0 自动算」会绕过</li>
 * </ul>
 *
 * <p>规则用「百分比价差」表达：{@code sell <= buy × (100 - margin) / 100}。
 * 默认 margin=5 ⇒ sell ≤ buy×0.95；margin=0 ⇒ 只要求 sell < buy（严格小于）。
 */
public final class PricePolicy {

	private PricePolicy() {
	}

	public static boolean enabled() {
		return ShopConfig.enforceSellBelowBuy();
	}

	/** 价差百分比，钳到 [0,95]（100 就等于禁止交易）。 */
	public static int marginPercent() {
		int m = ShopConfig.minSellMarginPercent();
		return Math.max(0, Math.min(95, m));
	}

	/** 该买价允许的最高卖价（按当前配置）。{@code buy<=0} 时返回 0（不允许回收）。 */
	public static long maxSellFor(long buy) {
		return maxSellFor(buy, marginPercent());
	}

	/** 纯函数版本（可单测）：{@code margin=0} 时要求严格小于买价。 */
	public static long maxSellFor(long buy, int margin) {
		if (buy <= 0) return 0L;
		int m = Math.max(0, Math.min(95, margin));
		if (m <= 0) return Math.max(0L, buy - 1);
		return Math.max(0L, buy * (100L - m) / 100L);
	}

	/** 纯函数：这对价格是否违反护栏。 */
	public static boolean violates(long buy, long sell, int margin) {
		if (sell <= 0 || buy <= 0) return false;
		return sell > maxSellFor(buy, margin);
	}

	/**
	 * 校验一对价格。
	 *
	 * @return {@code null} = 通过；否则是可直接展示给管理员的原因
	 */
	public static String check(long buy, long sell) {
		if (!enabled()) return null;
		if (sell <= 0) return null;            // 不回收，无套利空间
		if (buy <= 0) return null;             // 不可购买，无套利空间
		long max = maxSellFor(buy);
		if (!violates(buy, sell, marginPercent())) return null;
		return "卖出价 " + sell + " 必须低于买入价 " + buy
				+ "（价差至少 " + marginPercent() + "%，即 ≤" + max + "）";
	}

	/**
	 * 把卖价钳到合法区间（读盘路径用，不写回文件）。
	 * 只在「已开启 + 有买价 + 原来能卖」时生效。
	 */
	public static long clampSell(long buy, long sell) {
		if (!enabled()) return sell;
		return clampSell(buy, sell, marginPercent());
	}

	/** 纯函数版本（可单测）。 */
	public static long clampSell(long buy, long sell, int margin) {
		if (sell <= 0 || buy <= 0) return sell;
		long max = maxSellFor(buy, margin);
		return sell > max ? max : sell;
	}

	/** {@code sellPriceRatio} 的上限：开启护栏时不允许 ≥1（否则兜底价比买价高）。 */
	public static double clampRatio(double ratio) {
		if (!enabled()) return ratio;
		return clampRatio(ratio, marginPercent());
	}

	/** 纯函数版本（可单测）：ratio 不允许 ≥ 上限，否则兜底卖价比买价高。 */
	public static double clampRatio(double ratio, int margin) {
		double cap = (100.0 - Math.max(0, Math.min(95, margin))) / 100.0;
		return ratio > cap ? cap : ratio;
	}
}
