package com.akira.redtale.economy.client.pay;

import com.akira.redtale.economy.net.PacketIds;
import com.akira.redtale.economy.net.ClientSend;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.network.PacketByteBuf;

@Environment(EnvType.CLIENT)
public final class PayClientNetworking {

	private PayClientNetworking() {
	}

	public static void pay(String target, long amount) {
		PacketByteBuf buf = new PacketByteBuf(io.netty.buffer.Unpooled.buffer());
		buf.writeString(target == null ? "" : target, 32);
		buf.writeLong(amount);
		ClientSend.send(PacketIds.C2S_PAY, buf);
	}
}
