package com.akira.redtale.economy.shop;

import com.akira.redtale.economy.RedtaleEconomy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 补偿队列（Q2）。
 *
 * <p>购买流程是「预占余额 → 发货 → 提交库存/限购」，任一步抛异常都会走这里：
 * 先把钱退回去，再把这一笔记进 {@code <root>/shop/compensations.jsonl}，
 * 事后可以按文件逐笔核对，而不是只在日志里留一行容易翻不到的错误。
 *
 * <p>写文件失败不会影响退款本身（退款在调用前已执行），这里只做审计。
 */
public final class CompensationQueue {

	private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
	private static final Queue<Entry> PENDING = new ConcurrentLinkedQueue<>();
	private static Path file;

	/**
	 * @param at     时间（yyyy-MM-dd HH:mm:ss）
	 * @param player 玩家 UUID
	 * @param shopId 商店
	 * @param itemId 物品
	 * @param count  数量
	 * @param amount 金额（已退回的数）
	 * @param reason 失败原因
	 */
	public record Entry(String at, String player, String shopId, String itemId,
			int count, long amount, String reason) {
	}

	private CompensationQueue() {
	}

	public static void init(Path dataRoot) {
		try {
			Path dir = dataRoot.resolve("shop");
			Files.createDirectories(dir);
			file = dir.resolve("compensations.jsonl");
			PENDING.clear();
		} catch (Exception e) {
			file = null;
			RedtaleEconomy.LOGGER.error("[shop] 补偿队列目录不可用：{}", e.getMessage());
		}
	}

	/** 记一笔补偿：先入内存队列，再追加落盘。 */
	public static void enqueue(Entry e) {
		if (e == null) return;
		PENDING.add(e);
		append(e);
	}

	/** 待人工核对的条数（drain 前）。 */
	public static int pending() {
		return PENDING.size();
	}

	public static List<Entry> drain() {
		List<Entry> out = new ArrayList<>();
		Entry e;
		while ((e = PENDING.poll()) != null) out.add(e);
		return out;
	}

	/** 单行 JSON（不带换行）——纯函数，便于单测钉住字段与转义。 */
	public static String line(Entry e) {
		return GSON.toJson(e);
	}

	private static void append(Entry e) {
		if (file == null) return;
		try {
			Files.writeString(file, line(e) + "\n", StandardCharsets.UTF_8,
					StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		} catch (Exception ex) {
			RedtaleEconomy.LOGGER.error("[shop] 补偿记录落盘失败（内存队列仍保留）：{}", ex.getMessage());
		}
	}

	/** 供自检/命令用：当前落盘路径。 */
	public static Path file() {
		return file;
	}
}
