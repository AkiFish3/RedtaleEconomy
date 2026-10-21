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
	public record Page(String shopId, String shopName, String query, int page, int totalPages,
			int totalItems, List<Entry> entries, List<String> mods, List<String> categories) {
		public static Page empty(String shopId, String query) {
			return new Page(shopId, shopId, query, 0, 0, 0, List.of(), List.of(), List.of());
		}

		/** 兼容旧构造点（无 facet / 无商店名）。 */
		public Page(String shopId, String query, int page, int totalPages, int totalItems, List<Entry> entries) {
			this(shopId, shopId, query, page, totalPages, totalItems, entries, List.of(), List.of());
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

	/**
	 * T01：税率信息（打开商店时下发一次）。
	 *
	 * <p><b>为什么单独一个包而不是塞进 {@link Page}：</b>{@code Page} 的每个 {@link Entry}
	 * 都要按玩家算免税，而 {@code Entry} 是对所有玩家一样的商品快照（缓存友好）。
	 * 税率只跟「谁在看」有关，所以按玩家发，客户端自己乘。
	 *
	 * <p>{@code exemptMine} 已经由服务端按协会等级算好；{@code mode/items} 让客户端
	 * 能精确判断「这件商品收不收」，避免界面显示含税价、结算却不收（或反过来）。
	 * 客户端算出来的只是<b>显示值</b>，真正扣多少以服务端 {@code ActionResult} 为准。
	 */
	public record TaxInfo(boolean enabled, int buyPct, int sellPct, boolean exemptMine,
			String mode, List<String> items) {

		public static TaxInfo none() {
			return new TaxInfo(false, 0, 0, false, "none", List.of());
		}

		/** 买入要加收的税（铜，向上取整）。税率关/免税/名单外 → 0。 */
		public long buyTax(String itemId, long price) {
			return calc(buyPct, itemId, price);
		}

		/** 卖出被扣掉的税（铜，向上取整）。 */
		public long sellTax(String itemId, long income) {
			return calc(sellPct, itemId, income);
		}

		private long calc(int pct, String itemId, long amount) {
			if (!enabled || exemptMine || pct <= 0 || amount <= 0) return 0;
			if (!taxedItem(itemId)) return 0;
			return (amount * pct + 99) / 100;
		}

		/** 与服务端 {@code ShopTax.itemTaxed} 同口径：none=全收；whitelist=名单内；blacklist=名单外。 */
		public boolean taxedItem(String itemId) {
			if ("none".equalsIgnoreCase(mode)) return true;
			boolean in = false;
			if (itemId != null && items != null) {
				for (String raw : items) {
					if (raw == null) continue;
					String pat = raw.trim();
					if (pat.isEmpty()) continue;
					if (pat.endsWith(":*")) {
						if (itemId.startsWith(pat.substring(0, pat.length() - 1))) {
							in = true;
							break;
						}
					} else if (pat.equals(itemId)) {
						in = true;
						break;
					}
				}
			}
			return "whitelist".equalsIgnoreCase(mode) ? in : !in;
		}
	}

	private ShopProtocol() {
	}

	// ---------------------------------------------------------------- 税率（T01）

	public static void writeTax(PacketByteBuf buf, TaxInfo t) {
		buf.writeBoolean(t.enabled());
		buf.writeVarInt(t.buyPct());
		buf.writeVarInt(t.sellPct());
		buf.writeBoolean(t.exemptMine());
		buf.writeString(t.mode() == null ? "blacklist" : t.mode(), 16);
		buf.writeVarInt(t.items().size());
		for (String it : t.items()) {
			buf.writeString(it == null ? "" : it, 128);
		}
	}

	public static TaxInfo readTax(PacketByteBuf buf) {
		boolean enabled = buf.readBoolean();
		int buyPct = buf.readVarInt();
		int sellPct = buf.readVarInt();
		boolean exempt = buf.readBoolean();
		String mode = buf.readString(16);
		int n = Math.min(buf.readVarInt(), 512);
		List<String> items = new ArrayList<>(n);
		for (int i = 0; i < n; i++) {
			items.add(buf.readString(128));
		}
		return new TaxInfo(enabled, buyPct, sellPct, exempt, mode, items);
	}

	public static int lastVisibleRows() {
		return lastVisibleRows;
	}

	// ---------------------------------------------------------------- 编码

	public static void writePage(PacketByteBuf buf, Page p) {
		buf.writeString(p.shopId(), 64);
		buf.writeString(p.shopName() == null ? p.shopId() : p.shopName(), 64);
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
		String shopName = buf.readString(64);
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
		return new Page(shopId, shopName.isBlank() ? shopId : shopName, query, page, totalPages,
				totalItems, list, mods, cats);
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
