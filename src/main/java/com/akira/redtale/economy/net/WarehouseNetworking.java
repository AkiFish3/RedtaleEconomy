package com.akira.redtale.economy.net;

import com.akira.redtale.economy.module.ModuleEvents;
import com.akira.redtale.economy.warehouse.WarehouseData;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.item.ItemStack;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 仓储：服务端权威 + 光标持物。
 *
 * <p>原版手感的关键是「光标上抓着一叠东西」，所以服务端要记住每个玩家的光标内容。
 * 所有操作都在服务端校验后落盘，客户端只画。
 */
public final class WarehouseNetworking {

	private static final Map<UUID, ItemStack> CURSOR = new HashMap<>();

	private WarehouseNetworking() {
	}

	public static void send(ServerPlayerEntity player) {
		if (player == null) return;
		WarehouseData d = WarehouseData.of(player.getUuid());
		int slots = d == null ? 0 : d.slots();
		int purchased = d == null ? 0 : d.purchased();
		List<ItemStack> list = new ArrayList<>();
		for (int i = 0; i < slots; i++) {
			ItemStack st = d == null ? ItemStack.EMPTY : d.get(i);
			list.add(st == null ? ItemStack.EMPTY : st);
		}
		ItemStack cur = cursor(player.getUuid());
		var next = com.akira.redtale.economy.config.WarehouseConfig.nextUpgrade(slots);
		PacketByteBuf buf = new PacketByteBuf(io.netty.buffer.Unpooled.buffer());
		WarehouseProtocol.write(buf, new WarehouseProtocol.Snapshot(slots, purchased, list, cur,
				next == null ? 0 : next.slots(),
				next == null ? 0 : next.price(),
				next == null ? 0 : next.premium(),
				true));
		ServerPlayNetworking.send(player, PacketIds.S2C_WAREHOUSE, buf);
	}

	private static ItemStack cursor(UUID id) {
		return CURSOR.computeIfAbsent(id, k -> ItemStack.EMPTY);
	}

	private static void setCursor(UUID id, ItemStack st) {
		CURSOR.put(id, st == null ? ItemStack.EMPTY : st);
	}

	public static void register() {
		ServerPlayNetworking.registerGlobalReceiver(PacketIds.C2S_WAREHOUSE_REQ,
				(server, player, h, buf, sender) -> server.execute(() -> send(player)));

		// 抓取：0 = 整叠，1 = 一半
		ServerPlayNetworking.registerGlobalReceiver(PacketIds.C2S_WAREHOUSE_PICK,
				(server, player, h, buf, sender) -> {
					int idx = buf.readVarInt();
					boolean half = buf.readBoolean();
					server.execute(() -> pick(player, idx, half));
				});

		// 放下：1 = 只放一个
		ServerPlayNetworking.registerGlobalReceiver(PacketIds.C2S_WAREHOUSE_PLACE,
				(server, player, h, buf, sender) -> {
					int idx = buf.readVarInt();
					boolean one = buf.readBoolean();
					server.execute(() -> place(player, idx, one));
				});

		ServerPlayNetworking.registerGlobalReceiver(PacketIds.C2S_WAREHOUSE_QUICK,
				(server, player, h, buf, sender) -> {
					int idx = buf.readVarInt();
					server.execute(() -> quickOut(player, idx));
				});

		ServerPlayNetworking.registerGlobalReceiver(PacketIds.C2S_WAREHOUSE_QUICK_INV,
				(server, player, h, buf, sender) -> {
					int slot = buf.readVarInt();
					server.execute(() -> quickIn(player, slot));
				});

		ServerPlayNetworking.registerGlobalReceiver(PacketIds.C2S_WAREHOUSE_SPREAD,
				(server, player, h, buf, sender) -> {
					// T33：读完再截断。老写法 min(n,256) 一旦超限就把后面的 even 当成槽位读
					java.util.List<Integer> raw = Codecs.readList(buf, 256, Codecs.VAR_INT);
					int[] slots = new int[raw.size()];
					for (int i = 0; i < slots.length; i++) slots[i] = raw.get(i);
					boolean even = buf.readBoolean();
					server.execute(() -> spread(player, slots, even));
				});

		ServerPlayNetworking.registerGlobalReceiver(PacketIds.C2S_WAREHOUSE_PLACE_INV,
				(server, player, h, buf, sender) -> {
					int slot = buf.readVarInt();
					boolean one = buf.readBoolean();
					server.execute(() -> placeToInv(player, slot, one));
				});

		ServerPlayNetworking.registerGlobalReceiver(PacketIds.C2S_WAREHOUSE_HOTBAR,
				(server, player, h, buf, sender) -> {
					int slot = buf.readVarInt();
					server.execute(() -> hotbarSwap(player, slot));
				});

		ServerPlayNetworking.registerGlobalReceiver(PacketIds.C2S_WAREHOUSE_INV_PICK,
				(server, player, h, buf, sender) -> {
					int slot = buf.readVarInt();
					boolean half = buf.readBoolean();
					server.execute(() -> invPick(player, slot, half));
				});

		ServerPlayNetworking.registerGlobalReceiver(PacketIds.C2S_WAREHOUSE_DROP,
				(server, player, h, buf, sender) -> server.execute(() -> dropCursor(player)));

		ServerPlayNetworking.registerGlobalReceiver(PacketIds.C2S_WAREHOUSE_COLLECT,
				(server, player, h, buf, sender) -> server.execute(() -> collect(player)));

		ServerPlayNetworking.registerGlobalReceiver(PacketIds.C2S_WAREHOUSE_SORT,
				(server, player, h, buf, sender) -> server.execute(() -> sort(player)));

		ServerPlayNetworking.registerGlobalReceiver(PacketIds.C2S_WAREHOUSE_UPGRADE,
				(server, player, h, buf, sender) -> server.execute(() -> upgrade(player)));

		// 断开时把光标里的东西塞回背包，避免丢物品
		ModuleEvents.onQuit("warehouse.cursor", p -> {
			ItemStack cur = CURSOR.remove(p.getUuid());
			if (cur != null && !cur.isEmpty()) {
				p.getInventory().offerOrDrop(cur);
			}
		});
	}

