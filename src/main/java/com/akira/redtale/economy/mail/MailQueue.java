package com.akira.redtale.economy.mail;

import com.akira.redtale.economy.RedtaleEconomy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.StringNbtReader;
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
 * 货币待领取队列 + 物品 fallback 队列（R21）。
 *
 * <p>{@code <storage.root>/mail/<uuid>.json}：{@code pendingMoney} 是纯货币奖励，
 * {@code items} 是信箱满/未装 Contact 时暂存的物品（SNBT，重投成功后立刻删掉）。
 */
public final class MailQueue {

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
	private static final Map<UUID, MailQueue> CACHE = new ConcurrentHashMap<>();
	private static Path dir;

	public final UUID uuid;
	public long pendingMoney;
	public final List<ItemStack> items = new ArrayList<>();
	public boolean dirty;

	private MailQueue(UUID uuid) {
		this.uuid = uuid;
	}

	public static void setDir(Path d) {
		dir = d;
	}

	public static MailQueue of(UUID uuid) {
		MailQueue q = CACHE.get(uuid);
		if (q != null) return q;
		q = read(uuid);
		CACHE.put(uuid, q);
		return q;
	}

	private static MailQueue read(UUID uuid) {
		MailQueue q = new MailQueue(uuid);
		Path f = file(uuid);
		if (f == null || !Files.isRegularFile(f)) return q;
		try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
			JsonObject o = GSON.fromJson(r, JsonObject.class);
			if (o == null) return q;
			q.pendingMoney = o.has("pendingMoney") ? Math.max(0, o.get("pendingMoney").getAsLong()) : 0;
			if (o.has("items") && o.get("items").isJsonArray()) {
				for (var e : o.getAsJsonArray("items")) {
					if (!e.isJsonPrimitive()) continue;
					try {
						NbtCompound nbt = StringNbtReader.parse(e.getAsString());
						DefaultedList<ItemStack> one = DefaultedList.ofSize(1, ItemStack.EMPTY);
						net.minecraft.inventory.Inventories.readNbt(nbt, one);
						if (!one.get(0).isEmpty()) q.items.add(one.get(0));
					} catch (Exception ignored) {
						RedtaleEconomy.LOGGER.warn("[mail] 队列物品解析失败，已跳过一条");
					}
				}
			}
		} catch (Exception e) {
			RedtaleEconomy.LOGGER.error("[mail] 读取 {} 失败：{}", uuid, e.getMessage());
		}
		return q;
	}

	public void save() {
		Path f = file(uuid);
		if (f == null) return;
		JsonObject o = new JsonObject();
		o.addProperty("_version", 1);
		o.addProperty("uuid", uuid.toString());
		o.addProperty("pendingMoney", pendingMoney);
		JsonArray arr = new JsonArray();
		for (ItemStack st : items) {
			DefaultedList<ItemStack> one = DefaultedList.ofSize(1, ItemStack.EMPTY);
			one.set(0, st);
			NbtCompound nbt = new NbtCompound();
			net.minecraft.inventory.Inventories.writeNbt(nbt, one);
			arr.add(nbt.toString());
		}
		o.add("items", arr);
		Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
		try {
			Files.createDirectories(f.getParent());
			try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
				w.write(GSON.toJson(o));
			}
			try {
				Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (IOException atomicFail) {
				Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
			}
			dirty = false;
		} catch (IOException e) {
			RedtaleEconomy.LOGGER.error("[mail] 写入 {} 失败：{}", uuid, e.getMessage());
		}
	}

	private static Path file(UUID uuid) {
		return dir == null ? null : dir.resolve(uuid + ".json");
	}

	public static void evict(UUID uuid) {
		MailQueue q = CACHE.remove(uuid);
		if (q != null && q.dirty) q.save();
	}

	public static void flushAll() {
		for (MailQueue q : CACHE.values()) {
			try {
				if (q.dirty) q.save();
			} catch (Exception ignored) {
				// 单条失败不影响其余
			}
		}
	}

	public static void clearCache() {
		CACHE.clear();
	}
}
