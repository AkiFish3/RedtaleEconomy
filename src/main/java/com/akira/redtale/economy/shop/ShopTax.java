package com.akira.redtale.economy.shop;

import com.akira.redtale.economy.config.ShopConfig;
import com.akira.redtale.economy.module.impl.GuildModule;

import java.util.UUID;

/**
 * 商店手续费（可开关、可自定义、可按协会等级减免）。
 *
 * <p>纯函数，方便单测：向上取整，避免 0.4 铜被截成 0 导致「税开了但一分没收到」。
 */
public final class ShopTax {

	private ShopTax() {
	}

	public static boolean exempt(UUID uuid) {
		int need = ShopConfig.taxSkipRank();
		if (need <= 0) return false;
		try {
			return GuildModule.rankIndex(uuid) + 1 >= need;
		} catch (Throwable t) {
			return false;
		}
	}

	/** 名单模式判定：none=全收；whitelist=只有名单内收；blacklist=只有名单外收。 */
	public static boolean itemTaxed(String itemId) {
		String mode = ShopConfig.taxMode();
		if ("none".equalsIgnoreCase(mode)) return true;
		boolean in = matches(itemId, ShopConfig.taxItems());
		return "whitelist".equalsIgnoreCase(mode) ? in : !in;
	}

	/** 精确 id 或 modid:* 通配。 */
	public static boolean matches(String itemId, java.util.List<String> list) {
		if (itemId == null || itemId.isBlank() || list == null) return false;
		for (String raw : list) {
			if (raw == null) continue;
			String pat = raw.trim();
			if (pat.isEmpty()) continue;
			if (pat.endsWith(":*")) {
				if (itemId.startsWith(pat.substring(0, pat.length() - 1))) return true;
			} else if (pat.equals(itemId)) {
				return true;
			}
		}
		return false;
	}

	public static long buyTax(UUID uuid, long price) {
		return buyTax(uuid, null, price);
	}

	public static long buyTax(UUID uuid, String itemId, long price) {
		if (!ShopConfig.taxEnabled() || price <= 0 || exempt(uuid)) return 0;
		if (itemId != null && !itemTaxed(itemId)) return 0;
		int pct = ShopConfig.taxBuyPercent();
		if (pct <= 0) return 0;
		return (price * pct + 99) / 100;
	}

	public static long sellTax(UUID uuid, long income) {
		return sellTax(uuid, null, income);
	}

	public static long sellTax(UUID uuid, String itemId, long income) {
		if (!ShopConfig.taxEnabled() || income <= 0 || exempt(uuid)) return 0;
		if (itemId != null && !itemTaxed(itemId)) return 0;
		int pct = ShopConfig.taxSellPercent();
		if (pct <= 0) return 0;
		return (income * pct + 99) / 100;
	}
}
