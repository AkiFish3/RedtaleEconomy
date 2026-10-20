package com.akira.redtale.economy.market;

/**
 * 市场纯逻辑（R40）：费率、到期判定、分页钳制。
 *
 * <p>刻意不引 Minecraft 类 —— 这样单测可以直接跑，不需要启动游戏（本工程禁游戏轮的口径）。
 * 所有金额单位都是「铜」。
 */
public final class MarketRules {

	private MarketRules() {
	}

	/** 按百分比抽成，向上取整（1 铜的东西也要收得到，避免 feePercent>0 却收 0）。 */
	public static long percentOf(long amount, double percent) {
		if (amount <= 0 || percent <= 0) return 0;
		double raw = amount * percent / 100.0;
		long fee = (long) Math.ceil(raw - 1e-9);
		return Math.max(0, Math.min(fee, amount));
	}

	/** 挂单费：按标价抽，向上取整。 */
	public static long listingFee(long price, double feePercent) {
		return percentOf(price, feePercent);
	}

	/** 成交佣金：从货款里扣，不得超过货款本身（否则卖家倒欠钱）。 */
	public static long commission(long price, double commissionPercent) {
		return percentOf(price, commissionPercent);
	}

	/** 卖家实际到账 = 标价 - 佣金。 */
	public static long sellerProceeds(long price, double commissionPercent) {
		return Math.max(0, price - commission(price, commissionPercent));
	}

	/** 标价是否落在配置区间内。 */
	public static boolean priceInRange(long price, long min, long max) {
		return price >= min && price <= max;
	}

	/** 已到期（{@code expireAt} 为 0 表示永不过期，配置允许 0 时不自动下架）。 */
	public static boolean expired(long expireAt, long now) {
		return expireAt > 0 && now >= expireAt;
	}

	public static int totalPages(int totalItems, int pageSize) {
		if (pageSize <= 0) return 0;
		return (totalItems + pageSize - 1) / pageSize;
	}

	/** 任何输入的页码都落进合法区间；空列表返回 0。 */
	public static int clampPage(int requested, int totalItems, int pageSize) {
		int pages = totalPages(totalItems, pageSize);
		if (pages <= 0) return 0;
		if (requested < 0) return 0;
		return Math.min(requested, pages - 1);
	}

	// ---------------------------------------------------------------- R41 拍卖

	/**
	 * 下一次合法的最低出价。
	 *
	 * <p>没人出价时 = 起拍价；之后按「当前价 + 至少 pct%」算，且至少 +1 铜
	 * （低价物品 5% 不足 1 铜时会算出和当前价相同的数字，那样出价永远「不低于」却没提高）。
	 */
	public static long minNextBid(long currentBid, long startPrice, double incrementPercent) {
		if (currentBid <= 0) return Math.max(1, startPrice);
		long step = percentOf(currentBid, incrementPercent);
		return currentBid + Math.max(1, step);
	}

	/** 是否该顺延：剩余时间已进入「狙击窗口」。 */
	public static boolean shouldExtend(long now, long expireAt, long windowMs) {
		if (expireAt <= 0 || windowMs <= 0) return false;
		return expireAt - now <= windowMs;
	}

	/** 顺延后的到期时间：从「现在」往后加，而不是从原到期时间加（否则连点两下只延一点）。 */
	public static long extendedExpireAt(long now, long expireAt, long extendMs) {
		if (extendMs <= 0) return expireAt;
		return Math.max(expireAt, now + extendMs);
	}

	/** 剩余时间（毫秒），永不过期返回 -1。 */
	public static long remaining(long expireAt, long now) {
		if (expireAt <= 0) return -1;
		return Math.max(0, expireAt - now);
	}

	/** 人类可读的剩余时长（分钟粒度，<1 分钟显示「<1 分钟」）。 */
	public static String remainingText(long remainMs) {
		if (remainMs < 0) return "永久";
		if (remainMs < 60_000L) return "<1 分钟";
		long minutes = remainMs / 60_000L;
		if (minutes < 60) return minutes + " 分钟";
		long hours = minutes / 60;
		if (hours < 24) return hours + " 小时";
		return (hours / 24) + " 天";
	}
}
