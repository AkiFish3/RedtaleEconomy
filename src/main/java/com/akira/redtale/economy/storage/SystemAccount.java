package com.akira.redtale.economy.storage;

import com.akira.redtale.economy.RedtaleEconomy;
import com.akira.redtale.economy.util.DataRoot;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * T63：服务器保留账户 {@code __system__}（系统资金池）。
 *
 * <p>为什么需要它：商店买货、商店回收、市场挂单费/抽成、仓储扩容，钱从玩家身上扣掉之后
 * 以前只是「消失」（谁也不记账）⇒ 管理员无法判断「服务器一共回收了多少、又发出去了多少」，
 * 通胀/通缩只能靠估。这里给这些「系统侧」流量一个统一账户。
 *
 * <p>两条语义（Aki 定的口径）：
 * <ul>
 *   <li><b>收入入账</b>：玩家交给系统的钱（商店买价、手续费、抽成、扩容费）累加到资金池余额；</li>
 *   <li><b>支出不扣</b>：系统发给玩家的钱（签到/任务/战令/商店回收/FTBQ 奖励）只累计
 *       {@code spendTotal}，<b>不</b>从余额里扣 —— 余额是「累计回收量」，不是可花预算。
 *       否则高额奖励会把它压成负数，失去统计意义。</li>
 * </ul>
 *
 * <p>落盘：{@code <data>/system/account.json}，tmp + 原子 rename。流水照常进 {@link Ledger}
 * （uuid = 系统 uuid，type = {@code system.in}/{@code system.out}），管理端可对账。
 *
 * <p>★不走 {@link BalanceService}★：那个入口选的是玩家余额后端（Numismatic / JSON），
 * 系统账户不属于任何一个玩家，也不能被 {@code /pay} 之类碰到。
 */
public final class SystemAccount {

	/** 保留名：界面上展示用，不允许被玩家占用（{@code /pay __system__} 会找不到在线玩家）。 */
	public static final String NAME = "__system__";

	/**
	 * 固定 UUID（离线玩家公式的等价写法，但不依赖 Mojang 的 name→uuid 规则）。
	 *
	 * <p>固定而不是随机：资金池要跨重启累加，uuid 变了就是新账户。
	 */
	public static final UUID ID = UUID.nameUUIDFromBytes("OfflinePlayer:__system__".getBytes(StandardCharsets.UTF_8));

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
	private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

	/** 快照：给命令/控制台读的一致性视图。 */
	public record Pool(long balance, long incomeTotal, long spendTotal, String updatedAt) {
		public long net() {
			return incomeTotal - spendTotal;
		}
	}

	private static long balance;
	private static long incomeTotal;
	private static long spendTotal;
	private static String updatedAt = "";
	private static boolean loaded;
	/** 上次解析到的文件路径；{@link DataRoot} 换世界后会变，靠它判断是否要重读。 */
	private static Path loadedPath;

	private SystemAccount() {
	}

	/** 系统收入：余额 += amount（玩家交给系统的钱）。 */
	public static synchronized void income(long amount, String type, String ref) {
		if (amount <= 0) return;
		ensureLoaded();
		balance += amount;
		incomeTotal += amount;
		touch();
		Ledger.record(ID, amount, balance, type == null ? Ledger.TYPE_SYSTEM_IN : type, safe(ref));
	}

	/**
	 * 系统支出：只记累计，<b>不扣</b>余额（见类注释）。
	 *
	 * <p>流水里记成负数，便于和玩家侧「-金额」用同一套对账口径。
	 */
	public static synchronized void spend(long amount, String type, String ref) {
		if (amount <= 0) return;
		ensureLoaded();
		spendTotal += amount;
		touch();
		Ledger.record(ID, -amount, balance, type == null ? Ledger.TYPE_SYSTEM_OUT : type, safe(ref));
	}

	/** 当前资金池视图；未加载时先读盘（读失败按全 0 处理并记 WARN）。 */
	public static synchronized Pool pool() {
		ensureLoaded();
		return new Pool(balance, incomeTotal, spendTotal, updatedAt);
	}

	/** 测试/换世界用：丢掉内存态，下次访问重新读盘。 */
	public static synchronized void resetForTest() {
		loaded = false;
		loadedPath = null;
		balance = 0;
		incomeTotal = 0;
		spendTotal = 0;
		updatedAt = "";
	}

	/** 数据目录变化（R36 两段式时序）后重新绑定：路径不同则重读。 */
	public static synchronized void rebind() {
		Path now = file();
		if (loadedPath != null && !loadedPath.equals(now)) {
			loaded = false;
			loadedPath = null;
		}
		ensureLoaded();
	}

	private static String safe(String ref) {
		return ref == null ? "" : ref;
	}

	private static void touch() {
		updatedAt = LocalDateTime.now().format(TS);
		save();
	}

	private static void ensureLoaded() {
		Path f = file();
		if (loaded && f.equals(loadedPath)) return;
		loadedPath = f;
		loaded = true;
		balance = 0;
		incomeTotal = 0;
		spendTotal = 0;
		updatedAt = "";
		if (!Files.isRegularFile(f)) return;
		try {
			JsonObject o = GSON.fromJson(Files.readString(f, StandardCharsets.UTF_8), JsonObject.class);
			if (o == null) return;
			balance = num(o, "balance");
			incomeTotal = num(o, "incomeTotal");
			spendTotal = num(o, "spendTotal");
			updatedAt = o.has("updatedAt") ? o.get("updatedAt").getAsString() : "";
		} catch (Exception e) {
			// 只 WARN：账本坏了不能拦住交易，但必须留痕（余额按 0 起算会低估，需人工核）
			RedtaleEconomy.LOGGER.warn("[system] 资金池文件读取失败，按 0 起算：{}", e.toString());
		}
	}

	private static long num(JsonObject o, String key) {
		try {
			return o.has(key) ? o.get(key).getAsLong() : 0L;
		} catch (Exception ignored) {
			return 0L;
		}
	}

	private static void save() {
		Path f = file();
		try {
			Files.createDirectories(f.getParent());
			JsonObject o = new JsonObject();
			o.addProperty("balance", balance);
			o.addProperty("incomeTotal", incomeTotal);
			o.addProperty("spendTotal", spendTotal);
			o.addProperty("updatedAt", updatedAt);
			Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
			Files.writeString(tmp, GSON.toJson(o), StandardCharsets.UTF_8);
			Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (Exception e) {
			RedtaleEconomy.LOGGER.warn("[system] 资金池写入失败：{}", e.toString());
		}
	}

	private static Path file() {
		return DataRoot.resolve().path().resolve("system").resolve("account.json");
	}
}
