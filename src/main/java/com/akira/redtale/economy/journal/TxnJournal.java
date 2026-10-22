package com.akira.redtale.economy.journal;

import com.akira.redtale.economy.RedtaleEconomy;
import com.akira.redtale.economy.config.StorageConfig;
import com.akira.redtale.economy.mail.MailService;
import com.akira.redtale.economy.util.DataRoot;
import com.akira.redtale.economy.util.PathResolver;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Identifier;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * 写前日志（E2）：把「扣物品 / 记进度 / 入账」这类多步操作变成可恢复的一次事务。
 *
 * <p>问题：任务缴纳是「先扣背包+仓库，再写任务进度」，中间崩溃（杀进程 / OOM / 断电）
 * 就变成「物品没了、任务没完成」；商店出售同理（先扣物品再入账）。
 *
 * <p>做法：动手之前先把「打算做什么」同步落盘（{@code force(true)}，不接受异步刷盘），
 * 每完成一步改写 state，全部成功才删文件。启动时 {@link #recover} 扫残留：
 * <ul>
 *   <li>{@code prepare} —— 还没动任何东西，但也不能 100% 确定（窗口极小）⇒ 保留为
 *       {@code .orphan} 供人工核对，<b>不自动补</b>（自动补会造成双发）</li>
 *   <li>{@code taken}（物品已扣、奖励未发）⇒ 把物品按邮件退回</li>
 *   <li>{@code charged}（钱已扣、货未发）⇒ 把钱按邮件退回</li>
 *   <li>{@code paid}/{@code delivered}/{@code claimed}（业务已完成、只差收尾）⇒ 直接清理</li>
 * </ul>
 *
 * <p>注意：state 变更本身也是落盘操作，所以「扣完物品 → 写 taken」之间有毫秒级窗口；
 * 该窗口内崩溃会被当成 {@code prepare} 处理（不自动补），这是保守侧。
 */
public final class TxnJournal {

	public static final String DIR = "journal";

	public static final String TASK_SUBMIT = "task.submit";
	public static final String SHOP_SELL = "shop.sell";
	public static final String SHOP_BUY = "shop.buy";
	/** R129：委托交付（物品已销毁、赏金未发 ⇒ 重启后退物品）。 */
	public static final String BOARD_DELIVER = "board.deliver";

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
	private static final DateTimeFormatterHolder TS = new DateTimeFormatterHolder();

	private static Path dir;
	private static volatile boolean ready;

	private TxnJournal() {
	}

	public static void init() {
		PathResolver.Resolved r = DataRoot.resolve();
		try {
			dir = r.path().resolve(DIR);
			Files.createDirectories(dir);
			ready = true;
		} catch (Exception e) {
			dir = null;
			ready = false;
			RedtaleEconomy.LOGGER.error("[journal] 目录不可用，原子性保护降级为普通流程：{}", e.getMessage());
		}
	}

	public static boolean ready() {
		return ready;
	}

	public static int pendingCount() {
		if (!ready) return 0;
		try (Stream<Path> s = Files.list(dir)) {
			return (int) s.filter(p -> p.getFileName().toString().endsWith(".json")).count();
		} catch (Exception e) {
			return 0;
		}
	}

	// ---------------------------------------------------------------- 事务句柄

	/** 一次事务。{@link #commit()} 之前崩溃 = 下次启动会被 {@link #recover} 处理。 */
	public static final class Txn {
		private final Path file;
		private final JsonObject root;

		private Txn(Path file, JsonObject root) {
			this.file = file;
			this.root = root;
		}

		public String id() {
			return root.has("id") ? root.get("id").getAsString() : "";
		}

		public String state() {
			return root.has("state") ? root.get("state").getAsString() : "";
		}

		public JsonObject payload() {
			return root.has("payload") && root.get("payload").isJsonObject()
					? root.getAsJsonObject("payload") : new JsonObject();
		}

		/** 推进状态并<b>同步</b>落盘。 */
		public void state(String s) {
			if (file == null) return;
			root.addProperty("state", s);
			root.addProperty("stateAt", TS.now());
			write(file, root);
		}

		/** 只改 payload（用于逐条标记 taken），同样同步落盘。 */
		public void flush() {
			if (file == null) return;
			write(file, root);
		}

		/** 全部完成：删掉日志文件。 */
		public void commit() {
			if (file == null) return;
			try {
				Files.deleteIfExists(file);
			} catch (Exception e) {
				RedtaleEconomy.LOGGER.warn("[journal] 清理 {} 失败（下次启动会按已完成处理）：{}",
						file.getFileName(), e.getMessage());
			}
		}

		/** 明确什么都没做（连物品都没扣）：直接丢弃。 */
		public void dismiss() {
			commit();
		}
	}

	private static final Txn NOOP = new Txn(null, new JsonObject());

	/** 开一个新事务。日志目录不可用时返回空事务（调用方无需判空）。 */
	public static Txn begin(String kind, UUID uuid, JsonObject payload) {
		if (!ready) return NOOP;
		String id = kind.replace('.', '-') + "-" + uuid.toString().substring(0, 8) + "-" + TS.stamp();
		JsonObject root = new JsonObject();
		root.addProperty("v", 1);
		root.addProperty("id", id);
		root.addProperty("kind", kind);
		root.addProperty("uuid", uuid.toString());
		root.addProperty("at", TS.now());
		root.addProperty("state", "prepare");
		root.add("payload", payload == null ? new JsonObject() : payload);
		Path f = dir.resolve(id + ".json");
		if (!write(f, root)) return NOOP;
		return new Txn(f, root);
	}

	// ---------------------------------------------------------------- 崩溃恢复

	/**
	 * 启动时调用一次（服务端主线程）。返回处理条数。
	 *
	 * <p>用邮件/待领取队列补发，因此<b>玩家不需要在线</b>。
	 */
	public static synchronized int recover(MinecraftServer server) {
		if (!ready || server == null) return 0;
		List<Path> files = new ArrayList<>();
		try (Stream<Path> s = Files.list(dir)) {
			s.filter(Files::isRegularFile)
					.filter(p -> p.getFileName().toString().endsWith(".json"))
					.forEach(files::add);
		} catch (Exception e) {
			RedtaleEconomy.LOGGER.error("[journal] 扫描 {} 失败：{}", dir, e.getMessage());
			return 0;
		}
		if (files.isEmpty()) return 0;

		int handled = 0;
		for (Path f : files) {
			JsonObject root;
			try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
				root = GSON.fromJson(r, JsonObject.class);
			} catch (Exception e) {
				RedtaleEconomy.LOGGER.error("[journal] 解析 {} 失败（保留待人工）：{}",
						f.getFileName(), e.getMessage());
				continue;
			}
			if (root == null) continue;
			String kind = root.has("kind") ? root.get("kind").getAsString() : "";
			String state = root.has("state") ? root.get("state").getAsString() : "prepare";
			UUID uuid = parseUuid(root);
			JsonObject payload = root.has("payload") && root.get("payload").isJsonObject()
					? root.getAsJsonObject("payload") : new JsonObject();
			try {
				if (resume(server, kind, state, uuid, payload)) {
					handled++;
					Files.deleteIfExists(f);
				} else {
					// prepare 阶段无法判定是否扣了东西：改名留痕，不自动补（避免双发）
					Path keep = f.resolveSibling(f.getFileName().toString().replace(".json", ".orphan.json"));
					try {
						Files.move(f, keep, StandardCopyOption.REPLACE_EXISTING);
					} catch (Exception ignored) {
						// 改名失败就留在原地，下次启动再报一次
					}
				}
			} catch (Exception e) {
				RedtaleEconomy.LOGGER.error("[journal] 恢复 {} 失败，保留待下次：{}",
						f.getFileName(), e.getMessage());
			}
		}
		if (handled > 0) {
			RedtaleEconomy.LOGGER.warn("[journal] 已恢复 {} 个未完成事务（补发走邮件/待领取队列）", handled);
		}
		return handled;
	}

	/** @return true=已处理（文件可删） */
	private static boolean resume(MinecraftServer server, String kind, String state,
			UUID uuid, JsonObject payload) {
		boolean done = switch (state) {
			case "taken" -> TASK_SUBMIT.equals(kind) || SHOP_SELL.equals(kind) || BOARD_DELIVER.equals(kind);
			case "charged" -> SHOP_BUY.equals(kind);
			default -> false;
		};
		if (done) {
			switch (kind) {
				case TASK_SUBMIT -> {
					RedtaleEconomy.LOGGER.warn("[journal] 任务缴纳中断（物品已扣、奖励未发）→ 退还物品：{}", uuid);
					refundItems(server, uuid, payloadItemArray(payload, "items"), "task-submit-rollback");
				}
				case BOARD_DELIVER -> {
					int count = (int) num(payload, "count", 0);
					String itemId = str(payload, "itemId", "");
					RedtaleEconomy.LOGGER.warn("[journal] 委托交付中断（物品已销毁、赏金未发）→ 退还 {} x{} 给 {}",
							itemId, count, uuid);
					refundItems(server, uuid, itemArray(itemId, count), "board-deliver-rollback");
				}
				case SHOP_SELL -> {
					int count = (int) num(payload, "count", 0);
					String itemId = str(payload, "itemId", "");
					RedtaleEconomy.LOGGER.warn("[journal] 商店出售中断（物品已扣、未入账）→ 退还 {} x{} 给 {}",
							itemId, count, uuid);
					refundItems(server, uuid, itemArray(itemId, count), "shop-sell-rollback");
				}
				case SHOP_BUY -> {
					long price = num(payload, "price", 0);
					RedtaleEconomy.LOGGER.warn("[journal] 商店购买中断（钱已扣、货未发）→ 退还 {} 给 {}",
							price, uuid);
					if (price > 0) MailService.sendMoney(uuid, price, "shop-buy-rollback");
				}
				default -> {
				}
			}
			return true;
		}
		// 业务已完成、只差删文件（崩溃点在所有写入之后）
		if ("paid".equals(state) || "delivered".equals(state) || "claimed".equals(state)) {
			RedtaleEconomy.LOGGER.info("[journal] 事务 {} 已到终态 {}，仅清理日志（{}）", kind, state, uuid);
			return true;
		}
		if ("prepare".equals(state)) {
			// 窗口小时物品很可能还没扣，自动补会造成双发 ⇒ 只留痕
			RedtaleEconomy.LOGGER.error("[journal] 事务 {} 停在 prepare（{}），无法判定是否已扣物品/钱，"
					+ "已改名为 .orphan.json 留人工核对（不自动补，避免双发）：{}", kind, uuid, payload);
			return false;
		}
		return true;   // claimed / paid / delivered / 未知状态：业务已完成，清掉
	}

	private static JsonArray itemArray(String itemId, int count) {
		JsonArray arr = new JsonArray();
		if (itemId != null && !itemId.isBlank() && count > 0) {
			JsonObject o = new JsonObject();
			o.addProperty("id", itemId);
			o.addProperty("count", count);
			arr.add(o);
		}
		return arr;
	}

	private static JsonArray payloadItemArray(JsonObject payload, String key) {
		if (payload.has(key) && payload.get(key).isJsonArray()) return payload.getAsJsonArray(key);
		return new JsonArray();
	}

	private static void refundItems(MinecraftServer server, UUID uuid, JsonArray items, String reason) {
		if (uuid == null || items == null) return;
		for (var el : items) {
			if (!el.isJsonObject()) continue;
			JsonObject o = el.getAsJsonObject();
			// prepare 阶段没扣到的条目 taken=false，跳过（防止多退）
			if (o.has("taken") && !o.get("taken").getAsBoolean()) continue;
			String id = str(o, "id", "");
			int count = (int) num(o, "count", 0);
			if (count <= 0) continue;
			Item item = item(id);
			if (item == null) {
				RedtaleEconomy.LOGGER.error("[journal] 退还失败，物品已不存在：{} x{}（{}）", id, count, uuid);
				continue;
			}
			// 按最大堆叠切分：旧实现 Math.min(count, 64*27) 会把超出部分直接吞掉（大额交易退不全）
			int per = Math.max(1, new ItemStack(item).getMaxCount());
			int remaining = count;
			boolean ok = true;
			while (remaining > 0) {
				int take = Math.min(remaining, per);
				boolean accepted;
				try {
					accepted = MailService.sendItemOrQueue(server, uuid, new ItemStack(item, take), reason);
				} catch (Exception e) {
					RedtaleEconomy.LOGGER.error("[journal] 邮件退还失败：{}", e.getMessage());
					accepted = false;
				}
				if (!accepted) {
					RedtaleEconomy.LOGGER.error("[journal] 退还 {} x{} 给 {} 未成功，需人工补发（{}，剩余 {}）",
							id, take, uuid, reason, remaining);
					ok = false;
					break;
				}
				remaining -= take;
			}
			if (ok) {
				RedtaleEconomy.LOGGER.warn("[journal] 已退回 {} x{}（{}）→ 待领取队列/信箱", id, count, uuid);
			}
		}
	}

	private static Item item(String id) {
		try {
			Identifier ident = Identifier.tryParse(id);
			return ident == null ? null : Registries.ITEM.get(ident);
		} catch (Exception e) {
			return null;
		}
	}

	private static UUID parseUuid(JsonObject o) {
		try {
			return UUID.fromString(str(o, "uuid", ""));
		} catch (Exception e) {
			return null;
		}
	}

	private static String str(JsonObject o, String k, String def) {
		var e = o.get(k);
		return e != null && e.isJsonPrimitive() ? e.getAsString() : def;
	}

	private static long num(JsonObject o, String k, long def) {
		var e = o.get(k);
		return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber() ? e.getAsLong() : def;
	}

	// ---------------------------------------------------------------- 落盘

	/** 同步写 + fsync；失败返回 false（调用方降级为普通流程）。 */
	private static boolean write(Path file, JsonObject root) {
		Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
		try {
			byte[] bytes = GSON.toJson(root).getBytes(StandardCharsets.UTF_8);
			Files.write(tmp, bytes, StandardOpenOption.CREATE,
					StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
			try (var ch = java.nio.channels.FileChannel.open(tmp, StandardOpenOption.WRITE)) {
				ch.force(true);
			}
			try {
				Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (Exception atomicFail) {
				Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
			}
			return true;
		} catch (Exception e) {
			RedtaleEconomy.LOGGER.error("[journal] 写 {} 失败：{}", file.getFileName(), e.getMessage());
			return false;
		}
	}

	/** id 里需要时间戳：精确到毫秒 + 序号，避免同一毫秒两次操作撞名。 */
	private static final class DateTimeFormatterHolder {
		private final java.time.format.DateTimeFormatter fmt =
				java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");
		private int seq;

		String now() {
			return com.akira.redtale.economy.util.TimeUtil.nowIso();
		}

		synchronized String stamp() {
			return java.time.LocalDateTime.now(com.akira.redtale.economy.util.TimeUtil.zone()).format(fmt)
					+ "-" + (seq++ & 0xFFFF);
		}
	}
}
