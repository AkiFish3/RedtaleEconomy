package com.akira.redtale.economy.storage;

import com.akira.redtale.economy.RedtaleEconomy;
import com.akira.redtale.economy.config.StorageConfig;
import com.akira.redtale.economy.util.DataRoot;
import com.akira.redtale.economy.util.PathResolver;
import com.akira.redtale.economy.util.TimeUtil;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.UUID;

/**
 * 流水（spec v0.6 §13.2）：append-only JSONL，按天/月滚动。
 *
 * <p>写盘走单线程队列，主线程只入队 —— 避免玩家交易时被磁盘 IO 卡住。
 * 每笔都记 {@code after}（写入后余额），即使余额本体存在 Numismatic 也保持自洽。
 */
public final class Ledger {

	public static final String TYPE_CHECKIN = "checkin";
	public static final String TYPE_TASK = "task.claim";
	public static final String TYPE_SHOP_BUY = "shop.buy";
	public static final String TYPE_SHOP_SELL = "shop.sell";
	public static final String TYPE_PAY = "pay";
	public static final String TYPE_ADMIN = "admin";
	public static final String TYPE_PREMIUM = "premium";
	public static final String TYPE_WAREHOUSE = "warehouse.upgrade";
	public static final String TYPE_BOUNTY = "bounty";
	public static final String TYPE_PASS = "battlepass";
	/** R42：管理员手动解锁付费轨（不走扣费，必须留流水）。 */
	public static final String TYPE_PASS_ADMIN = "battlepass.admin.unlock";
	public static final String TYPE_BOARD = "board";
	public static final String TYPE_MAIL = "mail";
	public static final String TYPE_MIGRATE = "migrate";
	/** R40：玩家市场 —— 挂单费 / 买家付款 / 卖家收款，三笔分开记，便于对账。 */
	public static final String TYPE_MARKET_FEE = "market.fee";
	public static final String TYPE_MARKET_BUY = "market.buy";
	public static final String TYPE_MARKET_SELL = "market.sell";
	/** R41：拍卖出价托管 / 被超越退款 / 流拍退物，都走这个类型。 */
	public static final String TYPE_MARKET_AUCTION = "market.auction";

	private static final DateTimeFormatter DAY = DateTimeFormatter.ISO_LOCAL_DATE;
	private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyy-MM");

	private static Path root;
	private static ExecutorService writer;
	private static volatile boolean ready;

	private Ledger() {
	}

	public static void init() {
		if (writer == null) {
			writer = Executors.newSingleThreadExecutor(r -> {
				Thread t = new Thread(r, "redtale-ledger");
				t.setDaemon(true);
				return t;
			});
		}
		PathResolver.Resolved r = DataRoot.resolve();
		try {
			Path dir = r.path().resolve(StorageConfig.ledgerDir().replace("/", java.io.File.separator));
			Files.createDirectories(dir);
			root = dir;
			ready = true;
			RedtaleEconomy.LOGGER.info("[ledger] 流水目录：{}（粒度 {}）", dir, StorageConfig.granularity());
		} catch (Exception e) {
			ready = false;
			RedtaleEconomy.LOGGER.error("[ledger] 目录不可用：{}", e.getMessage());
		}
	}

	public static boolean ready() {
		return ready;
	}

	/** 记一笔流水；{@code after} 是写入后的余额快照值。 */
	public static void record(UUID uuid, long delta, long after, String type, String ref) {
		if (!StorageConfig.ledgerEnabled()) return;
		if (MysqlBackend.available()) {
			MysqlBackend.appendLedgerAsync(uuid, delta, after, type, ref);
			return;
		}
		if (!ready) return;
		JsonObject o = new JsonObject();
		o.addProperty("ts", TimeUtil.nowIso());
		o.addProperty("uuid", uuid.toString());
		o.addProperty("delta", delta);
		o.addProperty("after", after);
		o.addProperty("type", type == null ? "unknown" : type);
		o.addProperty("ref", ref == null ? "" : ref);
		o.addProperty("server", serverId());
		final String line = o.toString();
		final Path file = fileFor(TimeUtil.businessDate(0));
		ExecutorService w = writer;
		if (w == null) {
			append(file, line);
			return;
		}
		w.execute(() -> append(file, line));
	}

	private static String serverId() {
		return StorageConfig.store().getPathString("mysql.serverId", "local");
	}

	static Path fileFor(LocalDate date) {
		return fileFor(date, StorageConfig.granularity(), root);
	}

	/** 纯逻辑版本（B07 单测用）：不读配置、不碰静态状态。 */
	public static Path fileFor(LocalDate date, String granularity, Path base) {
		if (base == null) return null;
		if ("monthly".equals(granularity)) {
			return base.resolve(YearMonth.from(date).format(MONTH) + ".jsonl");
		}
		return base.resolve(date.format(MONTH)).resolve(date.format(DAY) + ".jsonl");
	}

	private static void append(Path file, String line) {
		if (file == null) return;
		try {
			Files.createDirectories(file.getParent());
			Files.writeString(file, line + System.lineSeparator(), StandardCharsets.UTF_8,
					StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		} catch (IOException e) {
			RedtaleEconomy.LOGGER.error("[ledger] 写入 {} 失败：{}", file.getFileName(), e.getMessage());
		}
	}

	/** 关服前排空队列，避免最后几笔丢在内存里。 */
	public static void shutdown() {
		ExecutorService w = writer;
		if (w == null) return;
		w.shutdown();
		try {
			if (!w.awaitTermination(5, TimeUnit.SECONDS)) {
				w.shutdownNow();
			}
		} catch (InterruptedException e) {
			w.shutdownNow();
			Thread.currentThread().interrupt();
		}
		writer = null;
	}
}
