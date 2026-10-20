package com.akira.redtale.economy.net;

import com.akira.redtale.economy.config.ShopConfig;
import net.minecraft.network.PacketByteBuf;

import java.util.ArrayList;
import java.util.List;

/**
 * 商店协议编解码（spec §7）。
 *
 * <p>核心约束：服务端<b>永不</b>下发全量商品。一次请求 = 一页 = {@code shop.pageSize} 条。
 * 客户端发来的 page 会被钳进 [0, totalPages-1]，负数/超大值都不会导致越界。
 */
public final class ShopProtocol {

	public record Entry(String itemId, long buyPrice, long sellPrice, long stock, boolean canBuy, boolean canSell) {
	}

	/**
	 * 一页结果。
	 *
	 * <p>{@code mods} / {@code categories} 是 U11b 的筛选维度取值表（当前商店的全量 facet），
	 * 客户端用它们画分类栏，不用自己再扫一遍商品。
	 */
	public record Page(String shopId, String query, int page, int totalPages, int totalItems,
			List<Entry> entries, List<String> mods, List<String> categories) {
		public static Page empty(String shopId, String query) {
			return new Page(shopId, query, 0, 0, 0, List.of(), List.of(), List.of());
		}

		/** 兼容旧构造点（无 facet）。 */
		public Page(String shopId, String query, int page, int totalPages, int totalItems, List<Entry> entries) {
			this(shopId, query, page, totalPages, totalItems, entries, List.of(), List.of());
		}
	}

	/**
	 * 操作结果。
	 *
	 * @param code 稳定机器码（B21）：客户端按它判断分支，语言键只管显示。
	 *             老构造器签名保留，code 由 {@link com.akira.redtale.economy.shop.ShopErrors#codeOf} 推导。
	 */
	public record ActionResult(boolean ok, String messageKey, String code, long balance, long stockRemaining) {
		public ActionResult(boolean ok, String messageKey, long balance, long stockRemaining) {
			this(ok, messageKey, com.akira.redtale.economy.shop.ShopErrors.codeOf(messageKey),
					balance, stockRemaining);
		}
	}

	/** readPage 顺带读到的「一屏行数」；客户端接收方用 {@link #lastVisibleRows()} 取。 */
	private static volatile int lastVisibleRows = 9;

	private ShopProtocol() {
	}

	public static int lastVisibleRows() {
		return lastVisibleRows;
	}

	// ---------------------------------------------------------------- 编码

	public static void writePage(PacketByteBuf buf, Page p) {
		buf.writeString(p.shopId(), 64);
		buf.writeString(p.query() == null ? "" : p.query(), 128);
		buf.writeVarInt(p.page());
		buf.writeVarInt(p.totalPages());
		buf.writeVarInt(p.totalItems());
		buf.writeVarInt(p.entries().size());
		for (Entry e : p.entries()) {
			buf.writeString(e.itemId(), 128);
			buf.writeLong(e.buyPrice());
			buf.writeLong(e.sellPrice());
			buf.writeLong(e.stock());
			buf.writeBoolean(e.canBuy());
			buf.writeBoolean(e.canSell());
		}
		// U11b：筛选维度（facet），客户端拿去画分类栏；必须紧跟 entries，readPage 按此顺序读
		buf.writeVarInt(p.mods().size());
		for (String m : p.mods()) buf.writeString(m, 48);
		buf.writeVarInt(p.categories().size());
		for (String c : p.categories()) buf.writeString(c, 64);
		// 最后：GUI 一屏行数（服务端 shop/settings.json 的 visibleRows），接收方在 readPage 之后读
		buf.writeVarInt(com.akira.redtale.economy.config.ShopConfig.visibleRows());
	}

	public static Page readPage(PacketByteBuf buf) {
		String shopId = buf.readString(64);
		String query = buf.readString(128);
		int page = buf.readVarInt();
		int totalPages = buf.readVarInt();
		int totalItems = buf.readVarInt();
		int n = buf.readVarInt();
		int max = ShopConfig.pageSize() + 1; // 容错一档，防止恶意端塞超长列表
		List<Entry> list = new ArrayList<>(Math.min(n, max));
		for (int i = 0; i < n; i++) {
			String id = buf.readString(128);
			long buy = buf.readLong();
			long sell = buf.readLong();
			long stock = buf.readLong();
			boolean canBuy = buf.readBoolean();
			boolean canSell = buf.readBoolean();
			if (i < max) list.add(new Entry(id, buy, sell, stock, canBuy, canSell));
		}
		List<String> mods = new ArrayList<>();
		int mn = Math.min(buf.readVarInt(), 200);
		for (int i = 0; i < mn; i++) mods.add(buf.readString(48));
		List<String> cats = new ArrayList<>();
		int cn = Math.min(buf.readVarInt(), 400);
		for (int i = 0; i < cn; i++) cats.add(buf.readString(64));
		// 尾部：visibleRows（服务端 visibleRows，客户端一屏几行）；读完协议才算对称
		if (buf.isReadable()) {
			lastVisibleRows = Math.max(1, Math.min(54, buf.readVarInt()));
		}
		return new Page(shopId, query, page, totalPages, totalItems, list, mods, cats);
	}

	public static void writeAction(PacketByteBuf buf, ActionResult r) {
		buf.writeBoolean(r.ok());
		buf.writeString(r.messageKey() == null ? "" : r.messageKey(), 96);
		buf.writeString(r.code() == null ? "" : r.code(), 32);
		buf.writeLong(r.balance());
		buf.writeLong(r.stockRemaining());
	}

	public static ActionResult readAction(PacketByteBuf buf) {
		return new ActionResult(buf.readBoolean(), buf.readString(96), buf.readString(32),
				buf.readLong(), buf.readLong());
	}

	/** 分页参数归一化：任何输入都落在一个合法的 [0, totalPages-1]。 */
	public static int clampPage(int requested, int totalItems, int pageSize) {
		if (pageSize <= 0) return 0;
		int totalPages = Math.max(1, (totalItems + pageSize - 1) / pageSize);
		if (requested < 0) return 0;
		return Math.min(requested, totalPages - 1);
	}

	public static int totalPages(int totalItems, int pageSize) {
		if (pageSize <= 0) return 0;
		return (totalItems + pageSize - 1) / pageSize;
	}
}
