package com.akira.redtale.economy.config;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 回归：配置根是嵌套对象，点号键必须能读到值。
 *
 * <p>2026-10-20 实测 bug —— {@code getString("mysql.host")} 以前只做平铺查找，
 * 永远返回默认值，导致 MySQL 参数 / 权限节点 / 战令赛季 / 补签物品配置全部静默失效。
 */
class ConfigDottedKeyTest {

	@TempDir
	static Path dir;

	@BeforeAll
	static void setUp() throws Exception {
		ConfigManager.createAt(dir);
		String json = """
				{
				  "_version": 1,
				  "backend": "mysql",
				  "mysql": {
				    "host": "127.0.0.1",
				    "port": 3307,
				    "database": "redtale_it",
				    "user": "root",
				    "driverClass": "com.mysql.cj.jdbc.Driver",
				    "tablePrefix": "it_",
				    "serverId": "it-1",
				    "poolSize": 2,
				    "useSSL": false
				  },
				  "snapshotIntervalMinutes": 7
				}
				""";
		Files.writeString(dir.resolve(StorageConfig.FILE), json, StandardCharsets.UTF_8);
		ConfigManager.get().define(StorageConfig.FILE, StorageConfig.SCHEMA);
		StorageConfig.store().load();
	}

	@AfterAll
	static void tearDown() {
		ConfigManager.clearInstance();
	}

	@Test
	void nestedValuesAreReadableThroughDottedKey() {
		assertEquals("mysql", StorageConfig.backend());
		assertEquals("127.0.0.1", StorageConfig.mysqlHost());
		assertEquals(3307, StorageConfig.mysqlPort());
		assertEquals("redtale_it", StorageConfig.mysqlDatabase());
		assertEquals("it_", StorageConfig.mysqlPrefix());
		assertEquals("it-1", StorageConfig.mysqlServerId());
		assertEquals(2, StorageConfig.mysqlPoolSize());
		assertFalse(StorageConfig.mysqlUseSsl());
	}

	@Test
	void topLevelValuesStillWork() {
		assertEquals(7, StorageConfig.snapshotIntervalMinutes());
	}

	@Test
	void missingDottedKeyFallsBackToDefault() {
		assertEquals("", StorageConfig.store().getString("mysql.notThere", ""));
		assertEquals(99, StorageConfig.store().getLong("mysql.notThere", 99));
	}
}
