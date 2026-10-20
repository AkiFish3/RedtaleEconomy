package com.akira.redtale.economy.net;

import net.minecraft.network.PacketByteBuf;

/** 余额快照（raw 铜值）与请求。 */
public final class BalanceProtocol {

	private BalanceProtocol() {
	}

	public static void write(PacketByteBuf buf, long raw) {
		buf.writeLong(raw);
	}

	public static long read(PacketByteBuf buf) {
		return buf.readLong();
	}
}
