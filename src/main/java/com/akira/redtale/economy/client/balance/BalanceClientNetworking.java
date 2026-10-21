package com.akira.redtale.economy.client.balance;

import com.akira.redtale.economy.net.PacketIds;
import com.akira.redtale.economy.net.ClientSend;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.util.Identifier;

/** 余额接收（Hub 侧边栏显示用）。 */
@Environment(EnvType.CLIENT)
public final class BalanceClientNetworking {

	public static volatile long RAW = -1;

	private BalanceClientNetworking() {
	}

	public static void register() {
		ClientPlayNetworking.registerGlobalReceiver(PacketIds.S2C_BALANCE, (client, handler, buf, sender) -> {
			long v = buf.readLong();
			client.execute(() -> RAW = v);
		});
	}

	/** 请求一次当前余额（服务端回包后 {@link #RAW} 更新）。 */
	public static void request() {
		Identifier id = PacketIds.C2S_BALANCE_REQ;
		ClientSend.send(id, new PacketByteBuf(io.netty.buffer.Unpooled.buffer()));
	}
}
