package com.akira.redtale.economy.shop;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;

import java.util.List;

/**
 * 容器内容解析（R26）。
 *
 * <p>默认策略 {@code ignore} 时<b>根本不调用这个类</b>，零开销、零兼容风险；
 * 只有服主把 {@code tasks.containerPolicy} 改成 {@code shulker}/{@code all} 才解析。
 *
 * <p>只读标准结构 {@code BlockEntityTag.Items[{Slot,id,Count}]}：
 * 潜影盒、以及大多数按原版格式存的背包类物品都能认；不认的（自研 NBT 结构）
 * 会表现为「读不到内容」，不会误扣、也不会崩。
 */
public final class ContainerScan {

	/** 与策略字符串对应；解析集中在这里，避免各处散落字符串比较。 */
	public enum Policy {
		IGNORE, SHULKER, ALL;

		public static Policy of(String raw) {
			if (raw == null) return IGNORE;
			return switch (raw.toLowerCase(java.util.Locale.ROOT)) {
				case "shulker" -> SHULKER;
				case "all" -> ALL;
				default -> IGNORE;
			};
		}
	}

	private ContainerScan() {
	}

	/** 取容器里的物品列表；不是容器/没有内容返回空列表。 */
	public static NbtList items(NbtCompound stackNbt, Policy policy) {
		if (stackNbt == null || policy == Policy.IGNORE) return new NbtList();
		NbtCompound blockEntity = stackNbt.getCompound("BlockEntityTag");
		if (blockEntity == null || !blockEntity.contains("Items", NbtElement.LIST_TYPE)) return new NbtList();
		return blockEntity.getList("Items", NbtElement.COMPOUND_TYPE);
	}

	/** 统计容器里某个物品的总数。 */
	public static long count(NbtCompound stackNbt, String itemId, Policy policy) {
		long sum = 0;
		NbtList list = items(stackNbt, policy);
		for (int i = 0; i < list.size(); i++) {
			NbtCompound e = list.getCompound(i);
			if (!itemId.equals(e.getString("id"))) continue;
			sum += Math.max(0, e.getByte("Count"));
		}
		return sum;
	}

	/**
	 * 从容器里扣物品（先扣数量小的槽以尽快清空，减少碎片）。
	 *
	 * @return 实际扣掉的数量；槽位扣空后从列表移除
	 */
	public static int take(NbtCompound stackNbt, String itemId, int want, Policy policy) {
		if (want <= 0) return 0;
		NbtList list = items(stackNbt, policy);
		int left = want;
		for (int i = list.size() - 1; i >= 0 && left > 0; i--) {
			NbtCompound e = list.getCompound(i);
			if (!itemId.equals(e.getString("id"))) continue;
			int have = Math.max(0, e.getByte("Count"));
			int take = Math.min(left, have);
			if (take <= 0) continue;
			e.putByte("Count", (byte) (have - take));
			left -= take;
			if (have - take <= 0) list.remove(i);
		}
		return want - left;
	}

	/** 找一个堆叠第一个匹配的物品 id（调试/提示用）。 */
	public static String firstItemId(NbtCompound stackNbt, Policy policy) {
		NbtList list = items(stackNbt, policy);
		for (int i = 0; i < list.size(); i++) {
			String id = list.getCompound(i).getString("id");
			if (!id.isEmpty()) return id;
		}
		return "";
	}

	/** 测试/工具用：构造一条标准容器物品条目。 */
	public static NbtCompound entry(int slot, String itemId, int count) {
		NbtCompound e = new NbtCompound();
		e.putByte("Slot", (byte) slot);
		e.putString("id", itemId);
		e.putByte("Count", (byte) count);
		return e;
	}

	/** 测试/工具用：把条目塞进物品 NBT 的 BlockEntityTag.Items。 */
	public static NbtCompound asContainer(List<NbtCompound> entries) {
		NbtList list = new NbtList();
		entries.forEach(list::add);
		NbtCompound be = new NbtCompound();
		be.put("Items", list);
		NbtCompound root = new NbtCompound();
		root.put("BlockEntityTag", be);
		return root;
	}
}
