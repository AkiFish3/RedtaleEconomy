package com.akira.redtale.economy.client.ui.hub;

import com.akira.redtale.economy.RedtaleEconomy;
import com.akira.redtale.economy.net.BoardProtocol;
import com.akira.redtale.economy.net.PacketIds;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.network.PacketByteBuf;

/**
 * 委托板接收器（T12）。
 *
 * <p>收到快照只做两件事：存进 {@link #LAST} 给 Hub 页读、用户没开界面就把界面打开。
 * 请求走 {@link #request()}（{@code C2S_BOARD_REQ}），不再靠发 {@code /board} 命令
 * —— 命令会在聊天栏留一行回执，界面刷新时刷屏。
 */
@Environment(EnvType.CLIENT)
public final class BoardClientNetworking {

	/** 最近一次快照，供 Hub 的委托板页复用。 */
	public static volatile BoardProtocol.Snapshot LAST = BoardProtocol.Snapshot.empty();

	private BoardClientNetworking() {
	}

	public static void register() {
		ClientPlayNetworking.registerGlobalReceiver(PacketIds.S2C_BOARD, (client, handler, buf, sender) -> {
			BoardProtocol.Snapshot snap = BoardProtocol.read(buf);
			LAST = snap;
			client.execute(() -> {
				if (client.currentScreen instanceof HubScreen hub) {
					// 已在 Hub 上（比如在商店页敲 /board）：切到委托板页，别当成"已经打开了"直接返回。
					// 2026-10-21 截图夹具抓到的：日志里 page=shop，/board 之后页面根本没动。
					hub.setPage(HubPage.BOARD);
					return;
				}
				if (ClientUiConfig.useHub) {
					HubScreen.open(
							HubPage.BOARD);
				}
			});
		});
		RedtaleEconomy.LOGGER.info("[board] 委托板接收器已注册");
	}

	/** 请求一次快照（页面打开 / 点刷新 / 动作回包后）。 */
	public static void request() {
		try {
			ClientPlayNetworking.send(PacketIds.C2S_BOARD_REQ, new PacketByteBuf(
					io.netty.buffer.Unpooled.buffer()));
		} catch (Exception e) {
			RedtaleEconomy.LOGGER.warn("[board] 请求委托板失败：{}", e.getMessage());
		}
	}
}
