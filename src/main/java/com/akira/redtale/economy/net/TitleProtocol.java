package com.akira.redtale.economy.net;

import net.minecraft.network.PacketByteBuf;

import java.util.List;

/**
 * 称号快照：拥有列表 + 当前生效的若干槽 + 配色（T23）+ 槽位数 / 强制称号（T60/T66）。
 *
 * <p>★两端必须同步★：{@link #write} 与 {@link #read} 的令牌顺序是唯一真相，
 * {@code _proto_audit.py} 会逐对比较写读序列。
 */
public final class TitleProtocol {

	/**
	 * @param enabled     称号模块是否可用
	 * @param prefix      第 1 槽（= 旧字段 current 的语义，字段名改清楚）
	 * @param suffix      当前后缀（可空）
	 * @param gradient    是否渐变
	 * @param color1      起始色 0xRRGGBB
	 * @param color2      渐变终止色 0xRRGGBB
	 * @param owned       已拥有的称号
	 * @param slots       该玩家可用的槽位数（T60，1..3）
	 * @param equipped    已装备的称号（有序，T60；可能比 {@code slots} 长，客户端自行截断）
	 * @param forcedTexts 生效中的强制称号文本（T66；不占槽位、只读展示）
	 */
	public record Snapshot(boolean enabled, String prefix, String suffix, boolean gradient,
			int color1, int color2, List<String> owned, int slots, List<String> equipped,
			List<String> forcedTexts) {

		/** 兼容 8 字段构造（调用方一行都不用改）。 */
		public Snapshot(boolean enabled, String prefix, String suffix, boolean gradient,
				int color1, int color2, List<String> owned) {
			this(enabled, prefix, suffix, gradient, color1, color2, owned, 1,
					prefix == null || prefix.isEmpty() ? List.of() : List.of(prefix), List.of());
		}

		public static Snapshot empty() {
			return new Snapshot(false, "", "", false, 0xFFD700, 0x8A2BE2, List.of(), 1,
					List.of(), List.of());
		}

		/** 兼容旧调用点：当前前缀。 */
		public String current() {
			return prefix;
		}
	}

	private static final Codec<String> TITLE = Codecs.string(128);

	/** 最多保留的称号条数（超出的读完即丢，不影响后续字段）。 */
	private static final int MAX_OWNED = 256;

	/** 槽位列表上限（TitleSlots.MAX_SLOTS 的协议侧栅栏）。 */
	private static final int MAX_EQUIPPED = 8;

	/** 强制称号上限。 */
	private static final int MAX_FORCED = 16;

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
		// T60 / T66：追加在末尾（读端同序），不要插在中间
		buf.writeInt(s.slots());
		Codecs.writeList(buf, s.equipped(), TITLE);
		Codecs.writeList(buf, s.forcedTexts(), TITLE);
	}

	public static Snapshot read(PacketByteBuf buf) {
		boolean en = buf.readBoolean();
		String prefix = buf.readString(128);
		String suffix = buf.readString(64);
		boolean gradient = buf.readBoolean();
		int c1 = buf.readInt() & 0xFFFFFF;
		int c2 = buf.readInt() & 0xFFFFFF;
		List<String> owned = Codecs.readList(buf, MAX_OWNED, TITLE);
		int slots = buf.readInt();
		List<String> equipped = Codecs.readList(buf, MAX_EQUIPPED, TITLE);
		List<String> forced = Codecs.readList(buf, MAX_FORCED, TITLE);
		return new Snapshot(en, prefix, suffix, gradient, c1, c2, owned, slots, equipped, forced);
	}
}
