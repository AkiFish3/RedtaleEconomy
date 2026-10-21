package com.akira.redtale.economy.client.ui.hub;

import com.akira.redtale.economy.RedtaleEconomy;
import com.akira.redtale.economy.net.AdminProtocol;
import com.akira.redtale.economy.net.ClientSend;
import com.akira.redtale.economy.net.PacketIds;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.network.PacketByteBuf;

/**
 * T28 控制台接收器。
 *
 * <p>与流水页同构：只存 {@link #LAST}，不抢屏、不弹聊天 —— 控制台是副页，
 * 收到包就刷新版面即可。刷新时间由 {@link #RECEIVED_AT} 记录，界面用它显示「x 秒前」。
 */
@Environment(EnvType.CLIENT)
public final class AdminClientNetworking {

	/** 最近一次快照。 */
	public static volatile AdminProtocol.Snapshot LAST = AdminProtocol.Snapshot.empty();
	/** 本地收到时间（判断「数据是不是这一屏的」用，不用服务端时钟）。 */
	public static volatile long RECEIVED_AT;

	private AdminClientNetworking() {
	}

	public static void register() {
		ClientPlayNetworking.registerGlobalReceiver(PacketIds.S2C_ADMIN, (client, handler, buf, sender) -> {
			AdminProtocol.Snapshot snap;
			try {
				snap = AdminProtocol.read(buf);
			} catch (Exception e) {
				RedtaleEconomy.LOGGER.warn("[admin] 概览解析失败：{}", e.getMessage());
				return;
			}
			LAST = snap;
			RECEIVED_AT = System.currentTimeMillis();
			client.execute(() -> {
				if (client.currentScreen instanceof HubScreen hub && hub.pageId().equals("admin")) {
					hub.setStatus(snap.admin()
							? "控制台 " + snap.metrics().size() + " 项"
							: "需要管理员权限");
				}
			});
		});
		RedtaleEconomy.LOGGER.info("[admin] 控制台接收器已注册");
	}

	/** 请求一次概览（非管理员也发：服务端会回空快照，界面据此显示无权限）。 */
	public static void request() {
		try {
			PacketByteBuf buf = new PacketByteBuf(io.netty.buffer.Unpooled.buffer());
			AdminProtocol.writeQuery(buf, AdminProtocol.Query.current());
			ClientSend.send(PacketIds.C2S_ADMIN_REQ, buf);
		} catch (Exception e) {
			RedtaleEconomy.LOGGER.warn("[admin] 请求概览失败：{}", e.getMessage());
		}
	}
}
