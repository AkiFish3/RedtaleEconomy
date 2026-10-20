package com.akira.redtale.economy.storage;

import com.akira.redtale.economy.RedtaleEconomy;
import com.akira.redtale.economy.config.StorageConfig;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.io.Reader;
import java.io.Writer;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * MySQL 后端（spec v0.6 §13.5 / R8）。
 *
 * <p>只依赖 JDK 自带的 {@code java.sql}，不把任何 JDBC 驱动打进 jar：
 * <ul>
 *   <li>{@code storage.json → mysql.driverJar} 非空 ⇒ 从该路径外部加载驱动（URLClassLoader + DriverShim）</li>
 *   <li>为空 ⇒ 走 classpath 上的驱动（例如整合包自带 mysql-connector-j）</li>
 * </ul>
 * 驱动缺失时 {@link #init()} 返回 false 并给出明确指引，<b>不影响 JSON 后端运行</b>。
 *
 * <p>连接池是 {@code mysql.poolSize} 个长连接的阻塞队列（够用、无第三方依赖）。
 * 所有 SQL 的表名前缀来自配置，标识符做白名单校验，禁止拼接用户输入。
 */
public final class MysqlBackend {

	private static final Gson GSON = new Gson();
	private static final List<Connection> ALL = new ArrayList<>();
	private static BlockingQueue<Connection> pool;
	private static ExecutorService writer;
	private static volatile boolean ready;
	private static volatile String lastError = "";
	private static volatile String prefix = "redtale_";
	private static volatile String serverId = "local";

	private MysqlBackend() {
	}

	// ------------------------------------------------------------------ 生命周期

	public static synchronized boolean init() {
		shutdown();
		lastError = "";
		prefix = sanitizeIdentifier(StorageConfig.mysqlPrefix(), "redtale_");
		serverId = StorageConfig.mysqlServerId();

		if (!StorageConfig.backendIsMysql()) {
			ready = false;
			return false;
		}
		String user = StorageConfig.mysqlUser();
		String pass = StorageConfig.mysqlPassword();
		String host = StorageConfig.mysqlHost();
		String db = StorageConfig.mysqlDatabase();
		if (host.isBlank() || db.isBlank() || user.isBlank()) {
			lastError = "storage.json → mysql.host/database/user 未填写";
			RedtaleEconomy.LOGGER.error("[mysql] {}，保持 JSON 后端", lastError);
			ready = false;
			return false;
		}

		try {
			loadDriver();
			String url = jdbcUrl(host, StorageConfig.mysqlPort(), db, StorageConfig.mysqlUseSsl());
			int size = Math.max(1, StorageConfig.mysqlPoolSize());
			BlockingQueue<Connection> q = new LinkedBlockingQueue<>();
			List<Connection> opened = new ArrayList<>();
			for (int i = 0; i < size; i++) {
				Connection c = DriverManager.getConnection(url, user, pass);
				opened.add(c);
				q.add(c);
			}
			synchronized (MysqlBackend.class) {
				pool = q;
				ALL.addAll(opened);
				writer = Executors.newSingleThreadExecutor(r -> {
					Thread t = new Thread(r, "redtale-mysql-writer");
					t.setDaemon(true);
					return t;
				});
				ready = true;
			}
			createTables();
			RedtaleEconomy.LOGGER.info("[mysql] 已连接 {}（{} 个连接，前缀 {}，serverId {}）",
					url, size, prefix, serverId);
			return true;
		} catch (Exception e) {
			lastError = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
			RedtaleEconomy.LOGGER.error("[mysql] 连接失败：{}；保持 JSON 后端。"
					+ "提示：把 mysql-connector-j.jar 路径填到 storage.json → mysql.driverJar", lastError);
			shutdown();
			return false;
		}
	}

	public static boolean available() {
		return ready && pool != null;
	}

	public static String lastError() {
		return lastError;
	}

	public static synchronized void shutdown() {
		ready = false;
		if (writer != null) {
			writer.shutdown();
			try {
				writer.awaitTermination(5, TimeUnit.SECONDS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			writer = null;
		}
		for (Connection c : ALL) {
			try {
				c.close();
			} catch (SQLException ignored) {
				// 关服阶段不再纠缠
			}
		}
		ALL.clear();
		pool = null;
		// 关掉后回到默认前缀：静态字段被测试或多个 init 复用，留着旧值会让 table() 串味
		prefix = "redtale_";
		serverId = "local";
	}

	// ------------------------------------------------------------------ 驱动加载

	private static void loadDriver() throws Exception {
		String jar = StorageConfig.mysqlDriverJar();
		String cls = StorageConfig.mysqlDriverClass();
		if (!jar.isBlank()) {
			Path p = Path.of(jar);
			if (!Files.isRegularFile(p)) {
				throw new IllegalStateException("driverJar 不存在：" + jar);
			}
			URLClassLoader ucl = new URLClassLoader(new URL[]{p.toUri().toURL()},
					MysqlBackend.class.getClassLoader());
			Class<?> c = Class.forName(cls, true, ucl);
			Driver d = (Driver) c.getDeclaredConstructor().newInstance();
			DriverManager.registerDriver(new DriverShim(d));
			RedtaleEconomy.LOGGER.info("[mysql] 已从外部 jar 加载驱动 {}", jar);
		} else {
			Class.forName(cls);
		}
	}

	/** 让外部 ClassLoader 里的驱动能被 DriverManager 使用。 */
	private static final class DriverShim implements Driver {
		private final Driver d;

		DriverShim(Driver d) {
			this.d = d;
		}

		@Override
		public Connection connect(String url, Properties info) throws SQLException {
			return d.connect(url, info);
		}

		@Override
		public boolean acceptsURL(String url) throws SQLException {
			return d.acceptsURL(url);
		}

		@Override
		public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) throws SQLException {
			return d.getPropertyInfo(url, info);
		}

		@Override
		public int getMajorVersion() {
			return d.getMajorVersion();
		}

		@Override
		public int getMinorVersion() {
			return d.getMinorVersion();
		}

		@Override
		public boolean jdbcCompliant() {
			return d.jdbcCompliant();
		}

		@Override
		public Logger getParentLogger() {
			return Logger.getGlobal();
		}
	}

	// ------------------------------------------------------------------ SQL 片段（可单测）

	static String jdbcUrl(String host, int port, String db, boolean ssl) {
		String extra = ssl ? "" : "&useSSL=false&allowPublicKeyRetrieval=true";
		return "jdbc:mysql://" + host + ":" + port + "/" + db
				+ "?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai"
				+ extra + "&connectTimeout=5000&socketTimeout=15000";
	}

	/** 表名/前缀只允许 [A-Za-z0-9_]；非法字符剔除，空则回落。防 SQL 标识符注入。 */
	static String sanitizeIdentifier(String raw, String fallback) {
		if (raw == null) return fallback;
		String s = raw.replaceAll("[^A-Za-z0-9_]", "");
		return s.isBlank() ? fallback : s;
	}

	static String table(String name) {
		return "`" + prefix + name + "`";
	}

	// ------------------------------------------------------------------ 建表

	private static void createTables() {
		exec("CREATE TABLE IF NOT EXISTS " + table("accounts") + " ("
				+ "player_uuid CHAR(36) NOT NULL,"
				+ "server_id VARCHAR(64) NOT NULL,"
				+ "player_name VARCHAR(64) NULL,"
				+ "balance BIGINT NOT NULL DEFAULT 0,"
				+ "updated_at BIGINT NOT NULL,"
				+ "PRIMARY KEY (player_uuid, server_id)"
				+ ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
		exec("CREATE TABLE IF NOT EXISTS " + table("ledger") + " ("
				+ "id BIGINT NOT NULL AUTO_INCREMENT,"
				+ "server_id VARCHAR(64) NOT NULL,"
				+ "player_uuid CHAR(36) NOT NULL,"
				+ "ts BIGINT NOT NULL,"
				+ "delta BIGINT NOT NULL,"
				+ "balance_after BIGINT NOT NULL,"
				+ "type VARCHAR(32) NOT NULL,"
				+ "ref VARCHAR(255) NULL,"
				+ "PRIMARY KEY (id),"
				+ "KEY idx_server_ts (server_id, ts)"
				+ ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
		exec("CREATE TABLE IF NOT EXISTS " + table("snapshots") + " ("
				+ "id BIGINT NOT NULL AUTO_INCREMENT,"
				+ "server_id VARCHAR(64) NOT NULL,"
				+ "player_uuid CHAR(36) NOT NULL,"
				+ "ts BIGINT NOT NULL,"
				+ "balance BIGINT NOT NULL,"
				+ "full_snapshot TINYINT NOT NULL DEFAULT 0,"
				+ "PRIMARY KEY (id),"
				+ "KEY idx_server_ts (server_id, ts)"
				+ ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
	}

	private static void exec(String sql) {
		Connection c = borrow();
		if (c == null) return;
		try (Statement st = c.createStatement()) {
			st.execute(sql);
		} catch (SQLException e) {
			RedtaleEconomy.LOGGER.error("[mysql] 建表失败：{}", e.getMessage());
		} finally {
			give(c);
		}
	}

	// ------------------------------------------------------------------ 账号

	/** 读余额；账号不存在返回 0（调用方决定是否用 startingBalance）。 */
	public static long loadBalance(UUID uuid) {
		if (!available()) return 0L;
		Connection c = borrow();
		if (c == null) return 0L;
		String sql = "SELECT balance FROM " + table("accounts")
				+ " WHERE player_uuid=? AND server_id=?";
		try (PreparedStatement ps = c.prepareStatement(sql)) {
			ps.setString(1, uuid.toString());
			ps.setString(2, serverId);
			try (ResultSet rs = ps.executeQuery()) {
				if (rs.next()) return rs.getLong(1);
			}
		} catch (SQLException e) {
			RedtaleEconomy.LOGGER.error("[mysql] 读余额失败 {}：{}", uuid, e.getMessage());
		} finally {
			give(c);
		}
		return 0L;
	}

	public static boolean hasAccount(UUID uuid) {
		if (!available()) return false;
		Connection c = borrow();
		if (c == null) return false;
		try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM " + table("accounts")
				+ " WHERE player_uuid=? AND server_id=?")) {
			ps.setString(1, uuid.toString());
			ps.setString(2, serverId);
			try (ResultSet rs = ps.executeQuery()) {
				return rs.next();
			}
		} catch (SQLException e) {
			return false;
		} finally {
			give(c);
		}
	}

	public static boolean saveBalance(UUID uuid, String name, long balance) {
		if (!available()) return false;
		Connection c = borrow();
		if (c == null) return false;
		String sql = "INSERT INTO " + table("accounts")
				+ " (player_uuid, server_id, player_name, balance, updated_at) VALUES (?,?,?,?,?)"
				+ " ON DUPLICATE KEY UPDATE player_name=VALUES(player_name),"
				+ " balance=VALUES(balance), updated_at=VALUES(updated_at)";
		try (PreparedStatement ps = c.prepareStatement(sql)) {
			ps.setString(1, uuid.toString());
			ps.setString(2, serverId);
			ps.setString(3, name);
			ps.setLong(4, balance);
			ps.setLong(5, System.currentTimeMillis());
			ps.executeUpdate();
			return true;
		} catch (SQLException e) {
			RedtaleEconomy.LOGGER.error("[mysql] 写余额失败 {}：{}", uuid, e.getMessage());
			return false;
		} finally {
			give(c);
		}
	}

	// ------------------------------------------------------------------ 流水 / 快照

	public static void appendLedgerAsync(UUID uuid, long delta, long after, String type, String ref) {
		if (!available()) return;
		ExecutorService w = writer;
		if (w == null) {
			appendLedger(uuid, delta, after, type, ref);
			return;
		}
		w.execute(() -> appendLedger(uuid, delta, after, type, ref));
	}

	public static void appendLedger(UUID uuid, long delta, long after, String type, String ref) {
		if (!available()) return;
		Connection c = borrow();
		if (c == null) return;
		String sql = "INSERT INTO " + table("ledger")
				+ " (server_id, player_uuid, ts, delta, balance_after, type, ref) VALUES (?,?,?,?,?,?,?)";
		try (PreparedStatement ps = c.prepareStatement(sql)) {
			ps.setString(1, serverId);
			ps.setString(2, uuid.toString());
			ps.setLong(3, System.currentTimeMillis());
			ps.setLong(4, delta);
			ps.setLong(5, after);
			ps.setString(6, type == null ? "unknown" : type);
			ps.setString(7, ref == null ? "" : ref);
			ps.executeUpdate();
		} catch (SQLException e) {
			RedtaleEconomy.LOGGER.error("[mysql] 写流水失败：{}", e.getMessage());
		} finally {
			give(c);
		}
	}

	/** 快照异步写：快照在 tick 里触发，不能占主线程做网络 IO。 */
	public static void appendSnapshotAsync(UUID uuid, long balance, boolean full) {
		if (!available()) return;
		ExecutorService w = writer;
		if (w == null) {
			appendSnapshot(uuid, balance, full);
			return;
		}
		w.execute(() -> appendSnapshot(uuid, balance, full));
	}

	public static void appendSnapshot(UUID uuid, long balance, boolean full) {
		if (!available()) return;
		Connection c = borrow();
		if (c == null) return;
		String sql = "INSERT INTO " + table("snapshots")
				+ " (server_id, player_uuid, ts, balance, full_snapshot) VALUES (?,?,?,?,?)";
		try (PreparedStatement ps = c.prepareStatement(sql)) {
			ps.setString(1, serverId);
			ps.setString(2, uuid.toString());
			ps.setLong(3, System.currentTimeMillis());
			ps.setLong(4, balance);
			ps.setInt(5, full ? 1 : 0);
			ps.executeUpdate();
		} catch (SQLException e) {
			RedtaleEconomy.LOGGER.error("[mysql] 写快照失败：{}", e.getMessage());
		} finally {
			give(c);
		}
	}

	// ------------------------------------------------------------------ 迁移

	/** 统计 JSON 账号文件数（dry-run 用）。 */
	public static int countJsonAccounts(Path accountDir) {
		if (accountDir == null || !Files.isDirectory(accountDir)) return 0;
		try (var s = Files.list(accountDir)) {
			return (int) s.filter(p -> p.getFileName().toString().endsWith(".json")).count();
		} catch (Exception e) {
			return 0;
		}
	}

	/** JSON → MySQL。返回成功写入条数。 */
	public static int migrateFromJson(Path accountDir) {
		if (!available()) return 0;
		int ok = 0;
		try (var s = Files.list(accountDir)) {
			for (Path p : (Iterable<Path>) s.filter(x -> x.getFileName().toString().endsWith(".json"))::iterator) {
				String fn = p.getFileName().toString();
				UUID uuid;
				try {
					uuid = UUID.fromString(fn.substring(0, fn.length() - 5));
				} catch (IllegalArgumentException bad) {
					continue;
				}
				try (Reader r = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
					JsonObject o = GSON.fromJson(r, JsonObject.class);
					long bal = o != null && o.has("balance") ? o.get("balance").getAsLong() : 0L;
					String name = o != null && o.has("name") ? o.get("name").getAsString() : null;
					if (saveBalance(uuid, name, bal)) ok++;
				} catch (Exception e) {
					RedtaleEconomy.LOGGER.error("[mysql] 迁移 {} 失败：{}", fn, e.getMessage());
				}
			}
		} catch (Exception e) {
			RedtaleEconomy.LOGGER.error("[mysql] 扫描账号目录失败：{}", e.getMessage());
		}
		return ok;
	}

	/** MySQL → JSON。返回成功写出的条数。 */
	public static int migrateToJson(Path accountDir) {
		if (!available()) return 0;
		int ok = 0;
		try {
			Files.createDirectories(accountDir);
			Connection c = borrow();
			if (c == null) return 0;
			try (PreparedStatement ps = c.prepareStatement("SELECT player_uuid, player_name, balance FROM "
					+ table("accounts") + " WHERE server_id=?")) {
				ps.setString(1, serverId);
				try (ResultSet rs = ps.executeQuery()) {
					while (rs.next()) {
						UUID uuid;
						try {
							uuid = UUID.fromString(rs.getString(1));
						} catch (IllegalArgumentException bad) {
							continue;
						}
						long bal = rs.getLong(3);
						Path f = accountDir.resolve(uuid + ".json");
						Path tmp = accountDir.resolve(uuid + ".json.tmp");
						JsonObject o = new JsonObject();
						o.addProperty("_version", 1);
						o.addProperty("uuid", uuid.toString());
						String name = rs.getString(2);
						if (name != null) o.addProperty("name", name);
						o.addProperty("balance", bal);
						try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
							GSON.toJson(o, w);
						}
						try {
							Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING,
									StandardCopyOption.ATOMIC_MOVE);
						} catch (Exception atomicFail) {
							Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
						}
						ok++;
					}
				}
			} finally {
				give(c);
			}
		} catch (Exception e) {
			RedtaleEconomy.LOGGER.error("[mysql] 导出失败：{}", e.getMessage());
		}
		return ok;
	}

	/**
	 * 行数统计（`/redtale migrate storage` dry-run 与自检用）。
	 *
	 * @param which 只接受 accounts / ledger / snapshots（白名单，防表名注入）
	 * @return 行数；后端未启用或参数非法返回 -1
	 */
	public static int countRows(String which) {
		if (!available()) return -1;
		if (!"accounts".equals(which) && !"ledger".equals(which) && !"snapshots".equals(which)) return -1;
		Connection c = borrow();
		if (c == null) return -1;
		try (PreparedStatement ps = c.prepareStatement(
				"SELECT COUNT(*) FROM " + table(which) + " WHERE server_id=?")) {
			ps.setString(1, serverId);
			try (ResultSet rs = ps.executeQuery()) {
				return rs.next() ? rs.getInt(1) : -1;
			}
		} catch (SQLException e) {
			RedtaleEconomy.LOGGER.warn("[mysql] 统计 {} 失败：{}", which, e.getMessage());
			return -1;
		} finally {
			give(c);
		}
	}

	/** 库内账号数（dry-run / 对账用）。 */
	public static int countMysqlAccounts() {		if (!available()) return 0;
		Connection c = borrow();
		if (c == null) return 0;
		try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM " + table("accounts")
				+ " WHERE server_id=?")) {
			ps.setString(1, serverId);
			try (ResultSet rs = ps.executeQuery()) {
				return rs.next() ? rs.getInt(1) : 0;
			}
		} catch (SQLException e) {
			return 0;
		} finally {
			give(c);
		}
	}

	// ------------------------------------------------------------------ 池

	private static Connection borrow() {
		BlockingQueue<Connection> q = pool;
		if (q == null) return null;
		try {
			Connection c = q.poll(3, TimeUnit.SECONDS);
			if (c == null) return null;
			if (c.isClosed()) {
				c = DriverManager.getConnection(
						jdbcUrl(StorageConfig.mysqlHost(), StorageConfig.mysqlPort(),
								StorageConfig.mysqlDatabase(), StorageConfig.mysqlUseSsl()),
						StorageConfig.mysqlUser(), StorageConfig.mysqlPassword());
				synchronized (MysqlBackend.class) {
					ALL.add(c);
				}
			}
			return c;
		} catch (Exception e) {
			RedtaleEconomy.LOGGER.error("[mysql] 取连接失败：{}", e.getMessage());
			return null;
		}
	}

	private static void give(Connection c) {
		BlockingQueue<Connection> q = pool;
		if (c == null || q == null) return;
		try {
			if (c.isClosed() || !c.isValid(2)) {
				return;
			}
		} catch (SQLException e) {
			return;
		}
		q.offer(c);
	}
}
