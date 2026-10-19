package com.akira.redtale.economy.shop;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Q2：补偿队列的单行 JSON 与落盘。 */
class CompensationQueueTest {

	private static CompensationQueue.Entry entry(String reason) {
		return new CompensationQueue.Entry("2026-10-20 07:20", "uuid-1", "shop1",
				"minecraft:stone", 3, 150, reason);
	}

	@Test
	void lineIsSingleLineJsonWithAllFields() {
		String line = CompensationQueue.line(entry("refunded: boom"));
		assertFalse(line.contains("\n"), "必须是单行，否则 jsonl 会被拆坏");
		for (String f : List.of("at", "player", "shopId", "itemId", "count", "amount", "reason")) {
			assertTrue(line.contains("\"" + f + "\""), "缺字段 " + f);
		}
		assertTrue(line.contains("refunded: boom"));
	}

	@Test
	void quotesAndBackslashesAreEscaped() {
		String line = CompensationQueue.line(entry("他说\"坏\" 且 \\ 反斜杠"));
		assertTrue(line.contains("\\\""));
		assertFalse(line.contains("\n"));
	}

	@Test
	void enqueueAppendsToJsonl(@TempDir Path tmp) throws Exception {
		CompensationQueue.init(tmp);
		CompensationQueue.enqueue(entry("a"));
		CompensationQueue.enqueue(entry("b"));
		Path f = CompensationQueue.file();
		assertNotNull(f);
		assertTrue(Files.isRegularFile(f));
		List<String> lines = Files.readAllLines(f);
		assertEquals(2, lines.size());
		assertTrue(lines.get(0).contains("\"a\""));
		assertTrue(lines.get(1).contains("\"b\""));
		assertEquals(2, CompensationQueue.pending());
		assertEquals(2, CompensationQueue.drain().size());
		assertEquals(0, CompensationQueue.pending());
	}
}
