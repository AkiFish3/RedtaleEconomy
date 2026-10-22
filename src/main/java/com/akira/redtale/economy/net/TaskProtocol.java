package com.akira.redtale.economy.net;

import net.minecraft.network.PacketByteBuf;

import java.util.List;

/**
 * 每日任务面板协议（U2）。
 *
 * <p>服务端把自己的「今日任务 + 背包实有数量 + 奖励 + 是否已领」一次性发下来，
 * 客户端只负责画。{@code have} 是服务端算的，客户端背包里有多少它说了不算。
 *
 * <p>缴纳/领取仍然走命令（{@code /tasks submit|claim <slot>}），所以数量校验、扣物品、
 * 发奖励只有服务端那一份代码，面板不会因为改了个按钮就绕过校验。
 *
 * <p>T33：两层列表都走 {@link Codecs}（读完再截断），任务数超限不会吃掉后续任务卡。
 */
public final class TaskProtocol {

	public record Item(String id, int need, int have) {
	}

	/**
	 * {@code label} 是任务卡标题（池 displayName）；旧版本没有这个字段，客户端为空时退回 #slot。
	 *
	 * <p>T06：{@code period} 是 {@code daily}/{@code weekly}，界面按它分栏；{@code slot} 已按周期分段
	 * （周任务从 100 起），所以「每日 tab 里点领取」只会把当页那条任务的 slot 发回去。
	 */
	public record Task(int slot, long reward, String mode, boolean claimed, List<Item> items, String label,
			String period) {
	}

	/** {@code weekKey} 形如 {@code W2026-10-19}（本周起始日），仅用于页头展示。 */
	public record Snapshot(String date, int refreshesLeft, List<Task> tasks, String weekKey) {
		public static Snapshot empty() {
			return new Snapshot("", 0, List.of(), "");
		}
	}

	/** 一屏最多保留的任务卡数 / 每张卡最多保留的物品种类。 */
	private static final int MAX_TASKS = 64;
	private static final int MAX_ITEMS = 32;

	private static final Codec<Item> ITEM = Codec.of("task/item",
			(buf, i) -> {
				buf.writeString(i.id() == null ? "" : i.id(), 128);
				buf.writeVarInt(Math.max(0, i.need()));
				buf.writeVarInt(Math.max(0, i.have()));
			},
			buf -> new Item(buf.readString(128), buf.readVarInt(), buf.readVarInt()));

	private static final Codec<Task> TASK = Codec.of("task/card",
			(buf, t) -> {
				buf.writeVarInt(t.slot());
				buf.writeLong(t.reward());
				buf.writeString(t.mode() == null ? "submit" : t.mode(), 16);
				buf.writeString(t.label() == null ? "" : t.label(), 64);
				buf.writeBoolean(t.claimed());
				buf.writeString(t.period() == null ? "daily" : t.period(), 16);
				Codecs.writeList(buf, t.items(), ITEM);
			},
			buf -> {
				int slot = buf.readVarInt();
				long reward = buf.readLong();
				String mode = buf.readString(16);
				String label = buf.readString(64);
				boolean claimed = buf.readBoolean();
				String period = buf.readString(16);
				return new Task(slot, reward, mode, claimed, Codecs.readList(buf, MAX_ITEMS, ITEM), label,
						period);
			});

	private TaskProtocol() {
	}

	public static void write(PacketByteBuf buf, Snapshot s) {
		buf.writeString(s.date() == null ? "" : s.date(), 32);
		buf.writeVarInt(Math.max(0, s.refreshesLeft()));
		buf.writeString(s.weekKey() == null ? "" : s.weekKey(), 32);
		Codecs.writeList(buf, s.tasks(), TASK);
	}

	public static Snapshot read(PacketByteBuf buf) {
		String date = buf.readString(32);
		int refreshes = buf.readVarInt();
		String weekKey = buf.readString(32);
		return new Snapshot(date, refreshes, Codecs.readList(buf, MAX_TASKS, TASK), weekKey);
	}
}
