package com.akira.redtale.economy.net;

import net.minecraft.network.PacketByteBuf;

import java.util.List;

/**
 * 委托板快照协议（T12）。
 *
 * <p>只发展示数据：id、发布者、物品、数量、赏金、状态、接单人、剩余分钟，
 * 外加两个<b>由服务端判定</b>的归属标志（我发布的 / 我接的），客户端不自己比 uuid
 * —— 免得离线 uuid 与登录 uuid 不一致时按钮全错。
 *
 * <p>接单 / 交付 / 撤销仍然发 {@code /board accept|deliver|cancel <id>}，界面不参与结算。
 */
public final class BoardProtocol {

	/** 状态顺序必须与 {@code BoardStore.Status} 一致。 */
	public static final String[] STATUS = {"OPEN", "ACCEPTED", "DONE", "CANCELLED"};

	public record Entry(String id, String posterName, String itemId, int count, long bounty,
			int status, String accepterName, int remainMin, boolean minePoster, boolean mineAccepter) {

		public String statusName() {
			return status >= 0 && status < STATUS.length ? STATUS[status] : "OPEN";
		}

		public boolean open() {
			return "OPEN".equals(statusName());
		}

		public boolean accepted() {
			return "ACCEPTED".equals(statusName());
		}
	}

	public record Snapshot(List<Entry> entries) {
		public static Snapshot empty() {
			return new Snapshot(List.of());
		}
	}

	private static final Codec<Entry> ENTRY = Codec.of("board/entry",
			(buf, e) -> {
				buf.writeString(e.id() == null ? "" : e.id(), 16);
				buf.writeString(e.posterName() == null ? "" : e.posterName(), 32);
				buf.writeString(e.itemId() == null ? "" : e.itemId(), 96);
				buf.writeVarInt(Math.max(0, e.count()));
				buf.writeLong(Math.max(0, e.bounty()));
				buf.writeVarInt(Math.max(0, e.status()));
				buf.writeString(e.accepterName() == null ? "" : e.accepterName(), 32);
				buf.writeVarInt(Math.max(0, e.remainMin()));
				buf.writeBoolean(e.minePoster());
				buf.writeBoolean(e.mineAccepter());
			},
			buf -> new Entry(buf.readString(16), buf.readString(32), buf.readString(96),
					buf.readVarInt(), buf.readLong(), buf.readVarInt(), buf.readString(32),
					buf.readVarInt(), buf.readBoolean(), buf.readBoolean()));

	/** 面板最多保留的条目数（读完即丢）。 */
	private static final int MAX_ENTRIES = 64;

	private BoardProtocol() {
	}

	public static void write(PacketByteBuf buf, Snapshot s) {
		Codecs.writeList(buf, s.entries(), ENTRY);
	}

	public static Snapshot read(PacketByteBuf buf) {
		return new Snapshot(Codecs.readList(buf, MAX_ENTRIES, ENTRY));
	}
}
