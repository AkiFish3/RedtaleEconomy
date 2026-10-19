package com.akira.redtale.economy.client;

import com.akira.redtale.economy.RedtaleEconomy;
import com.akira.redtale.economy.client.shop.ShopScreen;
import com.akira.redtale.economy.net.PacketIds;
import com.akira.redtale.economy.net.ShopProtocol;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.network.PacketByteBuf;

/** 客户端收包 + 发包封装。所有状态更新都切回主线程，避免 netty 线程碰 UI。 */
@Environment(EnvType.CLIENT)
public final class ShopClientNetworking {

	private ShopClientNetworking() {
	}

	public static void register() {
		ClientPlayNetworking.registerGlobalReceiver(PacketIds.S2C_PAGE, (client, handler, buf, sender) -> {
			ShopProtocol.Page p = ShopProtocol.readPage(buf);
			int rows = buf.isReadable() ? buf.readVarInt() : 9;
			client.execute(() -> {
				ShopClientState.setVisibleRows(rows);
				ShopClientState.setPage(p);
			});
		});

		ClientPlayNetworking.registerGlobalReceiver(PacketIds.S2C_OPEN, (client, handler, buf, sender) -> {
			String shopId = buf.readString(64);
			client.execute(() -> client.setScreen(new ShopScreen(shopId)));
		});

		ClientPlayNetworking.registerGlobalReceiver(PacketIds.S2C_ACTION, (client, handler, buf, sender) -> {
			ShopProtocol.ActionResult r = ShopProtocol.readAction(buf);
			client.execute(() -> ShopClientState.setAction(r));
		});

		RedtaleEconomy.LOGGER.info("[net] 客户端接收器已注册");
	}

	public static void requestPage(String shopId, int page, String query) {
		if (MinecraftClient.getInstance().getNetworkHandler() == null) return;
		PacketByteBuf buf = new PacketByteBuf(io.netty.buffer.Unpooled.buffer());
		buf.writeString(shopId, 64);
		buf.writeVarInt(page);
		buf.writeString(query == null ? "" : query, 128);
		ClientPlayNetworking.send(PacketIds.C2S_REQUEST_PAGE, buf);
	}

	public static void buy(String shopId, String itemId, int count) {
		sendTrade(PacketIds.C2S_BUY, shopId, itemId, count);
	}

	public static void sell(String shopId, String itemId, int count) {
		sendTrade(PacketIds.C2S_SELL, shopId, itemId, count);
	}

	private static void sendTrade(net.minecraft.util.Identifier channel, String shopId, String itemId, int count) {
		if (MinecraftClient.getInstance().getNetworkHandler() == null) return;
		PacketByteBuf buf = new PacketByteBuf(io.netty.buffer.Unpooled.buffer());
		buf.writeString(shopId, 64);
		buf.writeString(itemId, 128);
		buf.writeVarInt(Math.max(1, Math.min(6400, count)));
		ClientPlayNetworking.send(channel, buf);
	}
}
