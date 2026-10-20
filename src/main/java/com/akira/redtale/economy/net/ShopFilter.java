package com.akira.redtale.economy.net;

import net.minecraft.network.PacketByteBuf;

/**
 * 商店筛选条件（U11b）：mod / 类型（category）+ 价格区间 + 只看有货。
 *
 * <p>与搜索词<b>叠加</b>（搜索支持空格分词 AND）；空值表示不过滤。
 * 服务端是唯一执行筛选的地方，客户端只传条件、不改结果。
 */
public record ShopFilter(String mod, String category, long minPrice, long maxPrice, boolean inStockOnly) {

	public static final ShopFilter NONE = new ShopFilter("", "", 0, 0, false);

	public boolean isEmpty() {
		return (mod == null || mod.isBlank())
				&& (category == null || category.isBlank())
				&& minPrice <= 0 && maxPrice <= 0 && !inStockOnly;
	}

	public String modOrEmpty() {
		return mod == null ? "" : mod;
	}

	public String categoryOrEmpty() {
		return category == null ? "" : category;
	}

	public void write(PacketByteBuf buf) {
		buf.writeString(modOrEmpty(), 48);
		buf.writeString(categoryOrEmpty(), 64);
		buf.writeLong(minPrice);
		buf.writeLong(maxPrice);
		buf.writeBoolean(inStockOnly);
	}

	public static ShopFilter read(PacketByteBuf buf) {
		String m = buf.readString(48);
		String c = buf.readString(64);
		return new ShopFilter(m, c, buf.readLong(), buf.readLong(), buf.readBoolean());
	}

	/** 客户端展示用：价格档位（0=不限）。 */
	public String priceLabel() {
		if (minPrice <= 0 && maxPrice <= 0) return "价格不限";
		if (minPrice > 0 && maxPrice > 0) return minPrice + "~" + maxPrice + " 铜";
		if (maxPrice > 0) return "≤" + maxPrice + " 铜";
		return "≥" + minPrice + " 铜";
	}
}