	// ---------------------------------------------------------------- 操作

	private static void pick(ServerPlayerEntity player, int index, boolean half) {
		WarehouseData d = WarehouseData.of(player.getUuid());
		if (index < 0 || index >= d.slots()) return;
		ItemStack slot = d.get(index);
		if (slot == null || slot.isEmpty()) return;
		ItemStack cur = cursor(player.getUuid());
		int n = half ? Math.max(1, (slot.getCount() + 1) / 2) : slot.getCount();
		if (!cur.isEmpty()) {
			if (!ItemStack.canCombine(cur, slot)) return;
			int room = cur.getMaxCount() - cur.getCount();
			if (room <= 0) return;
			n = Math.min(n, room);
			cur.setCount(cur.getCount() + n);
			setCursor(player.getUuid(), cur);
		} else {
			ItemStack taken = slot.copy();
			taken.setCount(n);
			setCursor(player.getUuid(), taken);
		}
		ItemStack left = slot.copy();
		left.setCount(slot.getCount() - n);
		d.set(index, left.isEmpty() ? ItemStack.EMPTY : left);
		d.save();
		send(player);
	}

	private static void place(ServerPlayerEntity player, int index, boolean one) {
		WarehouseData d = WarehouseData.of(player.getUuid());
		if (index < 0 || index >= d.slots()) return;
		ItemStack cur = cursor(player.getUuid());
		if (cur.isEmpty()) return;
		ItemStack slot = d.get(index);
		int n = one ? 1 : cur.getCount();
		if (slot == null || slot.isEmpty()) {
			ItemStack put = cur.copy();
			put.setCount(n);
			d.set(index, put);
		} else {
			if (!ItemStack.canCombine(slot, cur)) return;
			int room = slot.getMaxCount() - slot.getCount();
			n = Math.min(n, room);
			if (n <= 0) return;
			slot.setCount(slot.getCount() + n);
			d.set(index, slot);
		}
		ItemStack left = cur.copy();
		left.setCount(cur.getCount() - n);
		setCursor(player.getUuid(), left.isEmpty() ? ItemStack.EMPTY : left);
		d.save();
		send(player);
	}

