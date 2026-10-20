package com.akira.redtale.economy.shop;

import net.minecraft.nbt.NbtCompound;
import org.junit.jupiter.api.Test;


import static org.junit.jupiter.api.Assertions.assertEquals;

/** R26：容器检测（默认 ignore 零解析；shulker/all 才统计与扣减）。 */
class ContainerScanTest {

	private static final String BREAD = "minecraft:bread";

	private static NbtCompound shulker(int... counts) {
		java.util.List<NbtCompound> list = new java.util.ArrayList<>();
		for (int i = 0; i < counts.length; i++) {
			list.add(ContainerScan.entry(i, BREAD, counts[i]));
		}
		return ContainerScan.asContainer(list);
	}

	@Test
	void ignorePolicyNeverParses() {
		NbtCompound nbt = shulker(16, 16);
		assertEquals(0, ContainerScan.count(nbt, BREAD, ContainerScan.Policy.IGNORE));
		assertEquals(0, ContainerScan.take(nbt, BREAD, 5, ContainerScan.Policy.IGNORE));
		// 内容原封不动
		assertEquals(32, ContainerScan.count(nbt, BREAD, ContainerScan.Policy.SHULKER));
	}

	@Test
	void countsContentsUnderShulkerPolicy() {
		assertEquals(32, ContainerScan.count(shulker(16, 16), BREAD, ContainerScan.Policy.SHULKER));
		assertEquals(0, ContainerScan.count(shulker(16), "minecraft:apple", ContainerScan.Policy.SHULKER));
	}

	@Test
	void takeRemovesFromSlotsAndDropsEmptyOnes() {
		NbtCompound nbt = shulker(10, 5);
		assertEquals(12, ContainerScan.take(nbt, BREAD, 12, ContainerScan.Policy.SHULKER));
		assertEquals(3, ContainerScan.count(nbt, BREAD, ContainerScan.Policy.SHULKER));
		// 扣到 0 的槽必须被移除，避免留下 Count:0 的假物品
		assertEquals(3, ContainerScan.take(nbt, BREAD, 99, ContainerScan.Policy.SHULKER));
		assertEquals(0, ContainerScan.count(nbt, BREAD, ContainerScan.Policy.SHULKER));
		assertEquals(0, ContainerScan.take(nbt, BREAD, 1, ContainerScan.Policy.SHULKER), "空容器不能再扣");
	}

	@Test
	void plainItemWithoutBlockEntityTagIsNotAContainer() {
		NbtCompound nbt = new NbtCompound();
		nbt.putString("display", "x");
		assertEquals(0, ContainerScan.count(nbt, BREAD, ContainerScan.Policy.ALL));
		assertEquals("", ContainerScan.firstItemId(nbt, ContainerScan.Policy.ALL));
	}

	@Test
	void policyParsingIsForgiving() {
		assertEquals(ContainerScan.Policy.IGNORE, ContainerScan.Policy.of(null));
		assertEquals(ContainerScan.Policy.IGNORE, ContainerScan.Policy.of("nonsense"));
		assertEquals(ContainerScan.Policy.SHULKER, ContainerScan.Policy.of("SHULKER"));
		assertEquals(ContainerScan.Policy.ALL, ContainerScan.Policy.of("all"));
	}

	@Test
	void worksForGenericBlockEntityContainersUnderAllPolicy() {
		NbtCompound be = new NbtCompound();
		net.minecraft.nbt.NbtList list = new net.minecraft.nbt.NbtList();
		list.add(ContainerScan.entry(0, BREAD, 7));
		be.put("Items", list);
		NbtCompound root = new NbtCompound();
		root.put("BlockEntityTag", be);
		assertEquals(7, ContainerScan.count(root, BREAD, ContainerScan.Policy.ALL));
		assertEquals("", ContainerScan.firstItemId(new NbtCompound(), ContainerScan.Policy.ALL));
	}
}
