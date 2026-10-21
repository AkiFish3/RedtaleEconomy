package com.akira.redtale.economy.net;

import net.minecraft.network.PacketByteBuf;

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

	private static final Codec<Rank> RANK = Codec.of("guild/rank",
			(buf, r) -> {
				buf.writeString(r.name() == null ? "" : r.name(), 64);
				buf.writeLong(r.rep());
			},
			buf -> new Rank(buf.readString(64), buf.readLong()));

	/** 档位表上限（读完即丢，不影响后续字段）。 */
	private static final int MAX_RANKS = 64;

	private GuildProtocol() {
	}

	public static void write(PacketByteBuf buf, Snapshot s) {
		buf.writeString(s.currentName() == null ? "" : s.currentName(), 64);
		buf.writeVarInt(s.currentIndex());
		buf.writeLong(s.rep());
		Codecs.writeList(buf, s.ranks(), RANK);
	}

	public static Snapshot read(PacketByteBuf buf) {
		String name = buf.readString(64);
		int idx = buf.readVarInt();
		long rep = buf.readLong();
		return new Snapshot(name, idx, rep, Codecs.readList(buf, MAX_RANKS, RANK));
	}
}
