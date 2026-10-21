package com.akira.redtale.economy.client.market;

import com.akira.redtale.economy.net.MarketProtocol;
import com.akira.redtale.economy.net.ClientSend;
import com.akira.redtale.economy.net.PacketIds;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.StringNbtReader;
import net.minecraft.network.PacketByteBuf;

/**
 * 市场客户端状态（R40）。
 *
 * <p>和商店一致：客户端只缓存服务端下发的一页快照，<b>不自己算价格/归属</b>；
 * 买/撤单/挂出都是「发请求 → 等服务端回快照 + actionbar 结果」。
 */
@Environment(EnvType.CLIENT)
public final class MarketClientNetworking {

	public static volatile MarketProtocol.Snapshot LAST = MarketProtocol.Snapshot.empty();
	public static volatile MarketProtocol.ActionResult LAST_ACTION;
	public static volatile long LAST_ACTION_AT;
	/** 客户端查询词/页，切页回来能复现。 */
	public static volatile boolean mineOnly;
	public static volatile String query = "";
	public static volatile int page;

	private MarketClientNetworking() {
	}

	public static void register() {
		ClientPlayNetworking.registerGlobalReceiver(PacketIds.S2C_MARKET, (client, handler, buf, sender) -> {
			MarketProtocol.Snapshot snap = MarketProtocol.readSnapshot(buf);
			client.execute(() -> {
				LAST = snap;
				page = snap.page();
				mineOnly = snap.mineOnly();
			});
		});
		ClientPlayNetworking.registerGlobalReceiver(PacketIds.S2C_MARKET_ACTION, (client, handler, buf, sender) -> {
			MarketProtocol.ActionResult r = MarketProtocol.readAction(buf);
			client.execute(() -> {
				LAST_ACTION = r;
				LAST_ACTION_AT = System.currentTimeMillis();
			});
		});
	}

	public static void request() {
		request(page, mineOnly, query);
	}

	public static void request(int page, boolean mine, String q) {
		PacketByteBuf buf = new PacketByteBuf(io.netty.buffer.Unpooled.buffer());
		buf.writeVarInt(Math.max(0, page));
		buf.writeBoolean(mine);
		buf.writeString(q == null ? "" : q, 64);
		ClientSend.send(PacketIds.C2S_MARKET_REQ, buf);
	}

	public static void buy(String offerId) {
		PacketByteBuf buf = new PacketByteBuf(io.netty.buffer.Unpooled.buffer());
		buf.writeString(offerId == null ? "" : offerId, 32);
		ClientSend.send(PacketIds.C2S_MARKET_BUY, buf);
	}

	public static void cancel(String offerId) {
		PacketByteBuf buf = new PacketByteBuf(io.netty.buffer.Unpooled.buffer());
		buf.writeString(offerId == null ? "" : offerId, 32);
		ClientSend.send(PacketIds.C2S_MARKET_CANCEL, buf);
	}

	/** 挂出手上物品（服务端读主手，客户端只报价格）。 */
	public static void sell(long price) {
		PacketByteBuf buf = new PacketByteBuf(io.netty.buffer.Unpooled.buffer());
		buf.writeLong(Math.max(0, price));
		ClientSend.send(PacketIds.C2S_MARKET_SELL, buf);
	}

	/** SNBT → ItemStack；坏了返回空栈（渲染成灰块，不会崩）。 */
	public static ItemStack stackOf(MarketProtocol.Offer o) {
		if (o == null) return ItemStack.EMPTY;
		String snbt = o.itemSnbt();
		if (snbt == null || snbt.isEmpty()) return ItemStack.EMPTY;
		try {
			return ItemStack.fromNbt(StringNbtReader.parse(snbt));
		} catch (Exception e) {
			return ItemStack.EMPTY;
		}
	}
}
