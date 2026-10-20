package com.akira.redtale.economy.shop;

import com.akira.redtale.economy.RedtaleEconomy;
import com.akira.redtale.economy.config.ShopConfig;
import com.akira.redtale.economy.config.StorageConfig;
import com.akira.redtale.economy.util.DataRoot;
import com.akira.redtale.economy.util.PathResolver;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;

/**
 * 有限库存的持久化层（R7 收尾）。
 *
 * <p>商品文件里的 {@code stock} 是<b>初值/补货上限</b>，实际剩余存在数据目录的
 * {@code shop/stock.json}，键为 {@code shopId|itemId}。
 * 只有「有限库存」的商品才会被写进来，{@code -1}（无限）永不落盘。
 * 这样重启后库存不会回到初值，避免「重启即补货」的刷物品漏洞。
 */
public final class ShopStock {

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Map<String, Long> STOCK = new HashMap<>();
	private static Path file;
	private static String lastError;

	private ShopStock() {
	}

	public static void init() {
		STOCK.clear();
		file = null;
		lastError = null;
		PathResolver.Resolved r = DataRoot.resolve();
		try {
			Path f = r.path().resolve(ShopConfig.stockFile());
			Files.createDirectories(f.getParent());
			file = f;
			load();
		} catch (Exception e) {
			lastError = e.getMessage();
			RedtaleEconomy.LOGGER.error("[shop] 库存文件不可用：{}", e.getMessage());
		}
	}

	public static String lastError() {
		return lastError;
	}

	public static int size() {
		return STOCK.size();
	}

	/** 读剩余量；{@code initial<0} 表示无限，永远返回 -1。 */
	public static long get(String shopId, String itemId, long initial) {
		if (initial < 0) return -1;
		Long v = STOCK.get(key(shopId, itemId));
		return v == null ? initial : v;
	}

	/** 是否已有覆盖值（管理命令 / 对账用）。 */
	public static boolean has(String shopId, String itemId) {
		return STOCK.containsKey(key(shopId, itemId));
	}

	/** 写入剩余量并立即落盘；{@code value<0} 表示恢复无限（删除覆盖）。 */
	public static synchronized void set(String shopId, String itemId, long value) {
		String k = key(shopId, itemId);
		if (value < 0) {
			STOCK.remove(k);
		} else {
			STOCK.put(k, value);
		}
		save();
	}

	public static synchronized void add(String shopId, String itemId, long delta, long initial) {
		long cur = get(shopId, itemId, initial);
		if (cur < 0) return;
		set(shopId, itemId, Math.max(0, cur + delta));
	}

	/** 管理命令：清空所有覆盖值，回到文件初值。 */
	/** 删除商品后清掉它的库存记录（R16b：避免回收站还原时带上旧库存）。 */
	public static synchronized void forget(String shopId, String itemId) {
		if (STOCK.remove(key(shopId, itemId)) != null) {
			save();
		}
	}

	/** 删除整家商店后清掉它所有库存记录。 */
	public static synchronized void forgetShop(String shopId) {
		String prefix = shopId + "|";
		boolean changed = STOCK.keySet().removeIf(k -> k.startsWith(prefix));
		if (changed) save();
	}

	public static synchronized void resetAll() {
		STOCK.clear();
		save();
	}

	private static String key(String shopId, String itemId) {
		return shopId + "|" + itemId;
	}

	// ---------------------------------------------------------------- 磁盘

	private static void load() {
		if (file == null || !Files.isRegularFile(file)) return;
		try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			JsonObject o = GSON.fromJson(r, JsonObject.class);
			if (o == null) return;
			JsonElement st = o.get("stock");
			if (st == null || !st.isJsonObject()) return;
			for (Map.Entry<String, JsonElement> e : st.getAsJsonObject().entrySet()) {
				try {
					STOCK.put(e.getKey(), e.getValue().getAsLong());
				} catch (Exception ignored) {
					// 单条坏值不影响其它商品
				}
			}
			RedtaleEconomy.LOGGER.info("[shop] 已恢复 {} 条有限库存", STOCK.size());
		} catch (Exception e) {
			RedtaleEconomy.LOGGER.error("[shop] 库存文件解析失败（按文件初值处理）：{}", e.getMessage());
		}
	}

	private static void save() {
		if (file == null) return;
		JsonObject o = new JsonObject();
		o.addProperty("_version", 1);
		JsonObject st = new JsonObject();
		for (Map.Entry<String, Long> e : STOCK.entrySet()) {
			st.addProperty(e.getKey(), e.getValue());
		}
		o.add("stock", st);

		Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
		try {
			Files.createDirectories(file.getParent());
			try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
				GSON.toJson(o, w);
			}
			try {
				Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (Exception atomicFail) {
				Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (Exception e) {
			RedtaleEconomy.LOGGER.error("[shop] 库存落盘失败：{}", e.getMessage());
		}
	}
}
