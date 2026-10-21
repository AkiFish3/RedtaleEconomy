package com.akira.redtale.economy.client.ui.hub;

import com.akira.redtale.economy.RedtaleEconomy;
import com.akira.redtale.economy.net.ClientSend;
import com.akira.redtale.economy.net.LedgerProtocol;
import com.akira.redtale.economy.net.PacketIds;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.network.PacketByteBuf;

/**
 * T16 流水查看器接收器。
 *
 * <p>与委托板同构：收到快照只存 {@link #LAST}，界面由 {@link HubScreen} 的画法读它。
 * 唯一差别是这里<b>不主动抢屏</b> —— 流水是查账用的副页，不该因为收到一个包就打断玩家。
 */
@Environment(EnvType.CLIENT)
public final class LedgerClientNetworking {

	/** 最近一次快照。 */
	public static volatile LedgerProtocol.Snapshot LAST = LedgerProtocol.Snapshot.empty();
	/** 服务端判定过的管理员标志（界面据此显示「全部玩家」开关）。 */
	public static volatile boolean admin;

	private LedgerClientNetworking() {
	}

	public static void register() {
		ClientPlayNetworking.registerGlobalReceiver(PacketIds.S2C_LEDGER, (client, handler, buf, sender) -> {
			LedgerProtocol.Snapshot snap;
			try {
				snap = LedgerProtocol.read(buf);
			} catch (Exception e) {
				RedtaleEconomy.LOGGER.warn("[ledger] 快照解析失败：{}", e.getMessage());
				return;
			}
			LAST = snap;
			admin = snap.admin();
			client.execute(() -> {
				if (client.currentScreen instanceof HubScreen hub && hub.devPageId().equals("ledger")) {
					hub.setStatus("流水 " + snap.total() + " 笔");
				}
			});
		});
		RedtaleEconomy.LOGGER.info("[ledger] 流水接收器已注册");
	}

	/** 按当前筛选条件请求一页。 */
	public static void request() {
		request(HubLedgerPage.daysIdx(), HubLedgerPage.dirIdx(), HubLedgerPage.typeIdx(), HubLedgerPage.allPlayers());
	}

	public static void request(int daysIdx, int dir, int typeIdx, boolean all) {
		try {
			PacketByteBuf buf = new PacketByteBuf(io.netty.buffer.Unpooled.buffer());
			LedgerProtocol.writeQuery(buf, new LedgerProtocol.Query(daysIdx, dir, typeIdx, all));
			ClientSend.send(PacketIds.C2S_LEDGER_REQ, buf);
		} catch (Exception e) {
			RedtaleEconomy.LOGGER.warn("[ledger] 请求流水失败：{}", e.getMessage());
		}
	}
}
