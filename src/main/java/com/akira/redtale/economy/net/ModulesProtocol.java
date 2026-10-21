package com.akira.redtale.economy.net;

import net.minecraft.network.PacketByteBuf;

import java.util.List;

/**
 * T51 模块总开关协议。
 *
 * <p>三个包：
 * <ul>
 *   <li>{@code C2S_MODULES_REQ}：拉一次全量状态（无参数，服务端按权限决定给不给明细）</li>
 *   <li>{@code C2S_MODULE_SET}：切换单个模块（id + 目标状态）</li>
 *   <li>{@code S2C_MODULES}：全量状态（id / 开 / 是否可关 / 关闭原因）</li>
 * </ul>
 *
 * <p>★权限在服务端判★：非管理员请求拿到的是 {@code admin=false} + 空列表，
 * 切换包直接忽略 —— 客户端那侧的入口隐藏只是「看不见」，不是安全边界。
 *
 * <p>字段上限一律写端截断：{@code writeString} 超限会在<b>服务端下发时</b>抛
 * {@code EncoderException}，表现是界面永远空白（T28 首轮踩过）。
 */
public final class ModulesProtocol {

	/** 一个模块的可见状态。{@code locked=true} 表示不可关（economy）。 */
	public record ModuleView(String id, boolean on, boolean locked, String note) {
	}

	public record Snapshot(boolean admin, List<ModuleView> modules) {

		public static Snapshot empty() {
			return new Snapshot(false, List.of());
		}
	}

	/** 客户端 → 服务端：切换请求。 */
	public record Set(int version, String id, boolean on) {
		public static Set of(String id, boolean on) {
			return new Set(1, id, on);
		}
	}

	private static final int MAX_MODULES = 64;
	private static final int MAX_ID = 32;
	private static final int MAX_NOTE = 64;

	private static final Codec<ModuleView> MODULE = Codec.of("modules/view",
			(buf, m) -> {
				buf.writeString(trunc(m.id(), MAX_ID), MAX_ID);
				buf.writeBoolean(m.on());
				buf.writeBoolean(m.locked());
				buf.writeString(trunc(m.note(), MAX_NOTE), MAX_NOTE);
			},
			buf -> new ModuleView(buf.readString(MAX_ID), buf.readBoolean(),
					buf.readBoolean(), buf.readString(MAX_NOTE)));

	private static String trunc(String s, int max) {
		if (s == null) return "";
		return s.length() <= max ? s : s.substring(0, max);
	}

	private ModulesProtocol() {
	}

	// ------------------------------------------------------------ 请求

	public record Query(int version) {
		public static Query current() {
			return new Query(1);
		}
	}

	public static void writeQuery(PacketByteBuf buf, Query q) {
		buf.writeByte(q.version());
	}

	public static Query readQuery(PacketByteBuf buf) {
		return new Query(buf.readByte());
	}

	public static void writeSet(PacketByteBuf buf, Set s) {
		buf.writeByte(s.version());
		buf.writeString(trunc(s.id(), MAX_ID), MAX_ID);
		buf.writeBoolean(s.on());
	}

	public static Set readSet(PacketByteBuf buf) {
		return new Set(buf.readByte(), buf.readString(MAX_ID), buf.readBoolean());
	}

	// ------------------------------------------------------------ 快照

	public static void write(PacketByteBuf buf, Snapshot s) {
		buf.writeBoolean(s.admin());
		Codecs.writeList(buf, s.modules(), MODULE);
	}

	public static Snapshot read(PacketByteBuf buf) {
		boolean admin = buf.readBoolean();
		return new Snapshot(admin, Codecs.readList(buf, MAX_MODULES, MODULE));
	}
}
