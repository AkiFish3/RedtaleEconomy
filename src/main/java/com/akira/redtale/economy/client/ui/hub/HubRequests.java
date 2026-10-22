package com.akira.redtale.economy.client.ui.hub;

import com.akira.redtale.economy.client.ShopClientNetworking;
import com.akira.redtale.economy.client.ShopClientState;
import net.minecraft.client.MinecraftClient;

/**
 * T32 批次6：Hub 的「拉数据 / 发命令」出口集中在这里。
 *
 * <p>这些方法原先散在 {@link HubScreen} 里（取 mc.player → 判空 → 发请求，四份同样的样板），
 * 与状态字段混在一起，令主类始终降不到 300 行以下。本类只发请求，不碰几何与渲染。
 */
final class HubRequests {
	private HubRequests() {
	}

	/** 切到某页时顺手拉一次数据（服务端只回快照，不做写操作）。 */
	static void pullData(HubScreen host, HubPage p) {
		// T56：进/退任务页都要告诉服务端（决定快照走 Hub 通道还是「弹独立面板」通道）
		TasksHubClientNetworking.sync(p);
		var mc = MinecraftClient.getInstance();
		if (mc.player == null) return;
		switch (p) {
			case SHOP -> host.requestShopPage();
			case RANK -> com.akira.redtale.economy.client.rank.RankClientNetworking
					.request(host.rankActiveOnly);
			case MARKET -> com.akira.redtale.economy.client.market.MarketClientNetworking.request();
			case BOARD -> BoardClientNetworking.request();
			case LEDGER -> LedgerClientNetworking.request();
			case ADMIN -> AdminClientNetworking.request();
			case CHECKIN -> chat("checkin status");
			case MAIL -> MailClientNetworking.request();
			// ★以下五页原来只在「点侧栏」时拉，深链（setPage / 命令打开 Hub）进来就是空页★
			case STORAGE -> com.akira.redtale.economy.client.warehouse.WarehouseClientNetworking.request();
			case TITLES -> com.akira.redtale.economy.client.title.TitlesClientNetworking.request();
			case PASS -> chat("pass");
			case BOUNTY -> chat("bounty list");
			case GUILD -> chat("guild");
			default -> {
			}
		}
	}

	/** 发一条客户端命令（没进世界时静默，避免单机菜单里崩）。 */
	private static void chat(String cmd) {
		var mc = MinecraftClient.getInstance();
		if (mc.player != null) mc.player.networkHandler.sendChatCommand(cmd);
	}

	// 原来还有 requestGuild / requestPass / requestBounty 三个薄封装，侧栏那条 if 链删掉后
	// 调用点全归到 pullData 的 switch（一并死了），三个方法一起删掉，别留半截。

	static void requestShopPage(HubScreen host) {
		host.shopRequested = true;
		String sid = ShopClientState.shopIdToRequest();
		if (sid.isEmpty()) return;   // 还没收到 S2C_OPEN，等它到了再请求，别发空 id 换回空页
		ShopClientNetworking.requestPage(sid, 0, host.shopQuery.toString());
	}
}
