package com.akira.redtale.economy.net;

import net.minecraft.network.PacketByteBuf;

import java.util.List;

/**
 * 签到面板协议（U3）。
 *
 * <p>服务端把「循环内每天的状态 + 那天能拿多少」一次发下来，客户端只画格子。
 * 签到/补签仍然发 {@code /checkin} 与 {@code /checkin make-up} 命令，
 * 补签扣券、每月次数上限都在服务端校验。
 *
 * <p>T33：两个列表走 {@link Codecs}；里程碑写在天数格之前（顺序不能换）。
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

	private static final Codec<Integer> INT = Codecs.VAR_INT;

	private static final Codec<Point> POINT = Codec.of("checkin/point",
			(buf, p) -> {
				buf.writeVarInt(p.day());
				buf.writeLong(p.money());
				buf.writeBoolean(p.done());
				buf.writeBoolean(p.current());
			},
			buf -> new Point(buf.readVarInt(), buf.readLong(), buf.readBoolean(), buf.readBoolean()));

	/** 里程碑条数 / 天数格条数上限（读完即丢）。 */
	private static final int MAX_MILESTONES = 32;
	private static final int MAX_POINTS = 64;

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
		Codecs.writeList(buf, s.milestones(), INT);
		Codecs.writeList(buf, s.points(), POINT);
	}

	public static Snapshot read(PacketByteBuf buf) {
		String lastDate = buf.readString(32);
		int streak = buf.readVarInt();
		int loopDays = buf.readVarInt();
		int loopIndex = buf.readVarInt();
		int totalDays = buf.readVarInt();
		int makeupLeft = buf.readVarInt();
		long makeupCost = buf.readLong();
		List<Integer> miles = Codecs.readList(buf, MAX_MILESTONES, INT);
		List<Point> points = Codecs.readList(buf, MAX_POINTS, POINT);
		return new Snapshot(lastDate, streak, loopDays, loopIndex, totalDays, makeupLeft, makeupCost,
				points, miles);
	}
}
