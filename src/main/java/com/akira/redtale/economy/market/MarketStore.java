package com.akira.redtale.economy.market;

import com.akira.redtale.economy.RedtaleEconomy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.StringNbtReader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 统一挂单表（R40，spec §43.5）：{@code <data>/market/offers.json}。
 *
 * <p>三种类型共用一张表（R30 委托板 buy / R40 市场 sell / R41 拍卖 auction），
 * 本轮先落 {@code sell}；{@code buy}/{@code auction} 的字段已经留好，
 * 后续接 R41 时只需要加服务端流程，不改文件结构。
 *
 * <p><b>托管（escrow）</b>：物品在挂单那一刻就被序列化成 SNBT 存进 {@code escrow}，
 * 玩家背包里的原物已经销毁。因此这里的数据就是唯一副本 —— 写盘必须原子，
 * 任何「物品没了但单子没写成」都会被 {@link MarketService} 的补偿路径还原。
 */
public final class MarketStore {

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

	private static final List<Entry> ENTRIES = new ArrayList<>();
	private static volatile Path file;

	private MarketStore() {
	}

	public enum Status { OPEN, SOLD, CANCELLED, EXPIRED }

	/** 一条挂单。字段名即 json 键，改名前先确认没有外部工具在读。 */
	public static final class Entry {
		public String id = "";
		/** sell / buy / auction（R40 只写 sell）。 */
		public String type = "sell";
		public String seller = "";
		public String sellerName = "";
		public String itemId = "";
		public int count;
		/** 整单标价（铜）。 */
		public long price;
		/** 挂单时已扣的挂单费。 */
		public long fee;
		public long createdAt;
		/** 0 = 永不过期。 */
		public long expireAt;
		public String status = Status.OPEN.name();
		/** 托管物品的 SNBT；成交/撤单/过期后清空。 */
		public String escrow = "";
		public String soldTo = "";
		public String soldToName = "";
		public long soldAt;
		// ---- R41 拍卖：起拍价复用 price，竞价信息单独存 ----
		/** 当前最高出价；0 = 还没人出价。 */
		public long bid;
		public String bidder = "";
		public String bidderName = "";
		public int bidCount;

		public boolean isOpen() {
			return Status.OPEN.name().equals(status);
		}

		public boolean isAuction() {
			return "auction".equals(type);
		}

		/** 托管物品还原；数据坏了返回空栈（调用方必须判空，绝不能凭空发东西）。 */
		public ItemStack escrowStack() {
			return decode(escrow);
		}
	}

	public static void init(Path dir) {
		file = dir == null ? null : dir.resolve("offers.json");
		synchronized (MarketStore.class) {
			ENTRIES.clear();
			load();
		}
	}

	public static Path file() {
		return file;
	}

	public static synchronized void load() {
		if (file == null || !Files.isRegularFile(file)) return;
		try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			JsonObject o = GSON.fromJson(r, JsonObject.class);
			if (o == null || !o.has("offers") || !o.get("offers").isJsonArray()) return;
			for (var e : o.getAsJsonArray("offers")) {
				if (!e.isJsonObject()) continue;
				Entry en = GSON.fromJson(e, Entry.class);
				if (en != null && en.id != null && !en.id.isEmpty()) ENTRIES.add(en);
			}
		} catch (Exception e) {
			RedtaleEconomy.LOGGER.error("[market] 读取挂单表失败：{}", e.getMessage());
		}
	}

	/** 写盘；返回是否成功（{@code false} 时调用方必须回滚刚才的内存改动）。 */
	public static synchronized boolean save() {
		if (file == null) return false;
		JsonObject o = new JsonObject();
		o.addProperty("_version", 1);
		o.addProperty("updatedAt", System.currentTimeMillis());
		JsonArray arr = new JsonArray();
		for (Entry e : ENTRIES) arr.add(GSON.toJsonTree(e));
		o.add("offers", arr);
		Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
		try {
			Files.createDirectories(file.getParent());
			try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
				w.write(GSON.toJson(o));
			}
			try {
				Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (IOException atomicFail) {
				Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
			}
			return true;
		} catch (IOException e) {
			RedtaleEconomy.LOGGER.error("[market] 写入挂单表失败：{}", e.getMessage());
			return false;
		}
	}

	public static synchronized List<Entry> all() {
		return new ArrayList<>(ENTRIES);
	}

	/** 只读快照：仍处于 OPEN 的单子。 */
	public static synchronized List<Entry> open() {
		List<Entry> out = new ArrayList<>();
		for (Entry e : ENTRIES) {
			if (e.isOpen()) out.add(e);
		}
		return out;
	}

	public static synchronized Entry byId(String id) {
		if (id == null) return null;
		for (Entry e : ENTRIES) {
			if (e.id.equalsIgnoreCase(id)) return e;
		}
		return null;
	}

	public static synchronized List<Entry> bySeller(UUID seller) {
		List<Entry> out = new ArrayList<>();
		if (seller == null) return out;
		String s = seller.toString();
		for (Entry e : ENTRIES) {
			if (s.equals(e.seller) && e.isOpen()) out.add(e);
		}
		return out;
	}

	public static synchronized int openCountOf(UUID seller) {
		return bySeller(seller).size();
	}

	/** 追加并落盘；返回落盘是否成功。失败时条目仍留在内存里，调用方负责回滚。 */
	public static synchronized boolean add(Entry e) {
		ENTRIES.add(e);
		return save();
	}

	/** 回滚用：把刚加进去的条目摘掉。 */
	public static synchronized void removeEntry(Entry e) {
		if (ENTRIES.remove(e)) save();
	}

	/**
	 * 清理已经结束且超过保留期的单子；返回清掉的数量。
	 *
	 * <p>只删「终态」条目：OPEN 的永远保留（过期由 expire sweep 先改成 EXPIRED）。
	 */
	public static synchronized int prune(long now, long keepMs) {
		int before = ENTRIES.size();
		ENTRIES.removeIf(e -> !e.isOpen() && keepMs > 0 && now - e.soldAt > keepMs);
		int removed = before - ENTRIES.size();
		if (removed > 0) save();
		return removed;
	}

	/** 用于测试/管理：清空内存（不动文件）。 */
	static synchronized void clearForTest() {
		ENTRIES.clear();
	}

	// ---------------------------------------------------------------- 物品 ↔ SNBT

	public static String encode(ItemStack stack) {
		if (stack == null || stack.isEmpty()) return "";
		try {
			NbtCompound nbt = new NbtCompound();
			stack.writeNbt(nbt);
			return nbt.toString();
		} catch (Exception e) {
			RedtaleEconomy.LOGGER.error("[market] 序列化托管物品失败：{}", e.getMessage());
			return "";
		}
	}

	public static ItemStack decode(String snbt) {
		if (snbt == null || snbt.isEmpty()) return ItemStack.EMPTY;
		try {
			return ItemStack.fromNbt(StringNbtReader.parse(snbt));
		} catch (Exception e) {
			RedtaleEconomy.LOGGER.error("[market] 托管物品损坏，无法还原：{}", e.getMessage());
			return ItemStack.EMPTY;
		}
	}
}
