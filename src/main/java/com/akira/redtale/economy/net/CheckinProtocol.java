package com.akira.redtale.economy.net;

import net.minecraft.network.PacketByteBuf;

import java.util.ArrayList;
import java.util.List;

/**
 * 签到面板协议（U3）。
 *
 * <p>服务端把「循环内每天的状态 + 那天能拿多少」一次发下来，客户端只画格子。
 * 签到/补签仍然发 {@code /checkin} 与 {@code /checkin make-up} 命令，
 * 补签扣券、每月次数上限都在服务端校验。
 */
public final class CheckinProtocol {

	/** 循环内的一天。 */
	public record Point(int day, long money, boolean done, boolean current) {
	}

	public record Snapshot(String lastDate, int streak, int loopDays, int loopIndex,
			int totalDays, int makeupLeft, long makeupCost, List<Point> points,
			List<Integer> milestones) {
		public static Snapshot empty() {
			return new Snapshot("", 0, 7, 1, 0, 0, 0, List.of(), List.of(7, 14, 28));
		}
	}

	private CheckinProtocol() {
	}

	public static void write(PacketByteBuf buf, Snapshot s) {
		buf.writeString(s.lastDate() == null ? "" : s.lastDate(), 32);
		buf.writeVarInt(Math.max(0, s.streak()));
		buf.writeVarInt(Math.max(1, s.loopDays()));
		buf.writeVarInt(Math.max(1, s.loopIndex()));
		buf.writeVarInt(Math.max(0, s.totalDays()));
		buf.writeVarInt(Math.max(0, s.makeupLeft()));
		buf.writeLong(Math.max(0, s.makeupCost()));
		buf.writeVarInt(s.milestones() == null ? 0 : s.milestones().size());
		for (int m : (s.milestones() == null ? List.<Integer>of() : s.milestones())) {
			buf.writeVarInt(m);
		}
		buf.writeVarInt(s.points().size());
		for (Point p : s.points()) {
			buf.writeVarInt(p.day());
			buf.writeLong(p.money());
			buf.writeBoolean(p.done());
			buf.writeBoolean(p.current());
		}
	}

	public static Snapshot read(PacketByteBuf buf) {
		String lastDate = buf.readString(32);
		int streak = buf.readVarInt();
		int loopDays = buf.readVarInt();
		int loopIndex = buf.readVarInt();
		int totalDays = buf.readVarInt();
		int makeupLeft = buf.readVarInt();
		long makeupCost = buf.readLong();
		int mc = Math.min(buf.readVarInt(), 32);
		List<Integer> miles = new ArrayList<>();
		for (int i = 0; i < mc; i++) miles.add(buf.readVarInt());
		int n = Math.min(buf.readVarInt(), 64);
		List<Point> points = new ArrayList<>();
		for (int i = 0; i < n; i++) {
			points.add(new Point(buf.readVarInt(), buf.readLong(), buf.readBoolean(), buf.readBoolean()));
		}
		return new Snapshot(lastDate, streak, loopDays, loopIndex, totalDays, makeupLeft, makeupCost,
				points, miles);
	}
}
