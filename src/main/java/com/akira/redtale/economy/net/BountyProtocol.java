package com.akira.redtale.economy.net;

import net.minecraft.network.PacketByteBuf;

import java.util.ArrayList;
import java.util.List;

/**
 * 系统悬赏面板协议（U7：列表类信息进 GUI）。
 *
 * <p>只发展示数据：序号、目标 id、进度、需求、金额、是否已领。
 * 领取仍然发 {@code /bounty claim <n>}，由服务端算奖励并校验——界面不参与任何结算。
 */
public final class BountyProtocol {

	public record Entry(int index, String id, int progress, int need, long reward, boolean claimed) {
		public boolean done() {
			return progress >= need;
		}
	}

	public record Snapshot(List<Entry> entries) {
		public static Snapshot empty() {
			return new Snapshot(List.of());
		}
	}

	private BountyProtocol() {
	}

	public static void write(PacketByteBuf buf, Snapshot s) {
		buf.writeVarInt(s.entries().size());
		for (Entry e : s.entries()) {
			buf.writeVarInt(e.index());
			buf.writeString(e.id() == null ? "" : e.id(), 128);
			buf.writeVarInt(Math.max(0, e.progress()));
			buf.writeVarInt(Math.max(1, e.need()));
			buf.writeLong(Math.max(0, e.reward()));
			buf.writeBoolean(e.claimed());
		}
	}

	public static Snapshot read(PacketByteBuf buf) {
		int n = buf.readVarInt();
		List<Entry> out = new ArrayList<>(Math.min(n, 64));
		for (int i = 0; i < n; i++) {
			int index = buf.readVarInt();
			String id = buf.readString(128);
			int progress = buf.readVarInt();
			int need = buf.readVarInt();
			long reward = buf.readLong();
			boolean claimed = buf.readBoolean();
			if (i < 64) out.add(new Entry(index, id, progress, need, reward, claimed));
		}
		return new Snapshot(out);
	}
}
