package com.akira.redtale.economy.shop;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 统一错误码（B21）。
 *
 * <p>以前客户端只能拿到 lang key 来判断失败原因，界面里得 `if (key.equals("shop.outOfStock"))`，
 * key 一改就悄悄失效。现在每个结果都带一个<b>稳定的机器码</b>（snake_case，永不改），
 * 语言键仍负责显示，两者由这里的一张表绑定。
 *
 * <p>新加失败分支时必须同时：① 在 {@link #KEYS} 加 lang key；② 补 zh_cn / en_us 翻译；
 * {@code ShopErrorsTest} 会检查这两件事，漏了 build 红。
 */
public final class ShopErrors {

	/** messageKey → 稳定码。key 用 camelCase 的 `shop.xxx`，码用 snake_case。 */
	private static final Map<String, String> KEYS = new LinkedHashMap<>();

	static {
		KEYS.put("shop.badRequest", "bad_request");
		KEYS.put("shop.notReady", "not_ready");
		KEYS.put("shop.notFound", "not_found");
		KEYS.put("shop.outOfStock", "out_of_stock");
		KEYS.put("shop.notEnoughStock", "not_enough_stock");
		KEYS.put("shop.dailyLimit", "daily_limit");
		KEYS.put("shop.premiumOnly", "premium_only");
		KEYS.put("shop.premiumInsufficient", "premium_insufficient");
		KEYS.put("cmd.pay.insufficient", "insufficient_funds");
		KEYS.put("shop.notSellable", "not_sellable");
		KEYS.put("shop.notEnoughItems", "not_enough_items");
		KEYS.put("shop.buyDisabled", "buy_disabled");
		KEYS.put("shop.sellDisabled", "sell_disabled");
		KEYS.put("shop.sellDailyLimit", "sell_daily_limit");
		KEYS.put("shop.sellDailyLimitTotal", "sell_daily_limit_total");
		KEYS.put("shop.giveFailed", "give_failed");
		KEYS.put("shop.buyOk", "ok");
		KEYS.put("shop.sellOk", "ok");
	}

	private ShopErrors() {
	}

	/** 未知 key 也返回一个稳定码（由 key 派生），避免客户端收到空码。 */
	public static String codeOf(String messageKey) {
		if (messageKey == null || messageKey.isBlank()) return "unknown";
		String code = KEYS.get(messageKey);
		if (code != null) return code;
		String tail = messageKey.substring(messageKey.lastIndexOf('.') + 1);
		return tail.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
	}

	public static Set<String> knownMessageKeys() {
		return KEYS.keySet();
	}

	/** 全部稳定码（去重）。 */
	public static Set<String> codes() {
		return Set.copyOf(KEYS.values());
	}
}
