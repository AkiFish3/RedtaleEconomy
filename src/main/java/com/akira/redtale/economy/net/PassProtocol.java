package com.akira.redtale.economy.net;

import net.minecraft.network.PacketByteBuf;

import java.util.ArrayList;
import java.util.List;

/**
 * 战令快照协议（R20/U4）。
 *
 * <p>只传展示所需数据；领取判定仍在服务端（客户端只发命令）。
 */
public final class PassProtocol {

	/** 一个奖励项。 */
	public record ItemReward(String id, int count) {
	}

	/** 一档奖励。 */
	public record Reward(int level, long money, List<ItemReward> items) {
	}

	/** 整个赛季快照。 */
	public record Snapshot(String season, int level, long xp, long xpNext, boolean premium,
			List<Reward> free, List<Reward> prem, List<Integer> claimedFree, List<Integer> claimedPrem) {
		public static Snapshot empty() {
			return new Snapshot("", 1, 0, 1000, false, List.of(), List.of(), List.of(), List.of());
		}
	}

	private PassProtocol() {
	}

	public static void write(PacketByteBuf buf, Snapshot s) {
		buf.writeString(s.season() == null ? "" : s.season(), 64);
		buf.writeVarInt(s.level());
		buf.writeLong(s.xp());
		buf.writeLong(s.xpNext());
		buf.writeBoolean(s.premium());
		writeTrack(buf, s.free());
		writeTrack(buf, s.prem());
		writeInts(buf, s.claimedFree());
		writeInts(buf, s.claimedPrem());
	}

	public static Snapshot read(PacketByteBuf buf) {
		String season = buf.readString(64);
		int level = buf.readVarInt();
		long xp = buf.readLong();
		long xpNext = buf.readLong();
		boolean premium = buf.readBoolean();
		List<Reward> free = readTrack(buf);
		List<Reward> prem = readTrack(buf);
		List<Integer> cf = readInts(buf);
		List<Integer> cp = readInts(buf);
		return new Snapshot(season, level, xp, xpNext, premium, free, prem, cf, cp);
	}

	private static void writeTrack(PacketByteBuf buf, List<Reward> list) {
		buf.writeVarInt(list.size());
		for (Reward r : list) {
			buf.writeVarInt(r.level());
			buf.writeLong(r.money());
			buf.writeVarInt(r.items().size());
			for (ItemReward it : r.items()) {
				buf.writeString(it.id(), 128);
				buf.writeVarInt(it.count());
			}
		}
	}

	private static List<Reward> readTrack(PacketByteBuf buf) {
		int n = Math.min(buf.readVarInt(), 512);
		List<Reward> out = new ArrayList<>(n);
		for (int i = 0; i < n; i++) {
			int level = buf.readVarInt();
			long money = buf.readLong();
			int ic = Math.min(buf.readVarInt(), 16);
			List<ItemReward> items = new ArrayList<>(ic);
			for (int j = 0; j < ic; j++) {
				items.add(new ItemReward(buf.readString(128), buf.readVarInt()));
			}
			out.add(new Reward(level, money, items));
		}
		return out;
	}

	private static void writeInts(PacketByteBuf buf, List<Integer> list) {
		buf.writeVarInt(list.size());
		for (int v : list) buf.writeVarInt(v);
	}

	private static List<Integer> readInts(PacketByteBuf buf) {
		int n = Math.min(buf.readVarInt(), 512);
		List<Integer> out = new ArrayList<>(n);
		for (int i = 0; i < n; i++) out.add(buf.readVarInt());
		return out;
	}
}
