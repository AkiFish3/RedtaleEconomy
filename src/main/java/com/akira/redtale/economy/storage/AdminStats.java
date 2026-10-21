package com.akira.redtale.economy.storage;

import com.akira.redtale.economy.RedtaleEconomy;
import com.akira.redtale.economy.config.ConfigManager;
import com.akira.redtale.economy.config.StorageConfig;
import com.akira.redtale.economy.market.MarketStore;
import com.akira.redtale.economy.shop.ShopCatalog;
import com.akira.redtale.economy.util.DataRoot;
import com.akira.redtale.economy.util.TimeUtil;
import net.minecraft.server.MinecraftServer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * T28 管理员控制台的数据面。
 *
 * <p>只读聚合：把散在各模块里的「一眼要看的数字」收成一个列表，供 {@code net.AdminNetworking}
 * 直接下发。**不写任何文件**，也不做权限判断（权限在 net 层）。
 *
 * <p>★每条指标独立 try/catch★：控制台是诊断入口，某个模块没加载（市场关闭 / 商店目录还没建）
 * 不应该让整页变空白 —— 拿不到就显示「—」，并在 hint 里写清原因。
 */
public final class AdminStats {

	/** 一条指标：label/value 给界面用，hint 是补充说明（可为空串）。 */
	public record Item(String label, String value, String hint) {
	}

	private AdminStats() {
	}

	/** 收集全部指标。{@code server} 可以为 null（单测/未开服时只算本地数据）。 */
	public static List<Item> collect(MinecraftServer server) {
		List<Item> out = new ArrayList<>(16);
		add(out, "在线玩家", () -> server == null ? "—" : String.valueOf(server.getCurrentPlayerCount()),
				"当前服务器在线人数");
		add(out, "商店 / 商品", () -> ShopCatalog.shopCount() + " 店 / " + ShopCatalog.itemCount() + " 件",
				"shops/shops 下已加载的商店与商品总数");
		add(out, "价格违规", () -> ShopCatalog.priceViolations() + " 条",
				"买价低于卖价等配置错误（加载时统计）");
		add(out, "市场挂单", AdminStats::market, "在售 + 拍卖中（不含已结算）");
		add(out, "今日流水", () -> LedgerQuery.query(ledgerRoot(), StorageConfig.granularity(),
						TimeUtil.businessDate(0), 1, 0, 0, null, 1).total() + " 笔",
				"营业日口径，与「流水」页的「今天」一致");
		add(out, "近 7 天流水", () -> LedgerQuery.query(ledgerRoot(), StorageConfig.granularity(),
						TimeUtil.businessDate(0), 7, 0, 0, null, 1).total() + " 笔",
				"含今天，按营业日切分");
		add(out, "配置项", () -> cfg().definedFiles().size() + " 个文件",
				"已注册 schema 的配置（不含商店数据）");
		add(out, "最近重载", () -> {
			long at = cfg().lastReloadAt();
			return at <= 0 ? "从未" : AdminStats.age(System.currentTimeMillis() - at);
		}, "上一次 loadAll / reloadAll 的时间");
		add(out, "加载错误", () -> {
			List<String> errs = cfg().lastLoadErrors();
			return errs.isEmpty() ? "无" : errs.size() + " 条";
		}, "最近一次加载失败的文件（详见日志）");
		add(out, "商店回收站", AdminStats::trash, "被删商品/商店的备份文件数");
		add(out, "数据根", () -> String.valueOf(DataRoot.path()), "redtale-data 的实际落点");
		return out;
	}

	// ------------------------------------------------------------ 单项

	private static String market() {
		List<MarketStore.Entry> open = MarketStore.open();
		int auctions = 0;
		for (MarketStore.Entry e : open) {
			if ("auction".equalsIgnoreCase(e.type)) auctions++;
		}
		return open.size() + " 单（拍卖 " + auctions + "）";
	}

	private static String trash() {
		Path dir = ConfigManager.get().baseDir().resolve(ShopCatalog.TRASH_DIR);
		if (!Files.isDirectory(dir)) return "空";
		try (Stream<Path> s = Files.walk(dir, 2)) {
			long n = s.filter(Files::isRegularFile).count();
			return n == 0 ? "空" : n + " 个文件";
		} catch (Exception e) {
			return "?";
		}
	}

	private static Path ledgerRoot() {
		return DataRoot.path().resolve(StorageConfig.ledgerDir());
	}

	private static ConfigManager cfg() {
		return ConfigManager.get();
	}

	/**
	 * 毫秒差 → 人话。
	 *
	 * <p>刻意只到「天」：控制台不需要秒级精度，而「3 天前」比「259200000 ms」有用。
	 */
	public static String age(long ms) {
		if (ms < 0) return "刚刚";
		long sec = ms / 1000L;
		// 5 秒内统一叫「刚刚」：控制台是手动刷新的，写「1 秒前」反而让人以为在自动轮询
		if (sec < 5) return "刚刚";
		if (sec < 60) return sec + " 秒前";
		long min = sec / 60L;
		if (min < 60) return min + " 分钟前";
		long hour = min / 60L;
		if (hour < 24) return hour + " 小时前";
		return (hour / 24L) + " 天前";
	}

	/** 单条指标的取值函数：任何异常都降级成「—」，不让整页空白。 */
	private interface Value {
		String get() throws Exception;
	}

	private static void add(List<Item> out, String label, Value v, String hint) {
		String value;
		try {
			value = v.get();
		} catch (Exception e) {
			value = "—";
			// 诊断入口只记一行，不打堆栈：控制台刷新很频繁
			RedtaleEconomy.LOGGER.warn("[admin] 指标「{}」取值失败：{}", label, e.toString());
		}
		out.add(new Item(label, value == null ? "—" : value, hint));
	}
}
