package com.akira.redtale.economy.net;

import com.akira.redtale.economy.config.ShopConfig;
import net.minecraft.network.PacketByteBuf;

import java.util.List;

/**
 * 商店协议编解码（spec §7）。
 *
 * <p>核心约束：服务端<b>永不</b>下发全量商品。一次请求 = 一页 = {@code shop.pageSize} 条。
 * 客户端发来的 page 会被钳进 [0, totalPages-1]，负数/超大值都不会导致越界。
 *
 * <p>T33：三处列表（商品 / 模组 facet / 分类 facet）统一走 {@link Codecs}。
 * 旧写法 {@code min(n, 200)} 只读 200 条就把后面当 facet 分类读，超限必然错位。
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

	private static final Codec<Entry> ENTRY = Codec.of("shop/entry",
			(buf, e) -> {
				buf.writeString(e.itemId() == null ? "" : e.itemId(), 128);
				buf.writeLong(e.buyPrice());
				buf.writeLong(e.sellPrice());
				buf.writeLong(e.stock());
				buf.writeBoolean(e.canBuy());
				buf.writeBoolean(e.canSell());
			},
			buf -> new Entry(buf.readString(128), buf.readLong(), buf.readLong(), buf.readLong(),
					buf.readBoolean(), buf.readBoolean()));

	private static final Codec<String> TAX_ITEM = Codecs.string(128);
	private static final Codec<String> MOD = Codecs.string(48);
	private static final Codec<String> CATEGORY = Codecs.string(64);

	/** 税率名单上限 / facet 上限（读完即丢，不影响后面的 visibleRows）。 */
	private static final int MAX_TAX_ITEMS = 512;
	private static final int MAX_MODS = 200;
	private static final int MAX_CATEGORIES = 400;

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
		Codecs.writeList(buf, t.items(), TAX_ITEM);
	}

	public static TaxInfo readTax(PacketByteBuf buf) {
		boolean enabled = buf.readBoolean();
		int buyPct = buf.readVarInt();
		int sellPct = buf.readVarInt();
		boolean exempt = buf.readBoolean();
		String mode = buf.readString(16);
		List<String> items = Codecs.readList(buf, MAX_TAX_ITEMS, TAX_ITEM);
		return new TaxInfo(enabled, buyPct, sellPct, exempt, mode, items);
	}

	public static int lastVisibleRows() {
		return lastVisibleRows;
	}

	// ---------------------------------------------------------------- 编码

	public static void writePage(PacketByteBuf buf, Page p) {
		writePage(buf, p, com.akira.redtale.economy.config.ShopConfig.visibleRows());
	}

	/**
	 * @param visibleRows 一屏行数（服务端 {@code shop/settings.json}）。单独传参是为了让
	 *                    往返测试不依赖配置层（headless 单测里没有 shop 配置）。
	 */
	public static void writePage(PacketByteBuf buf, Page p, int visibleRows) {
		buf.writeString(p.shopId(), 64);
		buf.writeString(p.shopName() == null ? p.shopId() : p.shopName(), 64);
		buf.writeString(p.query() == null ? "" : p.query(), 128);
		buf.writeVarInt(p.page());
		buf.writeVarInt(p.totalPages());
		buf.writeVarInt(p.totalItems());
		Codecs.writeList(buf, p.entries(), ENTRY);
		// U11b：筛选维度（facet），客户端拿去画分类栏；必须紧跟 entries，readPage 按此顺序读
		Codecs.writeList(buf, p.mods(), MOD);
		Codecs.writeList(buf, p.categories(), CATEGORY);
		// 最后：GUI 一屏行数（服务端 shop/settings.json 的 visibleRows），接收方在 readPage 之后读
		buf.writeVarInt(visibleRows);
	}

	public static Page readPage(PacketByteBuf buf) {
		return readPage(buf, ShopConfig.pageSize() + 1);
	}

	/**
	 * @param maxEntries 商品条数上限（默认 {@code pageSize()+1}，容错一档防恶意端塞超长列表）。
	 *                   超出的条目<b>读完即丢</b>，不打断后续 facet 与尾部字段。
	 */
	public static Page readPage(PacketByteBuf buf, int maxEntries) {
		String shopId = buf.readString(64);
		String shopName = buf.readString(64);
		String query = buf.readString(128);
		int page = buf.readVarInt();
		int totalPages = buf.readVarInt();
		int totalItems = buf.readVarInt();
		List<Entry> list = Codecs.readList(buf, maxEntries, ENTRY);
		List<String> mods = Codecs.readList(buf, MAX_MODS, MOD);
		List<String> cats = Codecs.readList(buf, MAX_CATEGORIES, CATEGORY);
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
