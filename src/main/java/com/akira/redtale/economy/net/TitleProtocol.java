package com.akira.redtale.economy.net;

import net.minecraft.network.PacketByteBuf;

import java.util.ArrayList;
import java.util.List;

/** 称号快照：拥有列表 + 当前生效。 */
public final class TitleProtocol {

	public record Snapshot(boolean enabled, String current, List<String> owned) {
		public static Snapshot empty() {
			return new Snapshot(false, "", List.of());
		}
	}

	private TitleProtocol() {
	}

	public static void write(PacketByteBuf buf, Snapshot s) {
		buf.writeBoolean(s.enabled());
		buf.writeString(s.current() == null ? "" : s.current(), 128);
		buf.writeVarInt(s.owned().size());
		for (String t : s.owned()) buf.writeString(t, 128);
	}

	public static Snapshot read(PacketByteBuf buf) {
		boolean en = buf.readBoolean();
		String cur = buf.readString(128);
		int n = Math.min(buf.readVarInt(), 256);
		List<String> list = new ArrayList<>(n);
		for (int i = 0; i < n; i++) list.add(buf.readString(128));
		return new Snapshot(en, cur, list);
	}
}