	/** 仓储 → 背包（Shift 点击）。 */
	private static void quickOut(ServerPlayerEntity player, int index) {
		WarehouseData d = WarehouseData.of(player.getUuid());
		if (index < 0 || index >= d.slots()) return;
		ItemStack slot = d.get(index);
		if (slot == null || slot.isEmpty()) return;
		player.getInventory().offerOrDrop(slot.copy());
		d.set(index, ItemStack.EMPTY);
		d.save();
		send(player);
	}

	/** 背包 → 仓储（Shift 点击）。 */
	private static void quickIn(ServerPlayerEntity player, int slot) {
		WarehouseData d = WarehouseData.of(player.getUuid());
		ItemStack src = player.getInventory().getStack(slot);
		if (src.isEmpty()) return;
		int left = insertAll(d, src);
		if (left == src.getCount()) {
			player.sendMessage(Text.literal("仓储已满"), true);
			return;
		}
		ItemStack remain = src.copy();
		remain.setCount(left);
		player.getInventory().setStack(slot, remain.isEmpty() ? ItemStack.EMPTY : remain);
		d.save();
		send(player);
	}

	private static int insertAll(WarehouseData d, ItemStack moving) {
		int left = moving.getCount();
		for (int i = 0; i < d.slots() && left > 0; i++) {
			ItemStack dst = d.get(i);
			if (dst == null || dst.isEmpty() || !ItemStack.canCombine(dst, moving)) continue;
			int room = Math.min(left, dst.getMaxCount() - dst.getCount());
			if (room <= 0) continue;
			dst.setCount(dst.getCount() + room);
			d.set(i, dst);
			left -= room;
		}
		for (int i = 0; i < d.slots() && left > 0; i++) {
			ItemStack dst = d.get(i);
			if (dst != null && !dst.isEmpty()) continue;
			ItemStack one = moving.copy();
			one.setCount(left);
			d.set(i, one);
			left = 0;
		}
		return left;
	}

	/** 从背包抓取到光标（和原版一样能抓背包里的东西）。 */
	private static void invPick(ServerPlayerEntity player, int slot, boolean half) {
		ItemStack st = player.getInventory().getStack(slot);
		if (st.isEmpty()) return;
		ItemStack cur = cursor(player.getUuid());
		int n = half ? Math.max(1, (st.getCount() + 1) / 2) : st.getCount();
		if (!cur.isEmpty()) {
			if (!ItemStack.canCombine(cur, st)) return;
			int room = cur.getMaxCount() - cur.getCount();
			if (room <= 0) return;
			n = Math.min(n, room);
			cur.setCount(cur.getCount() + n);
			setCursor(player.getUuid(), cur);
		} else {
			ItemStack taken = st.copy();
			taken.setCount(n);
			setCursor(player.getUuid(), taken);
		}
		ItemStack left = st.copy();
		left.setCount(st.getCount() - n);
		player.getInventory().setStack(slot, left.isEmpty() ? ItemStack.EMPTY : left);
		send(player);
	}

	/** 关闭界面：把光标里的东西塞回背包，避免卡住。 */
	private static void dropCursor(ServerPlayerEntity player) {
		ItemStack cur = cursor(player.getUuid());
		if (cur.isEmpty()) return;
		player.getInventory().offerOrDrop(cur);
		setCursor(player.getUuid(), ItemStack.EMPTY);
		send(player);
	}

	/**
	 * 拖拽分配：把光标里的物品分到经过的格子。
	 *
	 * <p>even=true（左键拖拽）= 平均分，余数给前几格；even=false（右键）= 每格 1 个。
	 */
	private static void spread(ServerPlayerEntity player, int[] slots, boolean even) {
		ItemStack cur = cursor(player.getUuid());
		if (cur.isEmpty() || slots.length == 0) return;
		WarehouseData d = WarehouseData.of(player.getUuid());
		int n = Math.min(slots.length, 64);
		int pool = cur.getCount();
		int per = even ? Math.max(1, pool / n) : 1;
		int remain = even ? pool % n : 0;
		for (int i = 0; i < n && !cur.isEmpty(); i++) {
			int idx = slots[i];
			if (idx < 0 || idx >= d.slots()) continue;
			int want = per + (i < remain ? 1 : 0);
			ItemStack dst = d.get(idx);
			if (dst == null || dst.isEmpty()) {
				ItemStack put = cur.copyWithCount(Math.min(want, cur.getCount()));
				d.set(idx, put);
				cur.setCount(cur.getCount() - put.getCount());
			} else if (ItemStack.canCombine(dst, cur)) {
				int room = Math.min(want, dst.getMaxCount() - dst.getCount());
				if (room <= 0) continue;
				dst.setCount(dst.getCount() + room);
				d.set(idx, dst);
				cur.setCount(cur.getCount() - room);
			}
		}
		setCursor(player.getUuid(), cur.isEmpty() ? ItemStack.EMPTY : cur);
		d.save();
		send(player);
	}

