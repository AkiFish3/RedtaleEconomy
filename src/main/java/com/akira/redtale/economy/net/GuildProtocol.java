package com.akira.redtale.economy.net;

import net.minecraft.network.PacketByteBuf;

import java.util.ArrayList;
import java.util.List;

/** 协会快照：档位表 + 当前声望/档位。 */
public final class GuildProtocol {

	public record Rank(String name, long rep) {
	}

	public record Snapshot(String currentName, int currentIndex, long rep, List<Rank> ranks) {
		public static Snapshot empty() {
			return new Snapshot("", 0, 0, List.of());
		}
	}

	private GuildProtocol() {
	}

	public static void write(PacketByteBuf buf, Snapshot s) {
		buf.writeString(s.currentName() == null ? "" : s.currentName(), 64);
		buf.writeVarInt(s.currentIndex());
		buf.writeLong(s.rep());
		buf.writeVarInt(s.ranks().size());
		for (Rank r : s.ranks()) {
			buf.writeString(r.name(), 64);
			buf.writeLong(r.rep());
		}
	}

	public static Snapshot read(PacketByteBuf buf) {
		String name = buf.readString(64);
		int idx = buf.readVarInt();
		long rep = buf.readLong();
		int n = Math.min(buf.readVarInt(), 64);
		List<Rank> ranks = new ArrayList<>(n);
		for (int i = 0; i < n; i++) {
			ranks.add(new Rank(buf.readString(64), buf.readLong()));
		}
		return new Snapshot(name, idx, rep, ranks);
	}
}
