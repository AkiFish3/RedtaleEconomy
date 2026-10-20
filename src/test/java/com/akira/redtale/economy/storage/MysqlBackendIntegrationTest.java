package com.akira.redtale.economy.storage;

import com.akira.redtale.economy.config.ConfigManager;
import com.akira.redtale.economy.config.StorageConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * R8：MySQL 后端<b>实连</b>集成测试。
 *
 * <p>默认跳过（CI / 无库机器不影响 build）：只有传
 * {@code -Dredtale.it.mysql=1 -Dredtale.it.mysql.port=3307 ...} 时才跑。
 * 参数：host / port / database / user / password / driverJar。
 *
 * <p>覆盖：建表 → UPSERT → 读回 → 流水 → 快照 → JSON→MySQL → MySQL→JSON → 行数对账。
 */
class MysqlBackendIntegrationTest {

	@TempDir
	static Path dir;

	private static final UUID A = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");
	private static final UUID B = UUID.fromString("11111111-2222-3333-4444-555555555555");

	@BeforeAll
	static void connect() throws Exception {
		assumeTrue("1".equals(System.getProperty("redtale.it.mysql")),
				"未开启 MySQL 集成测试（-Dredtale.it.mysql=1）");
		String host = System.getProperty("redtale.it.mysql.host", "127.0.0.1");
		int port = Integer.parseInt(System.getProperty("redtale.it.mysql.port", "3307"));
		String db = System.getProperty("redtale.it.mysql.database", "redtale_it");
		String user = System.getProperty("redtale.it.mysql.user", "root");
		String pass = System.getProperty("redtale.it.mysql.password", "");
		String driverJar = System.getProperty("redtale.it.mysql.driverJar", "");

		String json = """
				{
				  "_version": 1,
				  "backend": "mysql",
				  "mysql": {
				    "host": "%s",
				    "port": %d,
				    "database": "%s",
				    "user": "%s",
				    "password": "%s",
				    "driverJar": "%s",
				    "driverClass": "com.mysql.cj.jdbc.Driver",
				    "tablePrefix": "it_",
				    "serverId": "it-main",
				    "poolSize": 2,
				    "useSSL": false
				  }
				}
				""".formatted(host, port, db, user, pass, driverJar.replace("\\", "/"));
		Path cfg = dir.resolve("config");
		Files.createDirectories(cfg);
		Files.writeString(cfg.resolve(StorageConfig.FILE), json, StandardCharsets.UTF_8);
		ConfigManager.createAt(cfg);
		ConfigManager.get().define(StorageConfig.FILE, StorageConfig.SCHEMA);
		StorageConfig.store().load();

		assertTrue(MysqlBackend.init(), "连接失败：" + MysqlBackend.lastError());
	}

	@AfterAll
	static void tearDown() {
		MysqlBackend.shutdown();
		ConfigManager.clearInstance();
	}

	@Test
	void tablesAreCreatedAndUpsertWorks() {
		assertTrue(MysqlBackend.available());
		assertTrue(MysqlBackend.saveBalance(A, "Alice", 12345L));
		assertEquals(12345L, MysqlBackend.loadBalance(A));
		assertTrue(MysqlBackend.hasAccount(A));

		// 同主键再写 = 更新，不是插入
		assertTrue(MysqlBackend.saveBalance(A, "Alice2", 999L));
		assertEquals(999L, MysqlBackend.loadBalance(A));
		assertTrue(MysqlBackend.countRows("accounts") >= 1);
	}

	@Test
	void ledgerAndSnapshotRowsLand() {
		MysqlBackend.saveBalance(B, "Bob", 500L);
		MysqlBackend.appendLedger(B, -200L, 300L, "shop.buy", "shop:general/item:1234");
		MysqlBackend.appendSnapshot(B, 300L, false);
		assertTrue(MysqlBackend.countRows("ledger") >= 1);
		assertTrue(MysqlBackend.countRows("snapshots") >= 1);
	}

	@Test
	void jsonToMysqlAndBackRoundTrip() throws Exception {
		Path accounts = dir.resolve("acc");
		Files.createDirectories(accounts);
		UUID c = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
		Files.writeString(accounts.resolve(c + ".json"),
				"{\"_version\":1,\"name\":\"Carol\",\"balance\":777}", StandardCharsets.UTF_8);

		int before = MysqlBackend.countJsonAccounts(accounts);
		assertEquals(1, before);
		assertEquals(1, MysqlBackend.migrateFromJson(accounts));
		assertEquals(777L, MysqlBackend.loadBalance(c));

		Path out = dir.resolve("export");
		int exported = MysqlBackend.migrateToJson(out);
		assertTrue(exported >= 1);
		String body = Files.readString(out.resolve(c + ".json"), StandardCharsets.UTF_8);
		assertTrue(body.contains("777"), body);
	}
}
