package com.akira.redtale.economy.net;

import net.minecraft.item.ItemStack;
import net.minecraft.network.PacketByteBuf;

import java.util.ArrayList;
import java.util.List;

/** 仓储快照：格子数 + 每格物品。 */
public final class WarehouseProtocol {

	public record Snapshot(int slots, int purchased, List<ItemStack> stacks, ItemStack cursor,
			int nextSlots, long nextPrice, long nextPremium, boolean loaded) {
		public static Snapshot empty() {
			return new Snapshot(0, 0, List.of(), ItemStack.EMPTY, 0, 0, 0, false);
		}
	}

	private WarehouseProtocol() {
	}

	public static void write(PacketByteBuf buf, Snapshot s) {
		buf.writeVarInt(s.slots());
		buf.writeVarInt(s.purchased());
		buf.writeVarInt(s.stacks().size());
		for (ItemStack st : s.stacks()) {
			buf.writeItemStack(st);
		}
		buf.writeItemStack(s.cursor() == null ? ItemStack.EMPTY : s.cursor());
		buf.writeVarInt(s.nextSlots());
		buf.writeLong(s.nextPrice());
		buf.writeLong(s.nextPremium());
	}

	public static Snapshot read(PacketByteBuf buf) {
		int slots = buf.readVarInt();
		int purchased = buf.readVarInt();
		int n = Math.min(buf.readVarInt(), 512);
		List<ItemStack> list = new ArrayList<>(n);
		for (int i = 0; i < n; i++) {
			list.add(buf.readItemStack());
		}
		return new Snapshot(slots, purchased, list, buf.readItemStack(),
				buf.readVarInt(), buf.readLong(), buf.readLong(), true);
	}
}
