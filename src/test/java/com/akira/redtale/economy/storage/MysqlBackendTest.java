package com.akira.redtale.economy.storage;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** R8：MySQL 连接串与标识符处理（不连库也能验证的部分）。 */
class MysqlBackendTest {

	@Test
	void urlContainsHostPortDbAndUtf8() {
		String url = MysqlBackend.jdbcUrl("db.example.com", 3307, "redtale", false);
		assertTrue(url.startsWith("jdbc:mysql://db.example.com:3307/redtale?"));
		assertTrue(url.contains("characterEncoding=utf8"));
		assertTrue(url.contains("useSSL=false"));
		assertTrue(url.contains("allowPublicKeyRetrieval=true"));
		assertTrue(url.contains("connectTimeout="));
	}

	@Test
	void urlKeepsSslWhenEnabled() {
		String url = MysqlBackend.jdbcUrl("127.0.0.1", 3306, "rt", true);
		assertFalse(url.contains("useSSL=false"));
	}

	@Test
	void prefixRejectsInjection() {
		assertEquals("redtale_", MysqlBackend.sanitizeIdentifier("redtale_", "x_"));
		assertEquals("abc123", MysqlBackend.sanitizeIdentifier("a`b;c 123", "x_"));
		assertEquals("x_", MysqlBackend.sanitizeIdentifier("---", "x_"));
		assertEquals("x_", MysqlBackend.sanitizeIdentifier(null, "x_"));
	}

	@Test
	void tableNameIsQuoted() {
		assertEquals("`redtale_accounts`", MysqlBackend.table("accounts"));
	}
}
