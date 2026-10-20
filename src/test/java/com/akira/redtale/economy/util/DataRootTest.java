package com.akira.redtale.economy.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** R36：{@code %world%} 占位解析 + 旧目录迁移（只复制不删除、不覆盖）。 */
class DataRootTest {

	@TempDir
	Path root;

	// ---------------------------------------------------------------- %world% 解析

	@Test
	void worldTokenNeedsWorld() {
		assertNull(PathResolver.resolveUnder("%world%/redtale-data", root));
		assertNotNull(PathResolver.resolveUnder("%world%/redtale-data", root, root.resolve("saves/w")));
	}

	@Test
	void worldTokenExpandsToWorldDir() {
		Path world = root.resolve("saves/w");
		PathResolver.Resolved r = PathResolver.resolveUnder("%world%/redtale-data", root, world);
		assertNotNull(r);
		assertEquals(world.resolve("redtale-data").toAbsolutePath().normalize(), r.path());
	}

	@Test
	void serverTokenStillWorksWithWorldKnown() {
		Path world = root.resolve("saves/w");
		PathResolver.Resolved r = PathResolver.resolveUnder("%server%/redtale-data", root, world);
		assertNotNull(r);
		assertEquals(root.resolve("redtale-data").toAbsolutePath().normalize(), r.path());
	}

	@Test
	void worldTokenTraversalStillRejected() {
		Path world = root.resolve("saves/w");
		assertNull(PathResolver.resolveUnder("%world%/../evil", root, world));
	}

	@Test
	void absolutePathIgnoresWorld() {
		Path target = root.resolve("abs");
		PathResolver.Resolved r = PathResolver.resolveUnder(target.toString(), root, root.resolve("saves/w"));
		assertNotNull(r);
		assertEquals(target.toAbsolutePath().normalize(), r.path());
	}

	// ---------------------------------------------------------------- 迁移

	@Test
	void mergeCopiesMissingAndKeepsExisting() throws IOException {
		Path src = root.resolve("legacy");
		Path dst = root.resolve("world/redtale-data");
		write(src.resolve("accounts/a.json"), "{\"a\":1}");
		write(src.resolve("board/board.json"), "{\"b\":2}");
		write(dst.resolve("accounts/a.json"), "{\"keep\":1}"); // 目标已有：不覆盖

		DataRoot.Report r = DataRoot.merge(src, dst, false);
		assertNotNull(r);
		assertEquals(1, r.copied(), "只应复制 board/board.json");
		assertEquals(1, r.skipped(), "已存在的 accounts/a.json 应跳过");
		assertEquals("{\"keep\":1}", Files.readString(dst.resolve("accounts/a.json")));
		assertEquals("{\"b\":2}", Files.readString(dst.resolve("board/board.json")));
		// 来源保留
		assertTrue(Files.isRegularFile(src.resolve("accounts/a.json")));
	}

	@Test
	void mergeSkipsEmptyPlaceholders() throws IOException {
		Path src = root.resolve("pending");
		Path dst = root.resolve("world/redtale-data");
		write(src.resolve("mail/inbox.json"), "");
		write(src.resolve("checkin/c.json"), "{\"d\":3}");

		DataRoot.Report r = DataRoot.merge(src, dst, true);
		assertNotNull(r);
		assertEquals(1, r.copied());
		assertFalse(Files.exists(dst.resolve("mail/inbox.json")), "0 字节占位文件不该搬过去");
		assertTrue(Files.isRegularFile(dst.resolve("checkin/c.json")));
	}

	@Test
	void mergeIntoMissingSourceIsNoop() {
		assertNull(DataRoot.merge(root.resolve("nope"), root.resolve("dst"), false));
	}

	@Test
	void migrationMarkerRoundTrip() throws IOException {
		Path dst = root.resolve("world/redtale-data");
		Files.createDirectories(dst);
		assertFalse(DataRoot.alreadyMigrated(dst, "legacy"));

		DataRoot.markMigrated(dst, "legacy");
		assertTrue(DataRoot.alreadyMigrated(dst, "legacy"));
		assertFalse(DataRoot.alreadyMigrated(dst, "pending"));

		DataRoot.markMigrated(dst, "pending");
		assertTrue(DataRoot.alreadyMigrated(dst, "legacy"));
		assertTrue(DataRoot.alreadyMigrated(dst, "pending"));
		// 坏标记不算迁移过（合并是幂等的，重跑安全）
		write(dst.resolve(DataRoot.MARKER), "{oops");
		assertFalse(DataRoot.alreadyMigrated(dst, "legacy"));
	}

	// ---------------------------------------------------------------- 旧配置升级

	@Test
	void legacyDefaultIsRecognised() {
		assertTrue(DataRoot.samePathText("%server%/redtale-data", "%SERVER%/redtale-data/"));
		assertTrue(DataRoot.samePathText("%server%\\redtale-data", " %server%/redtale-data "));
		assertFalse(DataRoot.samePathText("%server%/redtale-data", "%world%/redtale-data"));
		assertFalse(DataRoot.samePathText("D:/redtale-data", "%server%/redtale-data"));
	}

	@Test
	void defaultIsWorldScoped() {
		assertTrue(PathResolver.containsToken(DataRoot.DEFAULT, PathResolver.WORLD_TOKEN));
		assertTrue(PathResolver.containsToken(DataRoot.LEGACY_DEFAULT, PathResolver.SERVER_TOKEN));
	}

	private static void write(Path p, String text) throws IOException {
		Files.createDirectories(p.getParent());
		Files.writeString(p, text, StandardCharsets.UTF_8);
	}
}
