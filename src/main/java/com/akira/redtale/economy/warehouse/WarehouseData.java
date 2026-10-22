package com.akira.redtale.economy.warehouse;

import com.akira.redtale.economy.RedtaleEconomy;
import com.akira.redtale.economy.config.WarehouseConfig;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.StringNbtReader;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.collection.DefaultedList;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 单个玩家的仓库数据（R27）。
 *
 * <p><b>存储位置</b>：{@code <storage.root>/warehouse/<uuid>.json}，物品用 SNBT 编码（{@code {Items:[...]}}）。
 * 规格书首选「玩家 NBT」，但 Fabric 侧没有可写回玩家存档的公开钩子（不引 mixin），
 * 因此改用「一人一文件 + 原子写」：同样崩溃安全、同样不跨服，且便于运营备份/回档。
 *
 * <p><b>格数</b>：{@code slots} 是当前总格数（含购买），实际数组可以比它大（缩容时旧物品不丢，
 * 只是暂时看不到），保证「配置改小」不会吃掉玩家东西。
 */
public final class WarehouseData {

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
	private static final Map<UUID, WarehouseData> CACHE = new ConcurrentHashMap<>();
	private static Path dir;
	private static volatile boolean tickRegistered;

	public final UUID uuid;
	/** 当前总格数（玩家可用格数）。 */
	private int slots;
	/** 已购买到的格数（= slots，便于以后做「租用到期」时区分）。 */
	private int purchased;
	private String updatedAt = "";
	private boolean dirty;

	private final List<ItemStack> items = new ArrayList<>();

	private WarehouseData(UUID uuid) {
		this.uuid = uuid;
		this.slots = WarehouseConfig.baseSlots();
		this.purchased = this.slots;
	}

	// ---------------------------------------------------------------- 目录

	public static void setDir(Path d) {
		dir = d;
	}

	public static Path dir() {
		return dir;
	}

	// ---------------------------------------------------------------- 读写

	public static WarehouseData of(UUID uuid) {
		WarehouseData d = CACHE.get(uuid);
		if (d != null) {
			d.applyConfigBounds();
			return d;
		}
		d = read(uuid);
		d.applyConfigBounds();
		CACHE.put(uuid, d);
		return d;
	}

	/** 已缓存（打开过）的数据；不存在返回 null，不触发读盘。 */
	public static WarehouseData peek(UUID uuid) {
		return CACHE.get(uuid);
	}

