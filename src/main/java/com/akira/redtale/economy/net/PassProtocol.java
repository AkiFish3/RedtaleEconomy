package com.akira.redtale.economy.net;

import net.minecraft.network.PacketByteBuf;

import java.util.List;

/**
 * 战令快照协议（R20/U4）。
 *
 * <p>只传展示所需数据；领取判定仍在服务端（客户端只发命令）。
 *
 * <p>T33：三条列表（两条奖励轨 + 两条已领档位）走 {@link Codecs}，
 * 截断只影响显示条数，不会让「已领」集合从奖励轨中间开始读。
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

	/** 每条轨最多保留的档位数 / 每档最多物品数 / 已领档位条数。 */
	private static final int MAX_REWARDS = 512;
	private static final int MAX_ITEMS = 16;
	private static final int MAX_CLAIMED = 512;

	private static final Codec<Integer> INT = Codecs.VAR_INT;

	private static final Codec<ItemReward> ITEM = Codec.of("pass/item",
			(buf, it) -> {
				buf.writeString(it.id() == null ? "" : it.id(), 128);
				buf.writeVarInt(it.count());
			},
			buf -> new ItemReward(buf.readString(128), buf.readVarInt()));

	private static final Codec<Reward> REWARD = Codec.of("pass/reward",
			(buf, r) -> {
				buf.writeVarInt(r.level());
				buf.writeLong(r.money());
				Codecs.writeList(buf, r.items(), ITEM);
			},
			buf -> new Reward(buf.readVarInt(), buf.readLong(),
					Codecs.readList(buf, MAX_ITEMS, ITEM)));

	private PassProtocol() {
	}

	public static void write(PacketByteBuf buf, Snapshot s) {
		buf.writeString(s.season() == null ? "" : s.season(), 64);
		buf.writeVarInt(s.level());
		buf.writeLong(s.xp());
		buf.writeLong(s.xpNext());
		buf.writeBoolean(s.premium());
		Codecs.writeList(buf, s.free(), REWARD);
		Codecs.writeList(buf, s.prem(), REWARD);
		Codecs.writeList(buf, s.claimedFree(), INT);
		Codecs.writeList(buf, s.claimedPrem(), INT);
	}

	public static Snapshot read(PacketByteBuf buf) {
		String season = buf.readString(64);
		int level = buf.readVarInt();
		long xp = buf.readLong();
		long xpNext = buf.readLong();
		boolean premium = buf.readBoolean();
		List<Reward> free = Codecs.readList(buf, MAX_REWARDS, REWARD);
		List<Reward> prem = Codecs.readList(buf, MAX_REWARDS, REWARD);
		List<Integer> cf = Codecs.readList(buf, MAX_CLAIMED, INT);
		List<Integer> cp = Codecs.readList(buf, MAX_CLAIMED, INT);
		return new Snapshot(season, level, xp, xpNext, premium, free, prem, cf, cp);
	}
}
