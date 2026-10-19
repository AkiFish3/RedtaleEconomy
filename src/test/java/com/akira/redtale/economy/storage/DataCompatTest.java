package com.akira.redtale.economy.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** B22 玩家数据兼容性。 */
class DataCompatTest {

	@Test
	void sameVersionIsOk() {
		assertEquals(DataCompat.Verdict.OK, DataCompat.verdict(1, 1));
		assertEquals(DataCompat.Verdict.OK, DataCompat.verdict(0, 0));
	}

	@Test
	void olderVersionIsMigratable() {
		assertEquals(DataCompat.Verdict.OLDER, DataCompat.verdict(0, 1));
		assertEquals(DataCompat.Verdict.OLDER, DataCompat.verdict(1, 3));
	}

	@Test
	void newerVersionIsReadOnly() {
		assertEquals(DataCompat.Verdict.NEWER, DataCompat.verdict(2, 1));
		assertEquals(DataCompat.Verdict.NEWER, DataCompat.verdict(99, DataCompat.ACCOUNT_VERSION));
	}

	@Test
	void backupCopiesFileIntoTooNewDir(@TempDir Path tmp) throws Exception {
		Path src = tmp.resolve("abc.json");
		Files.writeString(src, "{\"_version\":9}");
		Path bak = DataCompat.backup(tmp.resolve("backup").toString(), src, "account");
		assertNotNull(bak);
		assertTrue(Files.isRegularFile(bak));
		assertEquals("{\"_version\":9}", Files.readString(bak));
		assertTrue(bak.toString().replace('\\', '/').contains("/backup/too-new/"));
		assertTrue(bak.getFileName().toString().startsWith("abc.json.account."));
	}

	@Test
	void backupToleratesMissingSource(@TempDir Path tmp) {
		assertNull(DataCompat.backup(tmp.toString(), tmp.resolve("nope.json"), "account"));
	}

	@Test
	void backupWithoutRootIsNoop(@TempDir Path tmp) throws Exception {
		Path src = tmp.resolve("x.json");
		Files.writeString(src, "{}");
		assertNull(DataCompat.backup("", src, "account"));
		assertNull(DataCompat.backup(null, src, "account"));
	}
}