	/** 光标物品放进玩家背包指定格。 */
	private static void placeToInv(ServerPlayerEntity player, int slot, boolean one) {
		if (slot < 0 || slot >= 36) return;
		ItemStack cur = cursor(player.getUuid());
		if (cur.isEmpty()) return;
		ItemStack dst = player.getInventory().getStack(slot);
		int n = one ? 1 : cur.getCount();
		if (dst.isEmpty()) {
			player.getInventory().setStack(slot, cur.copyWithCount(n));
		} else {
			if (!ItemStack.canCombine(dst, cur)) return;
			int room = Math.min(n, dst.getMaxCount() - dst.getCount());
			if (room <= 0) return;
			dst.setCount(dst.getCount() + room);
			player.getInventory().setStack(slot, dst);
			n = room;
		}
		ItemStack left = cur.copy();
		left.setCount(cur.getCount() - n);
		setCursor(player.getUuid(), left.isEmpty() ? ItemStack.EMPTY : left);
		send(player);
	}

	/** 数字键：光标与快捷栏交换。 */
	private static void hotbarSwap(ServerPlayerEntity player, int hotbarSlot) {
		ItemStack cur = cursor(player.getUuid());
		ItemStack bar = player.getInventory().getStack(hotbarSlot);
		setCursor(player.getUuid(), bar);
		player.getInventory().setStack(hotbarSlot, cur);
		send(player);
	}

	/** 双击：把仓储和背包里与光标同类的全吸过来（原版容器是两边都收）。 */
	private static void collect(ServerPlayerEntity player) {
		ItemStack cur = cursor(player.getUuid());
		if (cur.isEmpty()) return;
		WarehouseData d = WarehouseData.of(player.getUuid());
		for (int i = 0; i < d.slots(); i++) {
			ItemStack slot = d.get(i);
			if (slot == null || slot.isEmpty() || !ItemStack.canCombine(slot, cur)) continue;
			int room = cur.getMaxCount() - cur.getCount();
			if (room <= 0) break;
			int move = Math.min(room, slot.getCount());
			cur.setCount(cur.getCount() + move);
			ItemStack left = slot.copy();
			left.setCount(slot.getCount() - move);
			d.set(i, left.isEmpty() ? ItemStack.EMPTY : left);
		}
		for (int i = 0; i < 36; i++) {
			ItemStack st = player.getInventory().getStack(i);
			if (st.isEmpty() || !ItemStack.canCombine(st, cur)) continue;
			int room = cur.getMaxCount() - cur.getCount();
			if (room <= 0) break;
			int move = Math.min(room, st.getCount());
			cur.setCount(cur.getCount() + move);
			ItemStack left = st.copy();
			left.setCount(st.getCount() - move);
			player.getInventory().setStack(i, left.isEmpty() ? ItemStack.EMPTY : left);
		}
		setCursor(player.getUuid(), cur);
		d.save();
		send(player);
	}

	private static void sort(ServerPlayerEntity player) {
		WarehouseData d = WarehouseData.of(player.getUuid());
		List<ItemStack> all = new ArrayList<>();
		for (int i = 0; i < d.slots(); i++) {
			ItemStack st = d.get(i);
			if (st == null || st.isEmpty()) continue;
			boolean merged = false;
			for (ItemStack acc : all) {
				if (!ItemStack.canCombine(acc, st)) continue;
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
		for (int i = 0; i < d.slots(); i++) {
			d.set(i, i < all.size() ? all.get(i) : ItemStack.EMPTY);
		}
		d.save();
		send(player);
	}

	private static void upgrade(ServerPlayerEntity player) {
		var r = com.akira.redtale.economy.module.impl.WarehouseModule.upgrade(player);
		player.sendMessage(Text.literal("仓储升级：" + r.result()), true);
		send(player);
	}
}
