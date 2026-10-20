package com.akira.redtale.economy.net;

import io.netty.buffer.Unpooled;
import net.minecraft.network.PacketByteBuf;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** U2：任务快照的编解码往返。 */
class TaskProtocolTest {

	private static TaskProtocol.Snapshot sample() {
		return new TaskProtocol.Snapshot("2026-10-20", 2, List.of(
				new TaskProtocol.Task(0, 500, "submit", false,
						List.of(new TaskProtocol.Item("minecraft:stone", 16, 7),
								new TaskProtocol.Item("minecraft:oak_log", 8, 8)), "木材采集"),
				new TaskProtocol.Task(1, 1200, "detect", true,
						List.of(new TaskProtocol.Item("minecraft:iron_ingot", 3, 3)), "矿物采集")));
	}

	@Test
	void roundTripKeepsEverything() {
		PacketByteBuf buf = new PacketByteBuf(Unpooled.buffer());
		TaskProtocol.write(buf, sample());
		TaskProtocol.Snapshot r = TaskProtocol.read(buf);

		assertEquals("2026-10-20", r.date());
		assertEquals(2, r.refreshesLeft());
		assertEquals(2, r.tasks().size());

		TaskProtocol.Task t0 = r.tasks().get(0);
		assertEquals(0, t0.slot());
		assertEquals(500, t0.reward());
		assertEquals("submit", t0.mode());
		assertFalse(t0.claimed());
		assertEquals(2, t0.items().size());
		assertEquals("minecraft:stone", t0.items().get(0).id());
		assertEquals(16, t0.items().get(0).need());
		assertEquals(7, t0.items().get(0).have());

		TaskProtocol.Task t1 = r.tasks().get(1);
		assertEquals(1, t1.slot());
		assertTrue(t1.claimed());
		assertEquals("detect", t1.mode());
		// U6：任务卡标题要能过协议（否则界面只剩 "#0"）
		assertEquals("木材采集", t0.label());
		assertEquals("矿物采集", t1.label());
	}

	@Test
	void emptySnapshotRoundTrips() {
		PacketByteBuf buf = new PacketByteBuf(Unpooled.buffer());
		TaskProtocol.write(buf, TaskProtocol.Snapshot.empty());
		TaskProtocol.Snapshot r = TaskProtocol.read(buf);
		assertEquals("", r.date());
		assertEquals(0, r.tasks().size());
	}

	@Test
	void negativeCountsAreClampedToZeroOnWrite() {
		TaskProtocol.Snapshot s = new TaskProtocol.Snapshot("d", -3, List.of(
				new TaskProtocol.Task(0, 0, "submit", false,
						List.of(new TaskProtocol.Item("x", -5, -9)), "")));
		PacketByteBuf buf = new PacketByteBuf(Unpooled.buffer());
		TaskProtocol.write(buf, s);
		TaskProtocol.Snapshot r = TaskProtocol.read(buf);
		assertEquals(0, r.refreshesLeft());
		assertEquals(0, r.tasks().get(0).items().get(0).need());
		assertEquals(0, r.tasks().get(0).items().get(0).have());
	}
}
