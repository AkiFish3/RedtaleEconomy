package com.akira.redtale.economy.config;

import com.akira.redtale.economy.RedtaleEconomy;
import com.akira.redtale.economy.util.PathResolver;

import java.util.List;

/**
 * {@code config/redtale/storage.json} —— 存储后端、数据根目录、流水与快照策略（spec v0.6 §13）。
 *
 * <p>root 支持绝对路径（D:/ E:/）、{@code %server%}、{@code %world%} 占位；含 {@code ..} 直接拒绝。
 * 默认 {@code %world%/redtale-data}（R36 / spec §40.2）：玩家数据跟存档走，配置留 config。
 */
public final class StorageConfig {

	public static final String FILE = "storage.json";

	public static final ConfigSchema SCHEMA = ConfigSchema.obj("存储设置")
			.field("backend", ConfigSchema.choice("json", List.of("json", "mysql"),
					"存储后端；切换用 /redtale migrate storage"))
			.field("json", ConfigSchema.obj("JSON 后端")
					.field("root", ConfigSchema.string("%world%/redtale-data",
							"数据根目录；%world%=当前存档（默认，换世界不串），%server%=服务端根目录，"
									+ "也可写 D:/redtale-data 或 E:/data/redtale"))
					.field("migrateLegacy", ConfigSchema.bool(true,
							"首次启动时把旧的 %server%/redtale-data 复制进新根目录（只复制不删除）"))
					.field("accounts", ConfigSchema.string("accounts/", "账号目录（每人一个 <uuid>.json）"))
					.field("ledger", ConfigSchema.string("ledger/", "流水目录（append-only JSONL）"))
					.field("snapshots", ConfigSchema.string("snapshots/", "余额快照目录"))
					.field("granularity", ConfigSchema.choice("daily", List.of("daily", "monthly"),
							"流水/快照分文件粒度：daily=每天一个，monthly=每月一个"))
					.field("retentionDays", ConfigSchema.integer(180, 0, 3650,
							"流水与快照保留天数，0=永久"))
					.field("compress", ConfigSchema.bool(false, "滚动归档后是否 gzip（会保留 .jsonl.gz）")))
			.field("ledger", ConfigSchema.obj("流水")
					.field("enabled", ConfigSchema.bool(true, "是否记录流水"))
					.field("flushIntervalSeconds", ConfigSchema.integer(5, 1, 600,
							"流水批量落盘间隔（秒）。★当前实现是每笔立即追加到 JSONL（异步单线程），"
									+ "未做批量缓冲 ⇒ 本项暂不生效★；改成批量会缩短崩溃前的持久化窗口，"
									+ "属架构改动，留待确认")))
			.field("snapshotsEnabled", ConfigSchema.bool(true, "是否写余额快照（对账/回滚用）"))
			.field("snapshotIntervalMinutes", ConfigSchema.integer(30, 1, 10080,
					"同一玩家两次快照的最小间隔（分钟），仅在余额有变动时写"))
			.field("dailyFullSnapshot", ConfigSchema.bool(true, "每天 0 点对所有在线玩家强制全量快照一次"))
			.field("accountCache", ConfigSchema.obj("账号缓存")
					.field("size", ConfigSchema.integer(512, 16, 65536,
							"内存中缓存多少个账号；超出后淘汰「已落盘」的条目（有未落盘改动的永不淘汰）"))
					.field("saveDebounceSeconds", ConfigSchema.integer(10, 1, 600,
							"余额变动后延迟多久写盘（秒），防止高频写"))
					.field("unloadOnQuit", ConfigSchema.bool(true,
							"玩家退出时立即写盘并清缓存（关掉=留在内存里等防抖到期）")))
			.field("mysql", ConfigSchema.obj("MySQL 后端（backend=mysql 时才读）")
					.field("host", ConfigSchema.string("", "数据库地址"))
					.field("port", ConfigSchema.integer(3306, 1, 65535, "端口"))
					.field("database", ConfigSchema.string("", "库名"))
					.field("user", ConfigSchema.string("", "用户名"))
					.field("password", ConfigSchema.string("", "密码；passwordFile 非空时以文件内容为准"))
					.field("passwordFile", ConfigSchema.string("",
							"密码外部文件（绝对路径或 %server%/xxx）；存在且非空则覆盖 password"))
					.field("driverJar", ConfigSchema.string("",
							"JDBC 驱动 jar 路径（如 D:/libs/mysql-connector-j-8.4.0.jar）；留空则用 classpath"))
					.field("driverClass", ConfigSchema.string("com.mysql.cj.jdbc.Driver",
							"驱动类名（换 MariaDB 可填 org.mariadb.jdbc.Driver）"))
					.field("tablePrefix", ConfigSchema.string("redtale_",
							"表名前缀，只允许字母/数字/下划线"))
					.field("poolSize", ConfigSchema.integer(8, 1, 64, "连接池大小"))
					.field("useSSL", ConfigSchema.bool(false, "是否启用 SSL"))
					.field("serverId", ConfigSchema.string("local",
							"服务器标识，跨存档时作分区键（多服共库时各自填不同值）")));

