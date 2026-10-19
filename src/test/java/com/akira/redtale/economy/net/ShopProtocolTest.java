package com.akira.redtale.economy.net;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 分页参数归一化（纯函数，防护「客户端乱传页码」这条攻击面）。 */
class ShopProtocolTest {

	@Test
	void totalPagesRoundsUp() {
		assertEquals(0, ShopProtocol.totalPages(0, 40));
		assertEquals(1, ShopProtocol.totalPages(1, 40));
		assertEquals(1, ShopProtocol.totalPages(40, 40));
		assertEquals(2, ShopProtocol.totalPages(41, 40));
		assertEquals(71, ShopProtocol.totalPages(2803, 40));
	}

	@Test
	void totalPagesGuardsBadPageSize() {
		assertEquals(0, ShopProtocol.totalPages(100, 0));
		assertEquals(0, ShopProtocol.totalPages(100, -5));
	}

	@Test
	void clampPageKeepsInsideRange() {
		assertEquals(0, ShopProtocol.clampPage(-1, 2803, 40));
		assertEquals(0, ShopProtocol.clampPage(0, 2803, 40));
		assertEquals(70, ShopProtocol.clampPage(9999, 2803, 40));
		assertEquals(5, ShopProtocol.clampPage(5, 2803, 40));
	}

	@Test
	void clampPageHandlesEmptyShop() {
		assertEquals(0, ShopProtocol.clampPage(0, 0, 40));
		assertEquals(0, ShopProtocol.clampPage(7, 0, 40));
	}
}
