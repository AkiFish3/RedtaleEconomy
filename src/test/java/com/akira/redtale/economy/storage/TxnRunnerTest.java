package com.akira.redtale.economy.storage;

import com.akira.redtale.economy.shop.CompensationQueue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** T34：退款 / 回退 / 审计三条路径的口径。 */
class TxnRunnerTest {

	private static final UUID WHO = UUID.fromString("00000000-0000-0000-0000-0000000000aa");

	/** 记录调用的假资金通道。 */
	private static final class Sink implements TxnRunner.Money {
		final List<String> calls = new ArrayList<>();
		boolean fail;

		@Override
		public void add(UUID uuid, long amount, String ledgerType, String ref) {
			if (fail) throw new IllegalStateException("backend down");
			calls.add(uuid + "|" + amount + "|" + ledgerType + "|" + ref);
		}
	}

	private Path root;

	@BeforeEach
	void setUp(@TempDir Path tmp) {
		root = tmp;
		CompensationQueue.init(tmp);
		CompensationQueue.drain();
	}

	private List<String> auditLines() throws Exception {
		Path f = root.resolve("shop").resolve("compensations.jsonl");
		if (!Files.isRegularFile(f)) return List.of();
		return Files.readAllLines(f, StandardCharsets.UTF_8);
	}

	@Test
	void refundAddsMoneyAndWritesAuditLine() throws Exception {
		Sink sink = new Sink();
		TxnRunner.Outcome out = TxnRunner.refund(sink, WHO, 150, Ledger.TYPE_SHOP_BUY, "shop1/stonex3",
				new TxnRunner.Note(WHO, "shop1", "minecraft:stone", 3, 150, "inventory full"));

		assertEquals(TxnRunner.Outcome.REFUNDED, out);
		assertEquals(List.of(WHO + "|150|" + Ledger.TYPE_SHOP_BUY + "|shop1/stonex3"), sink.calls);

		List<String> lines = auditLines();
		assertEquals(1, lines.size(), "失败路径必须留一条补偿记录");
		String line = lines.get(0);
		assertTrue(line.contains("refunded: "), line);
		assertTrue(line.contains("shop1"), line);
		assertTrue(line.contains("minecraft:stone"), line);
		assertFalse(line.contains("\n"));
	}

	@Test
	void refundFailureIsReportedNotThrown() throws Exception {
		Sink sink = new Sink();
		sink.fail = true;
		TxnRunner.Outcome out = TxnRunner.refund(sink, WHO, 99, Ledger.TYPE_MARKET_BUY, "market:buy:refund:of_1",
				new TxnRunner.Note(WHO, "market", "of_1", 0, 99, "save failed"));

		assertEquals(TxnRunner.Outcome.FAILED, out, "退款失败要回 FAILED，不能把异常甩给命令层");
		List<String> lines = auditLines();
		assertEquals(1, lines.size());
		assertTrue(lines.get(0).contains("REFUND-FAILED: "), "人工补钱要靠这一条找账");
	}

	@Test
	void revertDoesNotTouchAuditFile() throws Exception {
		Sink sink = new Sink();
		assertEquals(TxnRunner.Outcome.REFUNDED,
				TxnRunner.revert(sink, WHO, 20, Ledger.TYPE_MARKET_AUCTION, "auction:outbid:of_2"));
		assertEquals(1, sink.calls.size());
		assertTrue(auditLines().isEmpty(), "日常回退不该污染补偿文件");
	}

	@Test
	void revertFailureIsReportedNotThrown() {
		Sink sink = new Sink();
		sink.fail = true;
		assertEquals(TxnRunner.Outcome.FAILED,
				TxnRunner.revert(sink, WHO, 20, Ledger.TYPE_MARKET_AUCTION, "auction:outbid:of_2"));
	}

	@Test
	void zeroAmountIsSkipped() throws Exception {
		Sink sink = new Sink();
		assertEquals(TxnRunner.Outcome.SKIPPED,
				TxnRunner.refund(sink, WHO, 0, Ledger.TYPE_ADMIN, "refund:none",
						new TxnRunner.Note(WHO, "shop1", "stone", 1, 0, "nothing to refund")));
		assertTrue(sink.calls.isEmpty());
		assertTrue(auditLines().isEmpty());
	}

	@Test
	void auditOnlyWritesReasonAsIs() throws Exception {
		TxnRunner.audit(new TxnRunner.Note(WHO, "shop1", "minecraft:stone", 5, 500, "sell-add-failed: boom"));
		List<String> lines = auditLines();
		assertEquals(1, lines.size());
		assertNotNull(lines.get(0));
		assertTrue(lines.get(0).contains("sell-add-failed: boom"));
	}
}
