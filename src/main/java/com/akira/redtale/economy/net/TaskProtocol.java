package com.akira.redtale.economy.net;

import net.minecraft.network.PacketByteBuf;

import java.util.ArrayList;
import java.util.List;

/**
 * 每日任务面板协议（U2）。
 *
 * <p>服务端把自己的「今日任务 + 背包实有数量 + 奖励 + 是否已领」一次性发下来，
 * 客户端只负责画。{@code have} 是服务端算的，客户端背包里有多少它说了不算。
 *
 * <p>缴纳/领取仍然走命令（{@code /tasks submit|claim <slot>}），所以数量校验、扣物品、
 * 发奖励只有服务端那一份代码，面板不会因为改了个按钮就绕过校验。
 */
public final class TaskProtocol {

	public record Item(String id, int need, int have) {
	}

	/** {@code label} 是任务卡标题（池 displayName）；旧版本没有这个字段，客户端为空时退回 #slot。 */
	public record Task(int slot, long reward, String mode, boolean claimed, List<Item> items, String label) {
	}

	public record Snapshot(String date, int refreshesLeft, List<Task> tasks) {
		public static Snapshot empty() {
			return new Snapshot("", 0, List.of());
		}
	}

	private TaskProtocol() {
	}

	public static void write(PacketByteBuf buf, Snapshot s) {
		buf.writeString(s.date() == null ? "" : s.date(), 32);
		buf.writeVarInt(Math.max(0, s.refreshesLeft()));
		buf.writeVarInt(s.tasks().size());
		for (Task t : s.tasks()) {
			buf.writeVarInt(t.slot());
			buf.writeLong(t.reward());
			buf.writeString(t.mode() == null ? "submit" : t.mode(), 16);
			buf.writeString(t.label() == null ? "" : t.label(), 64);
			buf.writeBoolean(t.claimed());
			buf.writeVarInt(t.items().size());
			for (Item i : t.items()) {
				buf.writeString(i.id(), 128);
				buf.writeVarInt(Math.max(0, i.need()));
				buf.writeVarInt(Math.max(0, i.have()));
			}
		}
	}

	public static Snapshot read(PacketByteBuf buf) {
		String date = buf.readString(32);
		int refreshes = buf.readVarInt();
		int n = Math.min(buf.readVarInt(), 64);
		List<Task> tasks = new ArrayList<>();
		for (int k = 0; k < n; k++) {
			int slot = buf.readVarInt();
			long reward = buf.readLong();
			String mode = buf.readString(16);
			String label = buf.readString(64);
			boolean claimed = buf.readBoolean();
			int m = Math.min(buf.readVarInt(), 32);
			List<Item> items = new ArrayList<>();
			for (int j = 0; j < m; j++) {
				String id = buf.readString(128);
				int need = buf.readVarInt();
				int have = buf.readVarInt();
				items.add(new Item(id, need, have));
			}
			tasks.add(new Task(slot, reward, mode, claimed, items, label));
		}
		return new Snapshot(date, refreshes, tasks);
	}
}
