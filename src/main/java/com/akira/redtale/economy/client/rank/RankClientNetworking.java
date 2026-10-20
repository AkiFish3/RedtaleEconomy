package com.akira.redtale.economy.client.rank;

import com.akira.redtale.economy.net.PacketIds;
import com.akira.redtale.economy.net.RankProtocol;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import java.util.List;

/** 排行榜快照接收（U9b）。只缓存，不主动开界面（由 Hub 的排行页决定何时要）。 */
@Environment(EnvType.CLIENT)
public final class RankClientNetworking {

	public static volatile RankProtocol.Snapshot LAST = RankProtocol.Snapshot.empty();

	private RankClientNetworking() {
	}

	public static void register() {
		ClientPlayNetworking.registerGlobalReceiver(PacketIds.S2C_RANK, (client, handler, buf, sender) -> {
			RankProtocol.Snapshot snap = RankProtocol.read(buf);
			client.execute(() -> LAST = snap);
		});
	}

	public static List<RankProtocol.Row> top() {
		return LAST.top();
	}

	public static RankProtocol.Row self() {
		return LAST.self();
	}

	/** 请求一次（发 /rt rank 由服务端下发；这里只在页面打开前兜底）。 */
	public static void request() {
		var mc = net.minecraft.client.MinecraftClient.getInstance();
		if (mc.player != null) {
			mc.player.networkHandler.sendChatCommand("rt rank");
		}
	}
}
