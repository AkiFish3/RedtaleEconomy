package com.akira.redtale.economy.net;

import net.minecraft.network.PacketByteBuf;

import java.util.List;

/**
 * T62：邮件页协议（左列表 + 右详情）。
 *
 * <p>列表里的每一条都是「系统待发放给玩家」的东西：
 * <ul>
 *   <li>{@link #KIND_MONEY}：待领取货币（合计一条，{@code money} 有值）</li>
 *   <li>{@link #KIND_ITEM}：没投进 Contact 信箱的物品（信箱满/未装模组时进队列），
 *       {@code index} 是队列下标</li>
 * </ul>
 *
 * <p>★物品不走 ItemStack 序列化★：客户端只需要「画图标 + 显示名字/数量」，
 * 传 id + count + 显示名就够（传整栈 NBT 会让包体随附魔/耐久膨胀，且要两端 NBT 版本一致）。
 *
 * <p>读端先读条数再按条数循环（{@link Codecs#readList} 的顺序），字段顺序与写端逐一对齐。
 */
public final class MailProtocol {

	/** 待领取货币。 */
	public static final int KIND_MONEY = 0;
	/** 待领取物品。 */
	public static final int KIND_ITEM = 1;

	/** 附件里一件物品（只给展示用）。 */
	public record Part(String itemId, int count, String name) {
	}

	/**
	 * 一条待领取记录。
	 *
	 * @param index 物品在队列里的下标（货币条目固定 -1）
	 * @param kind  {@link #KIND_MONEY} / {@link #KIND_ITEM}
	 */
	public record Entry(int index, int kind, String title, String sender, long money,
			List<Part> parts) {
	}

	/**
	 * 快照。
	 *
	 * @param provider      "contact" = 装了 Contact 且 API 匹配；"queue" = 只能用待领取队列
	 * @param mailboxFull   Contact 信箱是否已满（未装视作 true）
	 * @param entries       列表（货币条目排最前）
	 */
	public record Snapshot(String provider, boolean mailboxFull, long pendingMoney, int itemCount,
			List<Entry> entries) {
		public static Snapshot empty() {
			return new Snapshot("queue", true, 0, 0, List.of());
		}
	}

	/** 单条最多几件附件（读到即丢，防止坏包把客户端拖死）。 */
	private static final int MAX_PARTS = 16;
	/** 列表条数上限。 */
	private static final int MAX_ENTRIES = 64;

	private static final Codec<Part> PART = Codec.of("mail/part",
			(buf, p) -> {
				buf.writeString(p.itemId() == null ? "" : p.itemId(), 128);
				buf.writeVarInt(Math.max(0, p.count()));
				buf.writeString(p.name() == null ? "" : p.name(), 256);
			},
			buf -> new Part(buf.readString(128), buf.readVarInt(), buf.readString(256)));

	private static final Codec<Entry> ENTRY = Codec.of("mail/entry",
			(buf, e) -> {
				buf.writeVarInt(e.index());
				buf.writeVarInt(Math.max(0, e.kind()));
				buf.writeString(e.title() == null ? "" : e.title(), 256);
				buf.writeString(e.sender() == null ? "" : e.sender(), 64);
				buf.writeLong(Math.max(0, e.money()));
				Codecs.writeList(buf, e.parts(), PART);
			},
			buf -> new Entry(buf.readVarInt(), buf.readVarInt(), buf.readString(256),
					buf.readString(64), buf.readLong(), Codecs.readList(buf, MAX_PARTS, PART)));

	private MailProtocol() {
	}

	public static void write(PacketByteBuf buf, Snapshot s) {
		buf.writeString(s.provider() == null ? "queue" : s.provider(), 16);
		buf.writeBoolean(s.mailboxFull());
		buf.writeLong(Math.max(0, s.pendingMoney()));
		buf.writeVarInt(Math.max(0, s.itemCount()));
		Codecs.writeList(buf, s.entries(), ENTRY);
	}

	public static Snapshot read(PacketByteBuf buf) {
		// 注意顺序：provider → mailboxFull → pendingMoney → itemCount → entries
		String provider = buf.readString(16);
		boolean full = buf.readBoolean();
		long money = buf.readLong();
		int count = buf.readVarInt();
		List<Entry> entries = Codecs.readList(buf, MAX_ENTRIES, ENTRY);
		return new Snapshot(provider, full, money, count, entries);
	}
}
