package com.akira.redtale.economy.net;

import net.minecraft.network.PacketByteBuf;

import java.util.List;

/**
 * T16 流水查看器协议。
 *
 * <p>两个包：{@code C2S_LEDGER_REQ}（请求一页）与 {@code S2C_LEDGER}（快照）。
 * 请求只带「筛选条件的下标」，实际计算全在服务端 —— 客户端拿不到别人的流水文件路径，
 * 也就没有「改个包 body 就能翻别人账」的面。
 *
 * <p>非管理员请求里 {@code all=true} 会被服务端<b>直接降级</b>成只看自己（不报错）：
 * 界面上那个「全部玩家」开关本来就只有管理员能看到，真被伪造也无害。
 */
public final class LedgerProtocol {

	/** 单行。{@code name} 由服务端解析（离线 uuid 也能回名字，解析不到就是 uuid 前 8 位）。 */
	public record Row(String ts, String name, String type, long delta, long after, String ref) {
	}

	/** 请求。下标口径见 {@code storage.LedgerQuery}；{@code all} 只在管理员下生效。 */
	public record Query(int daysIdx, int dir, int typeIdx, boolean all) {
		public static Query defaults() {
			return new Query(0, 0, 0, false);
		}
	}

	/** 快照。{@code total} 是<b>截断前</b>的匹配总数，界面用它显示「共 N 笔」。 */
	public record Snapshot(int daysIdx, int dir, int typeIdx, boolean all, boolean admin,
			int total, List<Row> rows) {

		public static Snapshot empty() {
			return new Snapshot(0, 0, 0, false, false, 0, List.of());
		}
	}

	private static final Codec<Row> ROW = Codec.of("ledger/row",
			(buf, r) -> {
				buf.writeString(r.ts() == null ? "" : r.ts(), 24);
				buf.writeString(r.name() == null ? "" : r.name(), 40);
				buf.writeString(r.type() == null ? "" : r.type(), 48);
				buf.writeLong(r.delta());
				buf.writeLong(r.after());
				buf.writeString(r.ref() == null ? "" : r.ref(), 96);
			},
			buf -> new Row(buf.readString(24), buf.readString(40), buf.readString(48),
					buf.readLong(), buf.readLong(), buf.readString(96)));

	/** 一页上限：与 {@code LedgerQuery.MAX_ROWS} 一致；协议侧再兜一层。 */
	private static final int MAX_ROWS = 64;

	private LedgerProtocol() {
	}

	// ------------------------------------------------------------ 请求

	public static void writeQuery(PacketByteBuf buf, Query q) {
		buf.writeVarInt(Math.max(0, q.daysIdx()));
		buf.writeVarInt(Math.max(0, q.dir()));
		buf.writeVarInt(Math.max(0, q.typeIdx()));
		buf.writeBoolean(q.all());
	}

	public static Query readQuery(PacketByteBuf buf) {
		return new Query(buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readBoolean());
	}

	// ------------------------------------------------------------ 快照

	public static void write(PacketByteBuf buf, Snapshot s) {
		buf.writeVarInt(Math.max(0, s.daysIdx()));
		buf.writeVarInt(Math.max(0, s.dir()));
		buf.writeVarInt(Math.max(0, s.typeIdx()));
		buf.writeBoolean(s.all());
		buf.writeBoolean(s.admin());
		buf.writeVarInt(Math.max(0, s.total()));
		Codecs.writeList(buf, s.rows(), ROW);
	}

	public static Snapshot read(PacketByteBuf buf) {
		int daysIdx = buf.readVarInt();
		int dir = buf.readVarInt();
		int typeIdx = buf.readVarInt();
		boolean all = buf.readBoolean();
		boolean admin = buf.readBoolean();
		int total = buf.readVarInt();
		return new Snapshot(daysIdx, dir, typeIdx, all, admin, total, Codecs.readList(buf, MAX_ROWS, ROW));
	}
}
