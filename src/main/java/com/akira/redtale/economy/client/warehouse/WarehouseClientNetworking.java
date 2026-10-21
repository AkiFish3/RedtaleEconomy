package com.akira.redtale.economy.client.warehouse;

import com.akira.redtale.economy.net.PacketIds;
import com.akira.redtale.economy.net.WarehouseProtocol;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.network.PacketByteBuf;

/** 仓储自绘界面的客户端侧。 */
@Environment(EnvType.CLIENT)
public final class WarehouseClientNetworking {

	public static volatile WarehouseProtocol.Snapshot LAST = WarehouseProtocol.Snapshot.empty();

	/** 客户端镜像：本地立即生效，服务端快照回来后覆盖（消除 50ms 顿感）。 */
	public static final java.util.List<net.minecraft.item.ItemStack> SLOTS = new java.util.ArrayList<>();
	public static net.minecraft.item.ItemStack CURSOR = net.minecraft.item.ItemStack.EMPTY;
	public static int SLOT_COUNT;
	public static int NEXT_SLOTS;
	public static long NEXT_PRICE;
	public static long NEXT_PREMIUM;
	public static boolean LOADED;

	private WarehouseClientNetworking() {
	}

	public static void register() {
		ClientPlayNetworking.registerGlobalReceiver(PacketIds.S2C_WAREHOUSE, (client, handler, buf, sender) -> {
			WarehouseProtocol.Snapshot snap = WarehouseProtocol.read(buf);
			client.execute(() -> {
				LAST = snap;
				SLOTS.clear();
				SLOTS.addAll(snap.stacks());
				CURSOR = snap.cursor() == null ? net.minecraft.item.ItemStack.EMPTY : snap.cursor();
				SLOT_COUNT = snap.slots();
				NEXT_SLOTS = snap.nextSlots();
				NEXT_PRICE = snap.nextPrice();
				NEXT_PREMIUM = snap.nextPremium();
				LOADED = snap.loaded();
			});
		});
	}

	public static void request() {
		ClientPlayNetworking.send(PacketIds.C2S_WAREHOUSE_REQ,
				new PacketByteBuf(io.netty.buffer.Unpooled.buffer()));
	}

	/** 本地立即改镜像（服务端会再纠正一次）。 */
	public static void localPick(int index, boolean half) {
		if (index < 0 || index >= SLOTS.size()) return;
		net.minecraft.item.ItemStack slot = SLOTS.get(index);
		if (slot == null || slot.isEmpty()) return;
		int n = half ? Math.max(1, (slot.getCount() + 1) / 2) : slot.getCount();
		if (!CURSOR.isEmpty()) {
			if (!net.minecraft.item.ItemStack.canCombine(CURSOR, slot)) return;
			n = Math.min(n, CURSOR.getMaxCount() - CURSOR.getCount());
			if (n <= 0) return;
			CURSOR.setCount(CURSOR.getCount() + n);
		} else {
			CURSOR = slot.copyWithCount(n);
		}
		net.minecraft.item.ItemStack left = slot.copyWithCount(slot.getCount() - n);
		SLOTS.set(index, left.isEmpty() ? net.minecraft.item.ItemStack.EMPTY : left);
	}

	/** 背包抓取的本地预演。 */
	public static void localInvPick(int slot, boolean half) {
		var mc = net.minecraft.client.MinecraftClient.getInstance();
		if (mc.player == null) return;
		var inv = mc.player.getInventory();
		net.minecraft.item.ItemStack st = inv.getStack(slot);
		if (st.isEmpty()) return;
		int n = half ? Math.max(1, (st.getCount() + 1) / 2) : st.getCount();
		if (!CURSOR.isEmpty()) {
			if (!net.minecraft.item.ItemStack.canCombine(CURSOR, st)) return;
			n = Math.min(n, CURSOR.getMaxCount() - CURSOR.getCount());
			if (n <= 0) return;
			CURSOR.setCount(CURSOR.getCount() + n);
		} else {
			CURSOR = st.copyWithCount(n);
		}
		inv.setStack(slot, st.copyWithCount(st.getCount() - n));
	}

	public static void localPlace(int index, boolean one) {
		if (index < 0 || index >= SLOTS.size() || CURSOR.isEmpty()) return;
		net.minecraft.item.ItemStack slot = SLOTS.get(index);
		int n = one ? 1 : CURSOR.getCount();
		if (slot == null || slot.isEmpty()) {
			SLOTS.set(index, CURSOR.copyWithCount(n));
		} else {
			if (!net.minecraft.item.ItemStack.canCombine(slot, CURSOR)) return;
			n = Math.min(n, slot.getMaxCount() - slot.getCount());
			if (n <= 0) return;
			slot.setCount(slot.getCount() + n);
		}
		CURSOR.setCount(CURSOR.getCount() - n);
		if (CURSOR.isEmpty()) CURSOR = net.minecraft.item.ItemStack.EMPTY;
	}

