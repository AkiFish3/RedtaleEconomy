package com.akira.redtale.economy.net;

import net.minecraft.network.PacketByteBuf;

import java.util.List;

/**
 * 玩家市场协议（R40）。
 *
 * <p>和商店一样：<b>服务端永不下发全量</b>，一次一页；客户端发来的页码/查询词全部当不可信输入。
 *
 * <p>物品本身用 SNBT 传（{@code itemSnbt}）—— 市场的货是带 NBT 的实物（附魔/自定义名/耐久），
 * 只传 itemId 会让客户端显示成一个「假的原版物品」，跟实际到手的不是同一个东西。
 *
 * <p>T33：挂单列表走 {@link Codecs}，截断与读取边界分离。
 */
public final class MarketProtocol {

	/** 一条挂单。{@code remainingMs} < 0 表示永不过期。 */
	public record Offer(String id, String itemSnbt, String itemId, int count, long price,
			String sellerName, boolean mine, long remainingMs) {
	}

	/** 一页列表 + 顶部统计（我的挂单数 / 上限 / 佣金率）。 */
	public record Snapshot(int page, int totalPages, int totalItems, boolean mineOnly, String query,
			List<Offer> offers, int myOpen, int maxOpen, double commissionPercent) {
		public static Snapshot empty() {
			return new Snapshot(0, 0, 0, false, "", List.of(), 0, 0, 0);
		}
	}

	/** 操作结果：{@code code} 供客户端分支，{@code message} 是服务端已本地化好的整句。 */
	public record ActionResult(boolean ok, String code, String message, long balance) {
	}

	private static final Codec<Offer> OFFER = Codec.of("market/offer",
			MarketProtocol::writeOffer, MarketProtocol::readOffer);

	/** 一页最多 54 条，多出来的读完即丢。 */
	private static final int MAX_OFFERS = 64;

	private MarketProtocol() {
	}

	// ---------------------------------------------------------------- 编码

	public static void writeOffer(PacketByteBuf buf, Offer o) {
		buf.writeString(o.id() == null ? "" : o.id(), 32);
		buf.writeString(o.itemSnbt() == null ? "" : o.itemSnbt(), 32767);
		buf.writeString(o.itemId() == null ? "" : o.itemId(), 128);
		buf.writeVarInt(o.count());
		buf.writeLong(o.price());
		buf.writeString(o.sellerName() == null ? "" : o.sellerName(), 32);
		buf.writeBoolean(o.mine());
		buf.writeLong(o.remainingMs());
	}

	public static Offer readOffer(PacketByteBuf buf) {
		String id = buf.readString(32);
		String snbt = buf.readString(32767);
		String itemId = buf.readString(128);
		int count = buf.readVarInt();
		long price = buf.readLong();
		String sellerName = buf.readString(32);
		boolean mine = buf.readBoolean();
		long remain = buf.readLong();
		return new Offer(id, snbt, itemId, count, price, sellerName, mine, remain);
	}

	public static void writeSnapshot(PacketByteBuf buf, Snapshot s) {
		buf.writeVarInt(s.page());
		buf.writeVarInt(s.totalPages());
		buf.writeVarInt(s.totalItems());
		buf.writeBoolean(s.mineOnly());
		buf.writeString(s.query() == null ? "" : s.query(), 64);
		Codecs.writeList(buf, s.offers(), OFFER);
		buf.writeVarInt(s.myOpen());
		buf.writeVarInt(s.maxOpen());
		buf.writeDouble(s.commissionPercent());
	}

	public static Snapshot readSnapshot(PacketByteBuf buf) {
		int page = buf.readVarInt();
		int totalPages = buf.readVarInt();
		int totalItems = buf.readVarInt();
		boolean mineOnly = buf.readBoolean();
		String query = buf.readString(64);
		List<Offer> list = Codecs.readList(buf, MAX_OFFERS, OFFER);
		int myOpen = buf.readVarInt();
		int maxOpen = buf.readVarInt();
		double commission = buf.readDouble();
		return new Snapshot(page, totalPages, totalItems, mineOnly, query, list, myOpen, maxOpen, commission);
	}

	public static void writeAction(PacketByteBuf buf, ActionResult r) {
		buf.writeBoolean(r.ok());
		buf.writeString(r.code() == null ? "" : r.code(), 32);
		buf.writeString(r.message() == null ? "" : r.message(), 512);
		buf.writeLong(r.balance());
	}

	public static ActionResult readAction(PacketByteBuf buf) {
		return new ActionResult(buf.readBoolean(), buf.readString(32), buf.readString(512), buf.readLong());
	}
}
