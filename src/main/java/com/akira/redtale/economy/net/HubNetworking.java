package com.akira.redtale.economy.net;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.network.ServerPlayerEntity;

/**
 * Hub 页面切换（U8b）：只负责「打开哪一页」，数据仍由各页自己的快照包下发。
 *
 * <p>这样新增页面不用再改协议结构，也避免把页面内容塞进一个万能包。
 */
public final class HubNetworking {

	public static final int MAX_PAGE_LEN = 24;

	private HubNetworking() {
	}

	public static void open(ServerPlayerEntity player, String page) {
		if (player == null || page == null || page.isBlank()) return;
		if (!ServerPlayNetworking.canSend(player, PacketIds.S2C_HUB_OPEN)) return;
		final String p = page.length() > MAX_PAGE_LEN ? page.substring(0, MAX_PAGE_LEN) : page;
		net.minecraft.network.PacketByteBuf buf = new net.minecraft.network.PacketByteBuf(
				io.netty.buffer.Unpooled.buffer());
		buf.writeString(p, MAX_PAGE_LEN);
		ServerPlayNetworking.send(player, PacketIds.S2C_HUB_OPEN, buf);
	}
}