	/** 仓储→背包的本地预演。 */
	public static void localQuickOut(int index) {
		if (index < 0 || index >= SLOTS.size()) return;
		net.minecraft.item.ItemStack st = SLOTS.get(index);
		if (st == null || st.isEmpty()) return;
		SLOTS.set(index, net.minecraft.item.ItemStack.EMPTY);
	}

	/** 背包→仓储的本地预演：先叠同类，再找空位。 */
	public static void localQuickIn(int invSlot) {
		var mc = net.minecraft.client.MinecraftClient.getInstance();
		if (mc.player == null) return;
		net.minecraft.item.ItemStack src = mc.player.getInventory().getStack(invSlot);
		if (src.isEmpty()) return;
		int left = src.getCount();
		for (int i = 0; i < SLOTS.size() && left > 0; i++) {
			net.minecraft.item.ItemStack dst = SLOTS.get(i);
			if (dst == null || dst.isEmpty() || !net.minecraft.item.ItemStack.canCombine(dst, src)) continue;
			int room = Math.min(left, dst.getMaxCount() - dst.getCount());
			if (room <= 0) continue;
			dst.setCount(dst.getCount() + room);
			left -= room;
		}
		for (int i = 0; i < SLOTS.size() && left > 0; i++) {
			net.minecraft.item.ItemStack dst = SLOTS.get(i);
			if (dst != null && !dst.isEmpty()) continue;
			SLOTS.set(i, src.copyWithCount(left));
			left = 0;
		}
		if (left < src.getCount()) {
			mc.player.getInventory().setStack(invSlot, src.copyWithCount(left));
		}
	}

	/** 整理的本地预演（合并 + 按 id 排序）。 */
	public static void localSort() {
		java.util.List<net.minecraft.item.ItemStack> all = new java.util.ArrayList<>();
		for (net.minecraft.item.ItemStack st : SLOTS) {
			if (st == null || st.isEmpty()) continue;
			boolean merged = false;
			for (net.minecraft.item.ItemStack acc : all) {
				if (!net.minecraft.item.ItemStack.canCombine(acc, st)) continue;
				int room = acc.getMaxCount() - acc.getCount();
				if (room <= 0) continue;
				int move = Math.min(room, st.getCount());
				acc.setCount(acc.getCount() + move);
				st.setCount(st.getCount() - move);
				if (st.isEmpty()) {
					merged = true;
					break;
				}
			}
			if (!merged && !st.isEmpty()) all.add(st.copy());
		}
		all.sort(java.util.Comparator.comparing(s2 ->
				net.minecraft.registry.Registries.ITEM.getId(s2.getItem()).toString()));
		for (int i = 0; i < SLOTS.size(); i++) {
			SLOTS.set(i, i < all.size() ? all.get(i) : net.minecraft.item.ItemStack.EMPTY);
		}
	}

	/** 双击汇集的本地预演。 */
	public static void localCollect() {
		if (CURSOR.isEmpty()) return;
		for (int i = 0; i < SLOTS.size(); i++) {
			net.minecraft.item.ItemStack st = SLOTS.get(i);
			if (st == null || st.isEmpty() || !net.minecraft.item.ItemStack.canCombine(st, CURSOR)) continue;
			int room = CURSOR.getMaxCount() - CURSOR.getCount();
			if (room <= 0) break;
			int move = Math.min(room, st.getCount());
			CURSOR.setCount(CURSOR.getCount() + move);
			SLOTS.set(i, st.copyWithCount(st.getCount() - move));
		}
		var mc = net.minecraft.client.MinecraftClient.getInstance();
		if (mc.player == null) return;
		var inv = mc.player.getInventory();
		for (int i = 0; i < 36; i++) {
			net.minecraft.item.ItemStack st = inv.getStack(i);
			if (st.isEmpty() || !net.minecraft.item.ItemStack.canCombine(st, CURSOR)) continue;
			int room = CURSOR.getMaxCount() - CURSOR.getCount();
			if (room <= 0) break;
			int move = Math.min(room, st.getCount());
			CURSOR.setCount(CURSOR.getCount() + move);
			inv.setStack(i, st.copyWithCount(st.getCount() - move));
		}
	}

