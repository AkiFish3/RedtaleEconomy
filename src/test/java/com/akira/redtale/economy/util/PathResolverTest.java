package com.akira.redtale.economy.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** B06：路径解析四条规则（不依赖 FabricLoader 的纯逻辑）。 */
class PathResolverTest {

	@TempDir
	Path root;

	@Test
	void serverTokenExpandsToRoot() {
		PathResolver.Resolved r = PathResolver.resolveUnder("%server%/redtale-data", root);
		assertNotNull(r);
		assertEquals(root.resolve("redtale-data").toAbsolutePath().normalize(), r.path());
		assertFalse(r.fellBack());
	}

	@Test
	void relativePathJoinsRoot() {
		PathResolver.Resolved r = PathResolver.resolveUnder("data/sub", root);
		assertNotNull(r);
		assertEquals(root.resolve("data/sub").toAbsolutePath().normalize(), r.path());
	}

	@Test
	void absolutePathIsKept() {
		Path target = root.resolve("abs-dir");
		PathResolver.Resolved r = PathResolver.resolveUnder(target.toString(), root);
		assertNotNull(r);
		assertEquals(target.toAbsolutePath().normalize(), r.path());
	}

	@Test
	void traversalIsRejected() {
		assertNull(PathResolver.resolveUnder("%server%/../evil", root));
		assertNull(PathResolver.resolveUnder("../../etc", root));
	}

	@Test
	void blankIsRejected() {
		assertNull(PathResolver.resolveUnder("", root));
		assertNull(PathResolver.resolveUnder("   ", root));
	}

	@Test
	void upperCaseTokenAlsoWorks() {
		PathResolver.Resolved r = PathResolver.resolveUnder("%SERVER%/x", root);
		assertNotNull(r);
		assertTrue(r.path().startsWith(root.toAbsolutePath().normalize()));
	}
}
