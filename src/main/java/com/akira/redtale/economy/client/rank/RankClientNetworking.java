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

	/**
	 * 请求一次（U9 收尾：走专用包，不再借 {@code /rt rank} 命令）。
	 *
	 * @param activeOnly 界面上的「只看活跃」开关；服务端按它选缓存榜，不重算
	 */
	public static void request(boolean activeOnly) {
		if (!ClientPlayNetworking.canSend(PacketIds.C2S_RANK_REQ)) return;
		net.minecraft.network.PacketByteBuf buf = new net.minecraft.network.PacketByteBuf(
				io.netty.buffer.Unpooled.buffer());
		RankProtocol.writeRequest(buf, new RankProtocol.Request(activeOnly));
		ClientPlayNetworking.send(PacketIds.C2S_RANK_REQ, buf);
	}

	/** 默认只要活跃榜。 */
	public static void request() {
		request(true);
	}
}
