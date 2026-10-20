package com.akira.redtale.economy.net;

import net.minecraft.network.PacketByteBuf;

import java.util.ArrayList;
import java.util.List;

/**
 * 排行榜快照（U9b）：前 10 + 自己那一行（spec §43.4「显示前 10 + 自己那行」）。
 *
 * <p>字段顺序固定，改字段必须同时改 encode/decode 与所有构造点（客户端/服务端同一个 jar）。
 */
public final class RankProtocol {

	/** board：0=财富榜（目前唯一实现，其余榜预留）。 */
	public record Row(int rank, String name, long balance) {
	}

	/**
	 * @param activeOnly 本次快照是否只含「活跃玩家」（U9：开关由客户端随请求发，
	 *                   服务端在两份缓存里挑一份，回头带在快照里让界面显示真实状态）
	 */
	public record Snapshot(int board, List<Row> top, Row self, int total, long computedAt,
			boolean activeOnly) {
		public static Snapshot empty() {
			return new Snapshot(0, List.of(), null, 0, 0L, true);
		}
	}

	/** 客户端请求：{@code activeOnly} = 界面上的「只看活跃」开关。 */
	public record Request(boolean activeOnly) {
	}

	private RankProtocol() {
	}

	public static void write(PacketByteBuf buf, Snapshot s) {
		buf.writeVarInt(s.board());
		buf.writeVarInt(s.top().size());
		for (Row r : s.top()) {
			buf.writeVarInt(r.rank());
			buf.writeString(r.name(), 32);
			buf.writeLong(r.balance());
		}
		buf.writeBoolean(s.self() != null);
		if (s.self() != null) {
			buf.writeVarInt(s.self().rank());
			buf.writeString(s.self().name(), 32);
			buf.writeLong(s.self().balance());
		}
		buf.writeVarInt(s.total());
		buf.writeLong(s.computedAt());
		buf.writeBoolean(s.activeOnly());
	}

	public static Snapshot read(PacketByteBuf buf) {
		int board = buf.readVarInt();
		int n = Math.min(buf.readVarInt(), 50);      // 防御：最多读 50 行
		List<Row> top = new ArrayList<>(n);
		for (int i = 0; i < n; i++) {
			top.add(new Row(buf.readVarInt(), buf.readString(32), buf.readLong()));
		}
		Row self = null;
		if (buf.readBoolean()) {
			self = new Row(buf.readVarInt(), buf.readString(32), buf.readLong());
		}
		return new Snapshot(board, top, self, buf.readVarInt(), buf.readLong(), buf.readBoolean());
	}

	// ---------------------------------------------------------------- 请求

	public static void writeRequest(PacketByteBuf buf, Request r) {
		buf.writeBoolean(r.activeOnly());
	}

	public static Request readRequest(PacketByteBuf buf) {
		return new Request(buf.readBoolean());
	}
}
