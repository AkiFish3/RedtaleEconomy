package com.akira.redtale.economy.net;

import net.minecraft.network.PacketByteBuf;

import java.util.List;

/** 称号快照：拥有列表 + 当前生效的前缀/后缀 + 配色（T23）。 */
public final class TitleProtocol {

	/**
	 * @param enabled  称号模块是否可用
	 * @param prefix   当前前缀（旧字段 current 的语义，字段名改清楚）
	 * @param suffix   当前后缀（可空）
	 * @param gradient 是否渐变
	 * @param color1   起始色 0xRRGGBB
	 * @param color2   渐变终止色 0xRRGGBB
	 * @param owned    已拥有的称号
	 */
	public record Snapshot(boolean enabled, String prefix, String suffix, boolean gradient,
			int color1, int color2, List<String> owned) {

		public static Snapshot empty() {
			return new Snapshot(false, "", "", false, 0xFFD700, 0x8A2BE2, List.of());
		}

		/** 兼容旧调用点：当前前缀。 */
		public String current() {
			return prefix;
		}
	}

	private static final Codec<String> TITLE = Codecs.string(128);

	/** 最多保留的称号条数（超出的读完即丢，不影响后续字段）。 */
	private static final int MAX_OWNED = 256;

	private TitleProtocol() {
	}

	public static void write(PacketByteBuf buf, Snapshot s) {
		buf.writeBoolean(s.enabled());
		buf.writeString(s.prefix() == null ? "" : s.prefix(), 128);
		buf.writeString(s.suffix() == null ? "" : s.suffix(), 64);
		buf.writeBoolean(s.gradient());
		buf.writeInt(s.color1() & 0xFFFFFF);
		buf.writeInt(s.color2() & 0xFFFFFF);
		Codecs.writeList(buf, s.owned(), TITLE);
	}

	public static Snapshot read(PacketByteBuf buf) {
		boolean en = buf.readBoolean();
		String prefix = buf.readString(128);
		String suffix = buf.readString(64);
		boolean gradient = buf.readBoolean();
		int c1 = buf.readInt() & 0xFFFFFF;
		int c2 = buf.readInt() & 0xFFFFFF;
		return new Snapshot(en, prefix, suffix, gradient, c1, c2,
				Codecs.readList(buf, MAX_OWNED, TITLE));
	}
}
