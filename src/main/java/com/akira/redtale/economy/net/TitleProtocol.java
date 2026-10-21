package com.akira.redtale.economy.net;

import net.minecraft.network.PacketByteBuf;

import java.util.List;

/** 称号快照：拥有列表 + 当前生效。 */
public final class TitleProtocol {

	public record Snapshot(boolean enabled, String current, List<String> owned) {
		public static Snapshot empty() {
			return new Snapshot(false, "", List.of());
		}
	}

	private static final Codec<String> TITLE = Codecs.string(128);

	/** 最多保留的称号条数（超出的读完即丢，不影响后续字段）。 */
	private static final int MAX_OWNED = 256;

	private TitleProtocol() {
	}

	public static void write(PacketByteBuf buf, Snapshot s) {
		buf.writeBoolean(s.enabled());
		buf.writeString(s.current() == null ? "" : s.current(), 128);
		Codecs.writeList(buf, s.owned(), TITLE);
	}

	public static Snapshot read(PacketByteBuf buf) {
		boolean en = buf.readBoolean();
		String cur = buf.readString(128);
		return new Snapshot(en, cur, Codecs.readList(buf, MAX_OWNED, TITLE));
	}
}
