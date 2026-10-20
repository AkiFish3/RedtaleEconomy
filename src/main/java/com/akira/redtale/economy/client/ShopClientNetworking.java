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
			int rows = ShopProtocol.lastVisibleRows();
			client.execute(() -> {
				ShopClientState.setVisibleRows(rows);
				ShopClientState.setPage(p);
			});
		});

		ClientPlayNetworking.registerGlobalReceiver(PacketIds.S2C_OPEN, (client, handler, buf, sender) -> {
			String shopId = buf.readString(64);
			client.execute(() -> {
				if (com.akira.redtale.economy.client.ui.hub.ClientUiConfig.useHub) {
					com.akira.redtale.economy.client.ui.hub.HubScreen.open(com.akira.redtale.economy.client.ui.hub.HubPage.SHOP);
				} else {
					client.setScreen(new ShopScreen(shopId));
				}
			});
		});

		ClientPlayNetworking.registerGlobalReceiver(PacketIds.S2C_OPEN_EDIT, (client, handler, buf, sender) -> {
			String shopId = buf.readString(64);
			client.execute(() -> client.setScreen(new com.akira.redtale.economy.client.admin.ShopEditScreen(shopId)));
		});

		ClientPlayNetworking.registerGlobalReceiver(PacketIds.S2C_ACTION, (client, handler, buf, sender) -> {
			ShopProtocol.ActionResult r = ShopProtocol.readAction(buf);
			client.execute(() -> ShopClientState.setAction(r));
		});

		RedtaleEconomy.LOGGER.info("[net] 客户端接收器已注册");
	}

	public static void requestPage(String shopId, int page, String query) {
		requestPage(shopId, page, query, ShopClientState.filter());
	}

	/** U11b：带筛选条件翻页；条件随包发送，服务端负责执行。 */
	public static void requestPage(String shopId, int page, String query,
			com.akira.redtale.economy.net.ShopFilter filter) {
		if (MinecraftClient.getInstance().getNetworkHandler() == null) return;
		PacketByteBuf buf = new PacketByteBuf(io.netty.buffer.Unpooled.buffer());
		buf.writeString(shopId, 64);
		buf.writeVarInt(page);
		buf.writeString(query == null ? "" : query, 128);
		(filter == null ? com.akira.redtale.economy.net.ShopFilter.NONE : filter).write(buf);
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
