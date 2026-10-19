package com.akira.redtale.economy.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** B22 identity 迁移：别名链解析与持久化。 */
class IdentityMapTest {

	@Test
	void resolvesSingleHop() {
		Map<String, String> m = Map.of("a", "b");
		assertEquals("b", IdentityMap.resolve(m, "a"));
	}

	@Test
	void resolvesChain() {
		Map<String, String> m = Map.of("a", "b", "b", "c");
		assertEquals("c", IdentityMap.resolve(m, "a"));
	}

	@Test
	void unknownPassesThrough() {
		assertEquals("x", IdentityMap.resolve(Map.of("a", "b"), "x"));
		assertEquals("x", IdentityMap.resolve(Map.of(), "x"));
		assertEquals("x", IdentityMap.resolve(null, "x"));
	}

	@Test
	void selfLoopIsNotFollowed() {
		assertEquals("a", IdentityMap.resolve(Map.of("a", "a"), "a"));
	}

	@Test
	void cycleStopsAndIsDetected() {
		Map<String, String> m = Map.of("a", "b", "b", "a");
		assertTrue(IdentityMap.isCyclic(m, "a"));
		assertFalse(IdentityMap.isCyclic(Map.of("a", "b"), "a"));
	}

	@Test
	void putThenLoadRoundTrips(@TempDir Path tmp) {
		assertTrue(IdentityMap.put(tmp, "old-uuid", "new-uuid", "Aki"));
		assertTrue(Files.isRegularFile(tmp.resolve(IdentityMap.FILE_NAME)));
		assertEquals("new-uuid", IdentityMap.load(tmp).get("old-uuid"));
		// 再写一条不覆盖旧的
		assertTrue(IdentityMap.put(tmp, "old2", "new2", null));
		Map<String, String> m = IdentityMap.load(tmp);
		assertEquals("new-uuid", m.get("old-uuid"));
		assertEquals("new2", m.get("old2"));
	}

	@Test
	void putRejectsSelfMappingAndMissingArgs(@TempDir Path tmp) {
		assertFalse(IdentityMap.put(tmp, "same", "same", "x"));
		assertFalse(IdentityMap.put(tmp, null, "b", "x"));
		assertFalse(IdentityMap.put(null, "a", "b", "x"));
		assertTrue(IdentityMap.load(tmp).isEmpty());
	}
}
