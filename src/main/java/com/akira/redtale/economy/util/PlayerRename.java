package com.akira.redtale.economy.util;

import com.akira.redtale.economy.RedtaleEconomy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * 离线服改名时的数据迁移（R37 / spec §40.3 D9）。
 *
 * <p>离线服的 UUID 由名字推导（{@code OfflinePlayer:<name>}），改名 = 换号。
 * 这里把旧 UUID 名下的数据**复制**到新 UUID 名下：
 * <ul>
 *   <li>文件名 / 目录名里出现旧 UUID（{@code <uuid>.json}、{@code battlepass/<season>/<uuid>.json}）→ 换成新 UUID</li>
 *   <li>文件内容里的旧 UUID 字符串 → 换成新 UUID（UUID 全局唯一，文本替换安全）</li>
 *   <li>JSON 里 {@code name/playerName/posterName/accepterName/ownerName} 等于旧名的 → 换成新名</li>
 *   <li>目标已存在 → 跳过（绝不覆盖）；旧文件一律**保留**（改名可回退）</li>
 * </ul>
 *
 * <p>{@code accounts/_identities.json} 里登记 旧UUID → 新UUID 的别名，旧号残留引用仍能解析到新号。
 */
public final class PlayerRename {

	/** 允许跟着改名的 JSON 字段（只改值恰好等于旧名的）。 */
	private static final Set<String> NAME_KEYS = Set.of(
			"name", "player", "playerName", "posterName", "accepterName", "ownerName");

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

	private PlayerRename() {
	}

	/** 离线模式 UUID（与原版 {@code UUID.nameUUIDFromBytes("OfflinePlayer:"+name)} 一致）。 */
	public static UUID offlineUuid(String name) {
		return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
	}

	/** 规划结果：{@code counts} 是按顶层目录统计的「将复制」文件数。 */
	public record Plan(String oldName, String newName, UUID oldUuid, UUID newUuid,
					   List<Item> items, Map<String, Integer> counts, List<String> skipped, int conflicts) {

		public boolean empty() {
			return items.isEmpty();
		}
	}

	/** 一个待复制项：源 → 目标。 */
	public record Item(Path from, Path to, boolean contentChanges) {
	}

	public record Result(int copied, int failed, int conflicts, boolean aliasWritten) {
	}

	/** 只扫描不写盘。{@code root} 为数据根目录（{@code <world>/redtale-data}）。 */
	public static Plan plan(Path root, String oldName, String newName) {
		UUID from = offlineUuid(oldName);
		UUID to = offlineUuid(newName);
		List<Item> items = new ArrayList<>();
		List<String> skipped = new ArrayList<>();
		Map<String, Integer> counts = new LinkedHashMap<>();
		int conflicts = 0;
		if (root == null || !Files.isDirectory(root)) {
			return new Plan(oldName, newName, from, to, items, counts, List.of("数据目录不存在"), 0);
		}
		String fs = from.toString();
		String ts = to.toString();
		try (Stream<Path> walk = Files.walk(root)) {
			for (Path p : walk.filter(Files::isRegularFile).toList()) {
				Path rel = root.relativize(p);
				String relText = rel.toString().replace('\\', '/');
				boolean nameHit = relText.contains(fs);
				String body;
				try {
					body = Files.readString(p, StandardCharsets.UTF_8);
				} catch (Exception e) {
					skipped.add(relText + "（非文本，跳过）");
					continue;
				}
				boolean bodyHit = body.contains(fs) || body.contains(oldName);
				if (!nameHit && !bodyHit) continue;

				Path to0 = root.resolve(relText.replace(fs, ts));
				if (Files.exists(to0)) {
					conflicts++;
					skipped.add(relText + "（目标已存在）");
					continue;
				}
				items.add(new Item(p, to0, bodyHit));
				String top = relText.contains("/") ? relText.substring(0, relText.indexOf('/')) : "(根)";
				counts.merge(top, 1, Integer::sum);
			}
		} catch (IOException e) {
			skipped.add("遍历失败：" + e.getMessage());
		}
		return new Plan(oldName, newName, from, to, items, counts, skipped, conflicts);
	}

	/** 执行规划：复制 + 改写内容 + 写别名。不删除任何源文件。 */
	public static Result execute(Path root, Plan plan) {
		int copied = 0;
		int failed = 0;
		for (Item it : plan.items()) {
			try {
				Files.createDirectories(it.to().getParent());
				if (it.contentChanges()) {
					String body = rewrite(Files.readString(it.from(), StandardCharsets.UTF_8),
							plan.oldUuid().toString(), plan.newUuid().toString(),
							plan.oldName(), plan.newName());
					Files.writeString(it.to(), body, StandardCharsets.UTF_8);
				} else {
					Files.copy(it.from(), it.to(), StandardCopyOption.COPY_ATTRIBUTES);
				}
				copied++;
			} catch (Exception e) {
				failed++;
				RedtaleEconomy.LOGGER.warn("[rename] 复制失败 {}：{}", it.from(), e.getMessage());
			}
		}
		boolean alias = false;
		if (root != null) {
			Path accounts = root.resolve("accounts");
			alias = com.akira.redtale.economy.storage.IdentityMap
					.put(accounts, plan.oldUuid().toString(), plan.newUuid().toString(), plan.oldName());
		}
		return new Result(copied, failed, plan.conflicts(), alias);
	}