	private StorageConfig() {
	}

	public static ConfigStore store() {
		return ConfigManager.get().store(FILE);
	}

	public static String backend() {
		return store().getString("backend", "json");
	}

	public static String root() {
		return store().getPathString("json.root", "%world%/redtale-data");
	}

	/** R36：首次启动是否把旧 {@code %server%/redtale-data} 合并进新根目录。 */
	public static boolean migrateLegacy() {
		return store().getPathBool("json.migrateLegacy", true);
	}

	public static String accountsDir() {
		return store().getPathString("json.accounts", "accounts/");
	}

	public static String ledgerDir() {
		return store().getPathString("json.ledger", "ledger/");
	}

	public static String snapshotsDir() {
		return store().getPathString("json.snapshots", "snapshots/");
	}

	public static String granularity() {
		return store().getPathString("json.granularity", "daily");
	}

	public static int retentionDays() {
		return (int) store().getPathLong("json.retentionDays", 180);
	}

	public static boolean compress() {
		return store().getPathBool("json.compress", false);
	}

	public static boolean ledgerEnabled() {
		return store().getPathBool("ledger.enabled", true);
	}

	public static boolean snapshotsEnabled() {
		return store().getBool("snapshotsEnabled", true);
	}

	public static int snapshotIntervalMinutes() {
		return (int) store().getLong("snapshotIntervalMinutes", 30);
	}

	/**
	 * 余额变动后延迟多久写盘（秒）。
	 *
	 * <p>★N+80 修★ 旧代码读 {@code account.saveDebounceSeconds}，而 schema 声明的是
	 * {@code accountCache.saveDebounceSeconds} ⇒ 路径不存在，{@code getPathLong} 静默返回默认值
	 * ⇒ 管理员改配置**从未生效**且零日志。{@code _e24_cfg_orphan_scan.py} 的「读取了但未声明」反向检查抓到。
	 */
	public static int saveDebounceSeconds() {
		return (int) store().getPathLong("accountCache.saveDebounceSeconds", 10);
	}

	/** 内存里最多缓存多少个账号余额（超出后按「最久未用且已落盘」淘汰）。 */
	public static int accountCacheSize() {
		return (int) store().getPathLong("accountCache.size", 512);
	}

	/** 玩家退出时立即落盘并清缓存（默认 true）。 */
	public static boolean unloadOnQuit() {
		return store().getPathBool("accountCache.unloadOnQuit", true);
	}

	// ---------------------------------------------------------------- MySQL（R8）

	public static boolean backendIsMysql() {
		return "mysql".equalsIgnoreCase(backend());
	}

	public static String mysqlHost() {
		return store().getString("mysql.host", "").trim();
	}

	public static int mysqlPort() {
		return (int) store().getLong("mysql.port", 3306);
	}

	public static String mysqlDatabase() {
		return store().getString("mysql.database", "").trim();
	}

	public static String mysqlUser() {
		return store().getString("mysql.user", "").trim();
	}

	/** passwordFile 存在且非空 ⇒ 用它；否则用配置里的 password。 */
	public static String mysqlPassword() {
		String fileCfg = store().getString("mysql.passwordFile", "").trim();
		if (!fileCfg.isEmpty()) {
			try {
				PathResolver.Resolved r = PathResolver.resolveDir(fileCfg, fileCfg);
				if (java.nio.file.Files.isRegularFile(r.path())) {
					String s = java.nio.file.Files.readString(r.path(),
							java.nio.charset.StandardCharsets.UTF_8).trim();
					if (!s.isEmpty()) return s;
				}
			} catch (Exception e) {
				RedtaleEconomy.LOGGER.warn("[mysql] 读取 passwordFile 失败：{}", e.getMessage());
			}
		}
		return store().getString("mysql.password", "");
	}

	public static String mysqlDriverJar() {
		return store().getString("mysql.driverJar", "").trim();
	}

	public static String mysqlDriverClass() {
		return store().getString("mysql.driverClass", "com.mysql.cj.jdbc.Driver").trim();
	}

	public static String mysqlPrefix() {
		return store().getString("mysql.tablePrefix", "redtale_");
	}

	public static String mysqlServerId() {
		return store().getString("mysql.serverId", "local");
	}

	public static int mysqlPoolSize() {
		return (int) store().getLong("mysql.poolSize", 8);
	}

	public static boolean mysqlUseSsl() {
		return store().getBool("mysql.useSSL", false);
	}
}
