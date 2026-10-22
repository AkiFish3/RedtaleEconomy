package com.akira.redtale.economy.storage;

import com.akira.redtale.economy.RedtaleEconomy;
import com.akira.redtale.economy.util.DataRoot;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 离线转账挂账（Aki 2026-10-23）。
 *
 * <p>为什么需要挂账而不是直接 {@code BalanceService.add}：
 * Numismatic 的余额存在玩家的 CCA 组件里，只有在线实体才有组件
 * （{@code NumismaticBridge.resolvePlayer} 只查在线玩家）⇒ 对离线 UUID 的
 * {@code get} 返回 -1、{@code modify} 静默无效。如果直接入账，结果是
 * <b>发送方扣了钱、收款方一分没收到</b>，且没有任何报错。
 *
 * <p>所以：离线收款一律落到这份挂账表，玩家 <b>上线时</b>（此时组件一定可写）
 * 由 {@code PayService.claim} 逐笔入账并写流水。内部余额后端其实支持离线写，
 * 但也统一走挂账 —— 两条后端行为一致，测试与客服口径都只有一种。
 *
 * <p>读写频率很低（只在离线转账与上线领取时），但仍按硬坑 #52 的要求：
 * <b>内存驻留 + 变更即回写</b>，不在 tick / 高频事件里读盘。
 */
public final class PendingPay {

	/** 一笔待领取的离线转账。 */
	public record Entry(UUID from, String fromName, String toName, long amount, long time) {
	}

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
	private static final String FILE = "pay_pending.json";
	private static final int MAX_PER_PLAYER = 200;

	private static final Map<UUID, List<Entry>> PENDING = new ConcurrentHashMap<>();
	private static Path file;
	private static boolean loaded;

	private PendingPay() {
	}

	/** 重新解析数据目录并重读（加载期 / world ready 重绑 / reload 都调这里）。 */
	public static synchronized void resolve() {
		try {
			file = DataRoot.path().resolve(FILE);
		} catch (Exception e) {
			file = null;
			RedtaleEconomy.LOGGER.error("[pay] 挂账目录不可用：{}", e.getMessage());
		}
		loaded = false;
		PENDING.clear();
		load();
	}

	private static void load() {
		if (loaded) return;
		loaded = true;
		if (file == null || !Files.isRegularFile(file)) return;
		try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			JsonObject root = GSON.fromJson(r, JsonObject.class);
			JsonArray arr = root != null && root.has("entries") && root.get("entries").isJsonArray()
					? root.getAsJsonArray("entries") : new JsonArray();
			int n = 0;
			for (var el : arr) {
				if (!el.isJsonObject()) continue;
				JsonObject o = el.getAsJsonObject();
				try {
					UUID to = UUID.fromString(o.get("to").getAsString());
					UUID from = UUID.fromString(o.get("from").getAsString());
					long amount = o.get("amount").getAsLong();
					if (amount <= 0) continue;
					String toName = o.has("toName") ? o.get("toName").getAsString() : "";
					String fromName = o.has("fromName") ? o.get("fromName").getAsString() : "";
					long time = o.has("time") ? o.get("time").getAsLong() : 0L;
					PENDING.computeIfAbsent(to, k -> new ArrayList<>())
							.add(new Entry(from, fromName, toName, amount, time));
					n++;
				} catch (Exception ignored) {
					// 单条坏数据不影响其余
				}
			}
			if (n > 0) RedtaleEconomy.LOGGER.info("[pay] 已载入 {} 笔离线转账挂账", n);
		} catch (Exception e) {
			RedtaleEconomy.LOGGER.error("[pay] 读取离线转账挂账失败：{}", e.getMessage());
		}
	}

	/** 记一笔待领取（同时落盘）。 */
	public static synchronized void add(UUID to, String toName, UUID from, String fromName, long amount) {
		if (amount <= 0) return;
		load();
		List<Entry> list = PENDING.computeIfAbsent(to, k -> new ArrayList<>());
		if (list.size() >= MAX_PER_PLAYER) {
			RedtaleEconomy.LOGGER.warn("[pay] {} 的挂账已满 {} 笔，丢弃最早一笔", to, MAX_PER_PLAYER);
			list.remove(0);
		}
		list.add(new Entry(from, fromName, toName, amount, System.currentTimeMillis()));
		save();
	}

	/**
	 * 取走某玩家的全部挂账（调用方负责真正入账 + 发消息）。
	 *
	 * <p>★先移除再返回★：即使后续入账抛异常也不会重复发放；
	 * 宁可少发一笔要人工补，也不能每次上线都重复加钱。
	 */
	public static synchronized List<Entry> takeAll(UUID to) {
		load();
		List<Entry> list = PENDING.remove(to);
		if (list == null || list.isEmpty()) return List.of();
		save();
		return List.copyOf(list);
	}

	/** 该玩家还有几笔没领（UI / 查询用，不读盘）。 */
	public static int count(UUID to) {
		load();
		List<Entry> list = PENDING.get(to);
		return list == null ? 0 : list.size();
	}

	private static void save() {
		if (file == null) return;
		JsonObject root = new JsonObject();
		root.addProperty("_version", 1);
		JsonArray arr = new JsonArray();
		PENDING.forEach((to, list) -> {
			for (Entry e : list) {
				JsonObject o = new JsonObject();
				o.addProperty("to", to.toString());
				o.addProperty("toName", e.toName());
				o.addProperty("from", e.from() == null ? "" : e.from().toString());
				o.addProperty("fromName", e.fromName());
				o.addProperty("amount", e.amount());
				o.addProperty("time", e.time());
				arr.add(o);
			}
		});
		root.add("entries", arr);
		try {
			Files.createDirectories(file.getParent());
			Path tmp = file.resolveSibling(FILE + ".tmp");
			try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
				GSON.toJson(root, w);
			}
			Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
		} catch (Exception e) {
			RedtaleEconomy.LOGGER.error("[pay] 写离线转账挂账失败：{}", e.getMessage());
		}
	}
}
