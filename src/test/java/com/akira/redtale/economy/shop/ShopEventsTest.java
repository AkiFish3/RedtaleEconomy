package com.akira.redtale.economy.shop;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** T13：成交事件的投递与隔离。 */
class ShopEventsTest {

	@AfterEach
	void cleanup() {
		ShopEvents.clearAll();
	}

	private static ShopEvents.Transaction tx(boolean buy) {
		return new ShopEvents.Transaction(UUID.randomUUID(), "general", "minecraft:stone",
				3, 100, 300, 700, buy);
	}

	@Test
	void listenersReceiveTransaction() {
		List<ShopEvents.Transaction> got = new ArrayList<>();
		ShopEvents.listen(got::add);
		ShopEvents.Transaction t = tx(true);
		ShopEvents.fire(t);
		assertEquals(1, got.size());
		assertEquals(t, got.get(0));
		assertTrue(got.get(0).buy());
		assertEquals(300L, got.get(0).total());
	}

	@Test
	void brokenListenerDoesNotStopOthers() {
		List<String> order = new ArrayList<>();
		ShopEvents.listen(t -> {
			throw new IllegalStateException("第三方炸了");
		});
		ShopEvents.listen(t -> order.add("second"));
		ShopEvents.fire(tx(false));
		assertEquals(List.of("second"), order, "前一个监听器抛异常不能中断后面的人");
	}

	@Test
	void unlistenRemoves() {
		List<String> order = new ArrayList<>();
		java.util.function.Consumer<ShopEvents.Transaction> l = t -> order.add("x");
		ShopEvents.listen(l);
		ShopEvents.unlisten(l);
		ShopEvents.fire(tx(true));
		assertTrue(order.isEmpty());
		assertEquals(0, ShopEvents.listenerCount());
	}

	@Test
	void nullTransactionIsIgnored() {
		List<String> order = new ArrayList<>();
		ShopEvents.listen(t -> order.add("x"));
		ShopEvents.fire(null);
		assertFalse(order.size() > 0);
		assertEquals(0L, ShopEvents.firedCount(), "null 不该计入发布数（否则诊断命令会虚报）");
		assertNull(ShopEvents.last());
	}

	@Test
	void firedCounterTracksDeliveriesEvenWithoutListeners() {
		ShopEvents.Transaction t = tx(false);
		ShopEvents.fire(t);
		assertEquals(1L, ShopEvents.firedCount(), "没有监听器也要计数：这是 /shop admin events 的唯一运行时证据");
		assertEquals(t, ShopEvents.last());
	}
}