	/**
	 * 迁移清单要展示的内容（spec §40.3：余额/仓库格数/签到天数/战令等级/协会声望）。
	 *
	 * <p>只读文件、不加载模组服务，缺字段一律回落 0，读不到不抛异常。
	 */
	public record Stats(int warehouseSlots, int warehousePurchased, int checkinStreak, int checkinTotal,
						int passXp, boolean passPremium, long guildRep, int dataFiles) {
	}

	public static Stats stats(Path root, UUID uuid) {
		String u = uuid.toString();
		int dataFiles = 0;
		if (root != null && Files.isDirectory(root)) {
			try (Stream<Path> s = Files.walk(root)) {
				dataFiles = (int) s.filter(Files::isRegularFile)
						.filter(p -> root.relativize(p).toString().replace('\\', '/').contains(u)).count();
			} catch (IOException ignored) {
				// 统计失败只影响清单数字
			}
		}
		JsonObject wh = readJson(root == null ? null : root.resolve("warehouse/" + u + ".json"));
		JsonObject ck = readJson(root == null ? null : root.resolve("checkin/" + u + ".json"));
		JsonObject gd = readJson(root == null ? null : root.resolve("guild/" + u + ".json"));
		int xp = 0;
		boolean premium = false;
		if (root != null) {
			Path bp = root.resolve("battlepass");
			if (Files.isDirectory(bp)) {
				try (Stream<Path> s = Files.list(bp)) {
					for (Path season : s.filter(Files::isDirectory).toList()) {
						JsonObject o = readJson(season.resolve(u + ".json"));
						if (o == null) continue;
						xp = Math.max(xp, intOf(o, "xp"));
						premium |= o.has("premium") && o.get("premium").isJsonPrimitive()
								&& o.get("premium").getAsBoolean();
					}
				} catch (IOException ignored) {
					// 同上
				}
			}
		}
		return new Stats(intOf(wh, "slots"), intOf(wh, "purchased"),
				intOf(ck, "streak"), intOf(ck, "totalDays"),
				xp, premium, longOf(gd, "rep"), dataFiles);
	}

	private static JsonObject readJson(Path f) {
		if (f == null || !Files.isRegularFile(f)) return null;
		try {
			JsonElement el = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8));
			return el.isJsonObject() ? el.getAsJsonObject() : null;
		} catch (Exception e) {
			return null;
		}
	}

	private static int intOf(JsonObject o, String key) {
		try {
			return o != null && o.has(key) ? o.get(key).getAsInt() : 0;
		} catch (Exception e) {
			return 0;
		}
	}

	private static long longOf(JsonObject o, String key) {
		try {
			return o != null && o.has(key) ? o.get(key).getAsLong() : 0L;
		} catch (Exception e) {
			return 0L;
		}
	}

	/** 纯函数：UUID 文本替换 + （能解析成 JSON 时）白名单名字字段替换。 */	public static String rewrite(String body, String oldUuid, String newUuid, String oldName, String newName) {
		if (body == null) return "";
		String out = oldUuid == null || oldUuid.isEmpty() ? body : body.replace(oldUuid, newUuid);
		if (oldName == null || oldName.isEmpty() || !out.contains(oldName)) return out;
		try {
			JsonElement el = JsonParser.parseString(out);
			if (!el.isJsonObject() && !el.isJsonArray()) return out;
			if (!renameKeys(el, oldName, newName)) return out;
			return GSON.toJson(el);
		} catch (Exception e) {
			return out; // 不是 JSON（jsonl 之类）就只做 UUID 替换
		}
	}

	private static boolean renameKeys(JsonElement el, String oldName, String newName) {
		boolean changed = false;
		if (el.isJsonObject()) {
			JsonObject o = el.getAsJsonObject();
			for (Map.Entry<String, JsonElement> e : new ArrayList<>(o.entrySet())) {
				String k = e.getKey();
				JsonElement v = e.getValue();
				if (NAME_KEYS.contains(k) && v.isJsonPrimitive() && v.getAsJsonPrimitive().isString()
						&& oldName.equals(v.getAsString())) {
					o.addProperty(k, newName);
					changed = true;
				} else if (v.isJsonObject() || v.isJsonArray()) {
					changed |= renameKeys(v, oldName, newName);
				}
			}
		} else if (el.isJsonArray()) {
			JsonArray a = el.getAsJsonArray();
			for (JsonElement v : a) {
				if (v.isJsonObject() || v.isJsonArray()) changed |= renameKeys(v, oldName, newName);
			}
		}
		return changed;
	}
}