	public static void pick(int index, boolean half) {
		PacketByteBuf buf = new PacketByteBuf(io.netty.buffer.Unpooled.buffer());
		buf.writeVarInt(index);
		buf.writeBoolean(half);
		ClientPlayNetworking.send(PacketIds.C2S_WAREHOUSE_PICK, buf);
	}

	public static void place(int index, boolean one) {
		PacketByteBuf buf = new PacketByteBuf(io.netty.buffer.Unpooled.buffer());
		buf.writeVarInt(index);
		buf.writeBoolean(one);
		ClientPlayNetworking.send(PacketIds.C2S_WAREHOUSE_PLACE, buf);
	}

	public static void quick(int index) {
		PacketByteBuf buf = new PacketByteBuf(io.netty.buffer.Unpooled.buffer());
		buf.writeVarInt(index);
		ClientPlayNetworking.send(PacketIds.C2S_WAREHOUSE_QUICK, buf);
	}

	public static void quickInv(int slot) {
		PacketByteBuf buf = new PacketByteBuf(io.netty.buffer.Unpooled.buffer());
		buf.writeVarInt(slot);
		ClientPlayNetworking.send(PacketIds.C2S_WAREHOUSE_QUICK_INV, buf);
	}

	public static void hotbar(int slot) {
		PacketByteBuf buf = new PacketByteBuf(io.netty.buffer.Unpooled.buffer());
		buf.writeVarInt(slot);
		ClientPlayNetworking.send(PacketIds.C2S_WAREHOUSE_HOTBAR, buf);
	}

	public static void collect() {
		ClientPlayNetworking.send(PacketIds.C2S_WAREHOUSE_COLLECT,
				new PacketByteBuf(io.netty.buffer.Unpooled.buffer()));
	}

	public static void invPick(int slot, boolean half) {
		PacketByteBuf buf = new PacketByteBuf(io.netty.buffer.Unpooled.buffer());
		buf.writeVarInt(slot);
		buf.writeBoolean(half);
		ClientPlayNetworking.send(PacketIds.C2S_WAREHOUSE_INV_PICK, buf);
	}

	public static void dropCursor() {
		ClientPlayNetworking.send(PacketIds.C2S_WAREHOUSE_DROP,
				new PacketByteBuf(io.netty.buffer.Unpooled.buffer()));
	}

	/** 光标物品放进背包格（本地预演）。 */
	public static void localPlaceToInv(int slot, boolean one) {
		var mc = net.minecraft.client.MinecraftClient.getInstance();
		if (mc.player == null || CURSOR.isEmpty()) return;
		var inv = mc.player.getInventory();
		net.minecraft.item.ItemStack dst = inv.getStack(slot);
		int n = one ? 1 : CURSOR.getCount();
		if (dst.isEmpty()) {
			inv.setStack(slot, CURSOR.copyWithCount(n));
		} else {
			if (!net.minecraft.item.ItemStack.canCombine(dst, CURSOR)) return;
			int room = Math.min(n, dst.getMaxCount() - dst.getCount());
			if (room <= 0) return;
			dst.setCount(dst.getCount() + room);
			inv.setStack(slot, dst);
			n = room;
		}
		CURSOR.setCount(CURSOR.getCount() - n);
		if (CURSOR.isEmpty()) CURSOR = net.minecraft.item.ItemStack.EMPTY;
	}

	public static void placeToInv(int slot, boolean one) {
		PacketByteBuf buf = new PacketByteBuf(io.netty.buffer.Unpooled.buffer());
		buf.writeVarInt(slot);
		buf.writeBoolean(one);
		ClientPlayNetworking.send(PacketIds.C2S_WAREHOUSE_PLACE_INV, buf);
	}

	public static void spread(int[] slots, boolean even) {
		PacketByteBuf buf = new PacketByteBuf(io.netty.buffer.Unpooled.buffer());
		buf.writeVarInt(slots.length);
		for (int s : slots) buf.writeVarInt(s);
		buf.writeBoolean(even);
		ClientPlayNetworking.send(PacketIds.C2S_WAREHOUSE_SPREAD, buf);
	}

	public static void sort() {
		ClientPlayNetworking.send(PacketIds.C2S_WAREHOUSE_SORT,
				new PacketByteBuf(io.netty.buffer.Unpooled.buffer()));
	}

	public static void upgrade() {
		ClientPlayNetworking.send(PacketIds.C2S_WAREHOUSE_UPGRADE,
				new PacketByteBuf(io.netty.buffer.Unpooled.buffer()));
	}
}