	private static WarehouseData read(UUID uuid) {
		WarehouseData d = new WarehouseData(uuid);
		Path f = file(uuid);
		if (f == null || !Files.isRegularFile(f)) return d;
		try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
			JsonObject o = GSON.fromJson(r, JsonObject.class);
			if (o == null) return d;
			d.slots = o.has("slots") ? o.get("slots").getAsInt() : d.slots;
			d.purchased = o.has("purchased") ? o.get("purchased").getAsInt() : d.slots;
			d.updatedAt = o.has("updatedAt") ? o.get("updatedAt").getAsString() : "";
			if (o.has("inventory") && o.get("inventory").isJsonPrimitive()) {
				d.loadItems(o.get("inventory").getAsString());
			}
		} catch (Exception e) {
			RedtaleEconomy.LOGGER.error("[warehouse] 读取 {} 失败，按空仓库处理：{}", uuid, e.getMessage());
			d.slots = WarehouseConfig.baseSlots();
			d.purchased = d.slots;
			d.items.clear();
		}
		return d;
	}

	private void loadItems(String snbt) {
		NbtCompound nbt;
		try {
			nbt = StringNbtReader.parse(snbt);
		} catch (Exception e) {
			RedtaleEconomy.LOGGER.error("[warehouse] {} 的物品 SNBT 解析失败：{}", uuid, e.getMessage());
			return;
		}
		int size = Math.max(nbt.contains("size") ? nbt.getInt("size") : 0, slots);
		DefaultedList<ItemStack> list = DefaultedList.ofSize(Math.max(size, 9), ItemStack.EMPTY);
		try {
			net.minecraft.inventory.Inventories.readNbt(nbt, list);
		} catch (Exception e) {
			RedtaleEconomy.LOGGER.error("[warehouse] {} 的物品读取失败：{}", uuid, e.getMessage());
			return;
		}
		items.clear();
		items.addAll(list);
	}

	/** 序列化成 JSON（物品转 SNBT）。 */
	public String toJson() {
		JsonObject o = new JsonObject();
		o.addProperty("_version", 1);
		o.addProperty("uuid", uuid.toString());
		o.addProperty("slots", slots);
		o.addProperty("purchased", purchased);
		o.addProperty("updatedAt", updatedAt);
		o.addProperty("inventory", toSnbt());
		return GSON.toJson(o);
	}

	private String toSnbt() {
		int size = items.size() + 9 - (items.size() % 9);
		DefaultedList<ItemStack> list = DefaultedList.ofSize(Math.max(size, 9), ItemStack.EMPTY);
		for (int i = 0; i < Math.min(items.size(), list.size()); i++) list.set(i, items.get(i));
		NbtCompound nbt = new NbtCompound();
		nbt.putInt("size", list.size());
		try {
			net.minecraft.inventory.Inventories.writeNbt(nbt, list);
		} catch (Exception e) {
			RedtaleEconomy.LOGGER.error("[warehouse] {} 物品序列化失败：{}", uuid, e.getMessage());
			NbtList empty = new NbtList();
			nbt.put("Items", empty);
		}
		return nbt.toString();
	}

	public void save() {
		saveChecked();
	}

	/**
	 * 落盘并返回是否真的写成功。
	 *
	 * <p>★R129：扣钱扩容之后必须看这个返回值★ 写失败时内存里已经扩容、磁盘没有，
	 * 重启后玩家付过钱的档位消失；而如果放任内存态不退回，下一次任何 save 都会把这档「免费」写进磁盘。
	 */
	public boolean saveChecked() {
		Path f = file(uuid);
		if (f == null) return false;
		updatedAt = java.time.LocalDateTime.now()
				.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
		Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
		try {
			Files.createDirectories(f.getParent());
			try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
				w.write(toJson());
			}
			try {
				Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (IOException atomicFail) {
				Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
			}
			dirty = false;
			return true;
		} catch (IOException e) {
			RedtaleEconomy.LOGGER.error("[warehouse] 写入 {} 失败：{}", uuid, e.getMessage());
			return false;
		}
	}

	/** 扩容落盘失败时把内存格数退回原值（见 {@link #saveChecked()}）。 */
	public void rollbackExpand(int previousSlots) {
		int base = WarehouseConfig.baseSlots();
		slots = Math.max(base, previousSlots);
		purchased = Math.min(purchased, slots);
		dirty = true;
	}

	private static Path file(UUID uuid) {
		return dir == null ? null : dir.resolve(uuid + ".json");
	}

	// ---------------------------------------------------------------- 格数

	public int slots() {
		return slots;
	}

	public int purchased() {
		return purchased;
	}

	/** 配置改小后：可用格数跟配置走，但绝不低于 baseSlots；旧物品保留在数组里。 */
	private void applyConfigBounds() {
		int base = WarehouseConfig.baseSlots();
		if (slots < base) slots = base;
		if (purchased < base) purchased = base;
		int max = WarehouseConfig.maxSlots();
		if (purchased > max) {
			if (WarehouseConfig.allowDowngrade()) purchased = max;
			else {
				// 已购买超过新上限：保持玩家权益，但可用格数不超上限
				purchased = Math.max(purchased, max);
			}
		}
		if (slots > purchased) slots = purchased;
	}

	/** 扩容到目标格数；返回是否成功。 */
	public boolean expandTo(int target) {
		int t = Math.min(WarehouseConfig.normalise(target), WarehouseConfig.maxSlots());
		if (t <= slots) return false;
		slots = t;
		purchased = Math.max(purchased, t);
		dirty = true;
		return true;
	}

	// ---------------------------------------------------------------- 物品

	public ItemStack get(int index) {
		if (index < 0 || index >= items.size()) return ItemStack.EMPTY;
		ItemStack s = items.get(index);
		return s == null ? ItemStack.EMPTY : s;
	}

	public void set(int index, ItemStack stack) {
		if (index < 0) return;
		while (items.size() <= index) items.add(ItemStack.EMPTY);
		items.set(index, stack == null ? ItemStack.EMPTY : stack);
		dirty = true;
	}

	public int size() {
		return items.size();
	}

	public void markDirty() {
		dirty = true;
	}

	public boolean isDirty() {
		return dirty;
	}

	/** 仓库里某物品总数（只统计可用格数内，避免缩容后「看不见却算数」）。 */
	public int count(Item item) {
		int n = 0;
		for (int i = 0; i < Math.min(slots, items.size()); i++) {
			ItemStack s = items.get(i);
			if (s != null && !s.isEmpty() && s.getItem() == item) n += s.getCount();
		}
		return n;
	}

	public int count(String itemId) {
		Item it = resolve(itemId);
		return it == null ? 0 : count(it);
	}

	/**
	 * 按物品 id 扣数量；返回实际扣掉的数量。
	 * 只动可用格数内的格子。
	 */
	public int take(String itemId, int amount) {
		Item item = resolve(itemId);
		if (item == null || amount <= 0) return 0;
		int left = amount;
		for (int i = 0; i < Math.min(slots, items.size()) && left > 0; i++) {
			ItemStack s = items.get(i);
			if (s == null || s.isEmpty() || s.getItem() != item) continue;
			int take = Math.min(left, s.getCount());
			s.decrement(take);
			left -= take;
			if (s.isEmpty()) items.set(i, ItemStack.EMPTY);
			dirty = true;
		}
		return amount - left;
	}

	/** 存入一叠物品；返回没塞进去的部分。 */
	public ItemStack insert(ItemStack stack) {
		if (stack == null || stack.isEmpty()) return ItemStack.EMPTY;
		ItemStack rest = stack.copy();
		int limit = Math.min(slots, Math.max(slots, items.size()));
		// 先叠到已有同类
		for (int i = 0; i < limit && !rest.isEmpty(); i++) {
			ItemStack s = get(i);
			if (s.isEmpty() || s.getItem() != rest.getItem()) continue;
			if (!ItemStack.canCombine(s, rest)) continue;
			int can = Math.min(s.getMaxCount() - s.getCount(), rest.getCount());
			if (can <= 0) continue;
			s.increment(can);
			rest.decrement(can);
			dirty = true;
		}
		// 再找空格
		for (int i = 0; i < Math.min(slots, Math.max(slots, items.size())) && !rest.isEmpty(); i++) {
			if (!get(i).isEmpty()) continue;
			set(i, rest.copy());
			rest.setCount(0);
			dirty = true;
		}
		return rest;
	}

	/** 清空某页（仅测试/管理用）。 */
	public void clear() {
		items.clear();
		dirty = true;
	}

	public static Item resolve(String itemId) {
		try {
			Identifier id = Identifier.tryParse(itemId);
			if (id == null || !Registries.ITEM.containsId(id)) return null;
			Item it = Registries.ITEM.get(id);
			return it == Items.AIR ? null : it;
		} catch (Exception e) {
			return null;
		}
	}

	// ---------------------------------------------------------------- 缓存 / 落盘

	public static void flush(UUID uuid) {
		WarehouseData d = CACHE.get(uuid);
		if (d != null && d.dirty) d.save();
	}

	public static void flushAll() {
		for (WarehouseData d : CACHE.values()) {
			try {
				if (d.dirty) d.save();
			} catch (Exception e) {
				RedtaleEconomy.LOGGER.error("[warehouse] 落盘 {} 失败：{}", d.uuid, e.getMessage());
			}
		}
	}

	public static void evict(UUID uuid) {
		WarehouseData d = CACHE.remove(uuid);
		if (d != null && d.dirty) d.save();
	}

	public static void clearCache() {
		CACHE.clear();
	}
}
