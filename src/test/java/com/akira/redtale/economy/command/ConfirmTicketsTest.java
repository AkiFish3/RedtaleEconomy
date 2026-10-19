package com.akira.redtale.economy.command;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** R25：二次确认票据（一次性/绑玩家/可作废/过期）。 */
class ConfirmTicketsTest {

	private static final UUID AKI = UUID.randomUUID();
	private static final UUID OTHER = UUID.randomUUID();

	@BeforeEach
	void reset() {
		ConfirmTickets.clearForTest();
	}

	@Test
	void issueThenTakeOnce() {
		ConfirmTickets.Ticket t = ConfirmTickets.issue(AKI, "shop.remove", "shop1|minecraft:stone");
		assertNotNull(t.code());
		assertEquals(8, t.code().length());
		assertEquals("shop.remove", t.action());
		assertEquals("shop1|minecraft:stone", t.payload());

		ConfirmTickets.Ticket got = ConfirmTickets.take(AKI, t.code());
		assertNotNull(got);
		// 一次性：第二次拿不到
		assertNull(ConfirmTickets.take(AKI, t.code()));
		assertEquals(0, ConfirmTickets.size());
	}

	@Test
	void otherPlayerCannotUseTicket() {
		ConfirmTickets.Ticket t = ConfirmTickets.issue(AKI, "shop.removeshop", "shop1");
		assertNull(ConfirmTickets.take(OTHER, t.code()));
		// 别人的票不该被消耗
		assertNotNull(ConfirmTickets.take(AKI, t.code()));
	}

	@Test
	void unknownCodeIsNull() {
		assertNull(ConfirmTickets.take(AKI, "NOPE1234"));
		assertNull(ConfirmTickets.take(AKI, ""));
		assertNull(ConfirmTickets.take(null, "ABCDEFGH"));
	}

	@Test
	void cancelInvalidates() {
		ConfirmTickets.Ticket t = ConfirmTickets.issue(AKI, "shop.remove", "s|i");
		assertTrue(ConfirmTickets.cancel(AKI, t.code()));
		assertNull(ConfirmTickets.take(AKI, t.code()));
		assertFalse(ConfirmTickets.cancel(AKI, t.code()));
	}

	@Test
	void codesAreUnique() {
		java.util.Set<String> seen = new java.util.HashSet<>();
		for (int i = 0; i < 200; i++) {
			assertTrue(seen.add(ConfirmTickets.issue(AKI, "a", "b").code()));
		}
	}

	@Test
	void expiredIsPure() {
		long now = 1_000_000L;
		assertTrue(ConfirmTickets.expired(now - 1, now));
		assertFalse(ConfirmTickets.expired(now + 1, now));
		assertFalse(ConfirmTickets.expired(now, now));
	}

	@Test
	void ttlFallbackWithoutConfig() {
		// 单测环境没有 ConfigManager，应回落到 300 秒而不是抛异常
		assertEquals(300, ConfirmTickets.ttlSeconds());
		assertFalse(ConfirmTickets.required());
	}
}
