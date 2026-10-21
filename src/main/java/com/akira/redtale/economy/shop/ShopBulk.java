package com.akira.redtale.economy.shop;

import com.akira.redtale.economy.RedtaleEconomy;
import com.akira.redtale.economy.config.ConfigManager;
import com.akira.redtale.economy.config.ShopConfig;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * T10：商店批量导入/导出（搬 2803 件用）。
 *
 * <p>与 {@link ShopBulkJson} 分工：本类只管读写盘、找归属文件、reload 与审计日志；
 * 校验与合并是纯函数（可单测）。
 *
 * <p>写盘纪律（与 {@link ShopCatalog#updateItem} 一致）：
 * <ul>
 *   <li>先备份到 {@code backup/<yyyyMMdd-HHmmss>/shop/shops/<file>}</li>
 *   <li>写 {@code .tmp} 再 {@code ATOMIC_MOVE}（失败退回普通 move）</li>
 *   <li>全部写完后只 {@code reload()} 一次，最后 {@code ConfigManager.logEdit} 留审计</li>
 * </ul>
 *
 * <p>{@code dry} 模式只算计数、不写盘，用来先看「会改几件」。
 */
public final class ShopBulk {

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
	private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

	/** 导出结果。 */
	public record ExportReport(Path file, int shops, int items, List<String> errors) {
	}

	/** 导入结果。{@code dryRun=true} 时 {@code files} 为空。 */
	public record ImportReport(boolean dryRun, boolean replaceItems, int shopsNew, int shopsUpdated,
			int itemsNew, int itemsUpdated, int itemsUnchanged, int itemsRemoved,
			List<String> errors, List<Path> files) {
	}

	private ShopBulk() {
	}

	// ---------------------------------------------------------------- 导出

	/** 把内存里的全部商店导成一份批量文件。{@code out} 相对路径按数据根目录解析。 */
	public static ExportReport exportTo(String out) {
		Path file = resolve(out);
		List<String> errors = new ArrayList<>();
		JsonObject root = new JsonObject();
		root.addProperty("_format", ShopBulkJson.FORMAT);
		root.addProperty("_version", ShopBulkJson.VERSION);
		root.addProperty("exportedAt", LocalDateTime.now().withNano(0).toString());

		List<String> ids = new ArrayList<>(ShopCatalog.shopIds());
		ids.sort(String::compareTo);
		JsonObject shops = new JsonObject();
		int itemCount = 0;
		for (String id : ids) {
			ShopCatalog.ShopDef def = ShopCatalog.shop(id);
			if (def == null) continue;
			JsonObject shop = new JsonObject();
			shop.addProperty("displayName", def.displayName());
			shop.addProperty("icon", def.icon());
			shop.addProperty("requirePremium", def.requirePremium());
			JsonArray cats = new JsonArray();
			for (String c : def.declaredCategories()) {
				if (c != null && !c.isBlank()) cats.add(c);
			}
			shop.add("categories", cats);
			JsonArray items = new JsonArray();
			for (ShopCatalog.ItemDef it : def.items()) {
				JsonObject o = new JsonObject();
				o.addProperty("id", it.id());
				o.addProperty("category", it.category() == null ? "" : it.category());
				o.addProperty("buy", it.buy());
				o.addProperty("sell", it.sell());
				o.addProperty("stock", it.stockInit());
				o.addProperty("dailyBuyLimit", it.dailyBuyLimit());
				o.addProperty("enabled", it.enabled());
				if (it.name() != null && !it.name().isBlank()) o.addProperty("name", it.name());
				items.add(o);
				itemCount++;
			}
			shop.add("items", items);
			shops.add(id, shop);
		}
		root.addProperty("shopCount", shops.size());
		root.addProperty("itemCount", itemCount);
		root.add("shops", shops);

		if (shops.size() == 0) errors.add("当前没有任何商店（shop/shops 为空？）");
		if (!writeJson(file, root)) errors.add("写入失败：" + file);
		else {
			ConfigManager.logEdit("shop/_export", "admin export", file + " shops=" + shops.size()
					+ " items=" + itemCount);
		}
		return new ExportReport(file, shops.size(), itemCount, errors);
	}

	// ---------------------------------------------------------------- 导入

	/**
	 * 导入一份批量文件。
	 *
	 * @param in           文件路径（相对数据根目录解析）
	 * @param replaceItems true = 文件里没有的商品从该店移除（写前已备份）
	 * @param dryRun       true = 只报计数，不写盘
	 */
	public static ImportReport importFrom(String in, boolean replaceItems, boolean dryRun) {
		Path file = resolve(in);
		List<String> errors = new ArrayList<>();
		if (!Files.isRegularFile(file)) {
			errors.add("文件不存在：" + file);
			return new ImportReport(dryRun, replaceItems, 0, 0, 0, 0, 0, 0, errors, List.of());
		}
		JsonObject doc;
		try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			doc = GSON.fromJson(r, JsonObject.class);
		} catch (Exception e) {
			errors.add("解析失败：" + e.getMessage());
			return new ImportReport(dryRun, replaceItems, 0, 0, 0, 0, 0, 0, errors, List.of());
		}
		ShopBulkJson.Limits lim = new ShopBulkJson.Limits(ShopConfig.minPrice(), ShopConfig.maxPrice(),
				ShopConfig.enforceSellBelowBuy() ? ShopConfig.minSellMarginPercent() : 0);
		ShopBulkJson.Parsed parsed = ShopBulkJson.parse(doc, lim);
		errors.addAll(parsed.errors());

		// 目标文件 → 已读入的根对象（同文件多店只写一次，避免互相覆盖）
		Map<Path, JsonObject> plans = new LinkedHashMap<>();
		int shopsNew = 0;
		int shopsUpdated = 0;
		int itemsNew = 0;
		int itemsUpdated = 0;
		int itemsUnchanged = 0;
		int itemsRemoved = 0;
		List<String> shopIds = new ArrayList<>(parsed.shops().keySet());
		shopIds.sort(String::compareTo);
		for (String shopId : shopIds) {
			JsonObject incoming = parsed.shops().getAsJsonObject(shopId);
			ShopCatalog.ShopDef existing = ShopCatalog.shop(shopId);
			Path target = (existing != null && existing.file() != null) ? existing.file()
					: ConfigManager.get().baseDir().resolve(ShopCatalog.SHOPS_DIR)
							.resolve(shopId + ".json");
			JsonObject root = plans.get(target);
			if (root == null) {
				root = readRoot(target);
				plans.put(target, root);
			}
			if (existing == null) shopsNew++;
			else shopsUpdated++;
			ShopBulkJson.Merged m = ShopBulkJson.merge(root, shopId, incoming, replaceItems);
			itemsNew += m.itemsNew();
			itemsUpdated += m.itemsUpdated();
			itemsUnchanged += m.itemsUnchanged();
			itemsRemoved += m.itemsRemoved();
		}

		if (dryRun) {
			return new ImportReport(true, replaceItems, shopsNew, shopsUpdated, itemsNew, itemsUpdated,
					itemsUnchanged, itemsRemoved, errors, List.of());
		}
		List<Path> written = new ArrayList<>();
		for (Map.Entry<Path, JsonObject> e : plans.entrySet()) {
			if (!writeJson(e.getKey(), e.getValue())) {
				errors.add("写入失败：" + e.getKey());
				continue;
			}
			written.add(e.getKey());
			ConfigManager.logEdit("shop/" + e.getKey().getFileName(), "admin import",
					"from=" + file.getFileName() + " replace=" + replaceItems);
		}
		if (!written.isEmpty()) {
			ShopCatalog.reload();
			RedtaleEconomy.LOGGER.info("[shop] 批量导入完成：{} 个文件，新增 {} 件 / 更新 {} 件 / 移除 {} 件",
					written.size(), itemsNew, itemsUpdated, itemsRemoved);
		}
		return new ImportReport(false, replaceItems, shopsNew, shopsUpdated, itemsNew, itemsUpdated,
				itemsUnchanged, itemsRemoved, errors, written);
	}

	// ---------------------------------------------------------------- 盘

	/** 相对路径按数据根目录（{@code <world>/redtale-data} 或 {@code config/redtale}）解析。 */
	public static Path resolve(String p) {
		Path path = Path.of(p.replace('\\', '/'));
		return path.isAbsolute() ? path : ConfigManager.get().baseDir().resolve(path).normalize();
	}

	private static JsonObject readRoot(Path file) {
		if (Files.isRegularFile(file)) {
			try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
				JsonObject root = GSON.fromJson(r, JsonObject.class);
				if (root != null) return root;
			} catch (Exception e) {
				RedtaleEconomy.LOGGER.warn("[shop] 读取 {} 失败，将按新文件处理：{}",
						file.getFileName(), e.getMessage());
			}
		}
		JsonObject root = new JsonObject();
		root.addProperty("_version", 1);
		root.add("shops", new JsonObject());
		return root;
	}

	/** 备份 + tmp + 原子替换（{@link ShopCatalog#writeWithBackup} 的同款纪律）。 */
	static boolean writeJson(Path file, JsonObject root) {
		Path base = ConfigManager.get().baseDir();
		try {
			Files.createDirectories(file.getParent());
			if (Files.isRegularFile(file) && file.startsWith(base)) {
				Path backup = base.resolve("backup").resolve(LocalDateTime.now().format(STAMP))
						.resolve(base.relativize(file));
				Files.createDirectories(backup.getParent());
				Files.copy(file, backup, StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (Exception e) {
			RedtaleEconomy.LOGGER.warn("[shop] 备份 {} 失败，继续写：{}", file.getFileName(), e.getMessage());
		}
		Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
		try {
			try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
				GSON.toJson(root, w);
			}
			try {
				Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (Exception atomicFail) {
				Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
			}
			return true;
		} catch (Exception e) {
			RedtaleEconomy.LOGGER.error("[shop] 写入 {} 失败：{}", file, e.getMessage());
			try {
				Files.deleteIfExists(tmp);
			} catch (Exception ignored) {
				// tmp 留在磁盘不影响读取
			}
			return false;
		}
	}

	/** 数据根目录下已有的批量文件（命令补全用）。 */
	public static List<String> existingBulkFiles() {
		List<String> out = new ArrayList<>();
		Path base = ConfigManager.get().baseDir();
		Path dir = base.resolve("shop");
		if (!Files.isDirectory(dir)) return out;
		try (Stream<Path> s = Files.list(dir)) {
			s.filter(Files::isRegularFile)
					.filter(p -> p.getFileName().toString().endsWith(".json"))
					.forEach(p -> out.add(base.relativize(p).toString().replace('\\', '/')));
		} catch (Exception ignored) {
			// 补全失败不影响命令
		}
		return out;
	}

	/** 导出文件是否可写（父目录能建、不是目录）。 */
	public static boolean writable(Path file) {
		try {
			Files.createDirectories(file.getParent());
			return !Files.isDirectory(file);
		} catch (Exception e) {
			return false;
		}
	}
}
