package com.akira.redtale.economy.net;

import net.minecraft.item.ItemStack;
import net.minecraft.network.PacketByteBuf;

import java.util.List;

/**
 * 仓储快照：格子数 + 每格物品。
 *
 * <p>T33：物品列表走 {@link Codecs.readList}。老写法 {@code min(n,512)} 会把第 513 格
 * 之后的内容当成 cursor 读，一次超限就整屏错位。
 *
 * <p>注意 {@code loaded} 只读不写（写端不传，读端恒为 true）：它是「本轮快照已加载」的
 * 客户端标志，不是服务端状态。往返测试的样本据此用 true 对齐。
 */
public final class WarehouseProtocol {

	public record Snapshot(int slots, int purchased, List<ItemStack> stacks, ItemStack cursor,
			int nextSlots, long nextPrice, long nextPremium, boolean loaded) {
		public static Snapshot empty() {
			return new Snapshot(0, 0, List.of(), ItemStack.EMPTY, 0, 0, 0, false);
		}
	}

	private static final Codec<ItemStack> STACK = Codec.of("warehouse/stack",
			PacketByteBuf::writeItemStack,
			PacketByteBuf::readItemStack);

	/** 一屏最多保留的格数（读完即丢）。 */
	private static final int MAX_STACKS = 512;

	private WarehouseProtocol() {
	}

	public static void write(PacketByteBuf buf, Snapshot s) {
		buf.writeVarInt(s.slots());
		buf.writeVarInt(s.purchased());
		Codecs.writeList(buf, s.stacks(), STACK);
		buf.writeItemStack(s.cursor() == null ? ItemStack.EMPTY : s.cursor());
		buf.writeVarInt(s.nextSlots());
		buf.writeLong(s.nextPrice());
		buf.writeLong(s.nextPremium());
	}

	public static Snapshot read(PacketByteBuf buf) {
		int slots = buf.readVarInt();
		int purchased = buf.readVarInt();
		List<ItemStack> list = Codecs.readList(buf, MAX_STACKS, STACK);
		return new Snapshot(slots, purchased, list, buf.readItemStack(),
				buf.readVarInt(), buf.readLong(), buf.readLong(), true);
	}
}
