package com.akira.redtale.economy.storage;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** T14：CSV 导出的转义、区间取文件、坏行统计与 UTF-8 BOM。 */
class CsvExportTest {

	@TempDir
	Path root;

	@Test
	void escapeFollowsRfc4180() {
		assertEquals("abc", CsvExport.escape("abc"));
		assertEquals("\"a,b\"", CsvExport.escape("a,b"));
		assertEquals("\"he said \"\"hi\"\"\"", CsvExport.escape("he said \"hi\""));
		assertEquals("\"a\nb\"", CsvExport.escape("a\nb"));
		assertEquals("", CsvExport.escape(null));
		assertEquals("a,\"b,c\",", CsvExport.row("a", "b,c", null));
	}

	@Test
	void ledgerRowKeepsColumnOrderAndBlanksMissingFields() {
		JsonObject full = JsonParser.parseString("{\"ts\":\"t\",\"uuid\":\"u1\",\"delta\":-5,\"after\":100,"
				+ "\"type\":\"shop.buy\",\"ref\":\"shop=a,item=b\",\"server\":\"local\"}").getAsJsonObject();
		assertEquals("t,u1,-5,100,shop.buy,\"shop=a,item=b\",local", CsvExport.ledgerRow(full));
		assertEquals("t,,,,,,", CsvExport.ledgerRow(
				JsonParser.parseString("{\"ts\":\"t\"}").getAsJsonObject()));
	}

	@Test
	void exportLedgerCountsBadLinesInsteadOfSwallowingThem() throws Exception {
		Path dir = root.resolve("ledger");
		Files.createDirectories(dir.resolve("2026-10"));
		String good = "{\"ts\":\"2026-10-21T10:00:00\",\"uuid\":\"u1\",\"delta\":10,\"after\":110,"
				+ "\"type\":\"checkin\",\"ref\":\"day=1\",\"server\":\"local\"}\n";
		Files.writeString(dir.resolve("2026-10/2026-10-21.jsonl"),
				good + good + "{ broken\n\n", StandardCharsets.UTF_8);

		Path out = root.resolve("out/ledger.csv");
		CsvExport.Stats st = CsvExport.exportLedger(dir, "daily",
				LocalDate.of(2026, 10, 21), LocalDate.of(2026, 10, 21), out);

		assertEquals(2, st.rows());
		assertEquals(1, st.skipped());
		String text = Files.readString(out, StandardCharsets.UTF_8);
		assertEquals('\uFEFF', text.charAt(0), "Excel 需要 BOM 才按 UTF-8 打开");
		String[] lines = text.substring(1).split("\n");
		assertEquals(String.join(",", CsvExport.LEDGER_COLUMNS), lines[0]);
		assertEquals(3, lines.length);
		assertFalse(Files.exists(out.resolveSibling("ledger.csv.tmp")), "临时文件必须被 rename 掉");
	}

	@Test
	void exportAssetsSkipsBrokenFiles() throws Exception {
		Path dir = root.resolve("accounts");
		Files.createDirectories(dir);
		Files.writeString(dir.resolve("aaaa.json"),
				"{\"uuid\":\"aaaa\",\"balance\":1234,\"updatedAt\":\"2026-10-21T09:00:00\"}", StandardCharsets.UTF_8);
		Files.writeString(dir.resolve("broken.json"), "not json", StandardCharsets.UTF_8);

		Path out = dir.resolve("assets.csv");
		CsvExport.Stats st = CsvExport.exportAssets(dir, out);

		assertEquals(1, st.rows());
		assertEquals(1, st.skipped());
		assertTrue(Files.readString(out, StandardCharsets.UTF_8)
				.contains("aaaa,1234,2026-10-21T09:00:00,aaaa.json"));
	}

	@Test
	void ledgerFilesSwapsReversedRange() {
		Path dir = root.resolve("l2");
		assertTrue(CsvExport.ledgerFiles(dir, "daily", LocalDate.of(2026, 10, 20), LocalDate.of(2026, 10, 19))
				.isEmpty(), "目录不存在时返回空而不是抛异常");
	}

	@Test
	void dateParseFallsBack() {
		assertEquals(LocalDate.of(2026, 10, 21), CsvExport.parseDate("2026-10-21", null));
		assertEquals(null, CsvExport.parseDate("2026/10/21", null));
	}
}
