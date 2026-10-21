package com.akira.redtale.economy.client.ui.hub;

import com.akira.redtale.economy.net.ClientSend;
import com.akira.redtale.economy.net.MailProtocol;
import com.akira.redtale.economy.net.PacketIds;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.network.PacketByteBuf;

/**
 * T62：邮件页客户端收包。
 *
 * <p>只保存最新快照并请求重画，<b>不打开任何界面</b>——邮件页是 Hub 的一个页签，
 * 收包时若自己 setScreen 会把玩家从 Hub 顶出去（T56 的教训）。
 */
@Environment(EnvType.CLIENT)
public final class MailClientNetworking {

	private static boolean registered;

	/** 最近一份快照（页面直接读，不做本地推算）。 */
	public static volatile MailProtocol.Snapshot LAST = MailProtocol.Snapshot.empty();
	public static volatile long LAST_AT;

	private MailClientNetworking() {
	}

	public static void register() {
		if (registered) return;
		registered = true;
		ClientPlayNetworking.registerGlobalReceiver(PacketIds.S2C_MAIL,
				(client, handler, buf, sender) -> {
					MailProtocol.Snapshot snap = MailProtocol.read(buf);
					client.execute(() -> {
						LAST = snap;
						LAST_AT = System.currentTimeMillis();
					});
				});
	}

	/** 请求一次快照（进页 / 领取后刷新）。 */
	public static void request() {
		if (!ClientSend.ready()) return;
		ClientSend.send(PacketIds.C2S_MAIL_REQ, new PacketByteBuf(io.netty.buffer.Unpooled.buffer()));
	}

	/** 领取一条：{@code kind} 见 {@link MailProtocol}，{@code index} 是物品队列下标（货币为 -1）。 */
	public static void claim(int kind, int index) {
		if (!ClientSend.ready()) return;
		PacketByteBuf buf = new PacketByteBuf(io.netty.buffer.Unpooled.buffer());
		buf.writeVarInt(Math.max(0, kind));
		buf.writeVarInt(Math.max(-1, index));
		ClientSend.send(PacketIds.C2S_MAIL_CLAIM, buf);
	}
}
