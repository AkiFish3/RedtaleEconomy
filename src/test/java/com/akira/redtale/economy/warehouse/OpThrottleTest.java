package com.akira.redtale.economy.warehouse;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 仓储操作节流（A 第 N+76 轮）。
 *
 * <p>节流写错的两个方向都很难看：太松 = 刷包能把磁盘打满；太紧 = 正常拖拽/连点被吞，
 * 玩家看到「点了没反应」且日志只有一行 30 秒才出一次的 INFO。所以窗口边界要钉住。
 */
class OpThrottleTest {

	private static final long T0 = 1_700_000_000_000L; // 秒边界对齐：T0/1000 是整数秒起点

	@Test
	void allowsExactlyPerSecondThenDrops() {
		OpThrottle t = new OpThrottle(3);
		UUID p = UUID.randomUUID();
		assertTrue(t.allow(p, T0));
		assertTrue(t.allow(p, T0 + 10));
		assertTrue(t.allow(p, T0 + 20));
		assertFalse(t.allow(p, T0 + 30), "第 4 次必须被丢");
		assertFalse(t.allow(p, T0 + 999), "同一秒内后续全部丢弃");
	}

	@Test
	void resetsOnNextSecond() {
		OpThrottle t = new OpThrottle(2);
		UUID p = UUID.randomUUID();
		assertTrue(t.allow(p, T0));
		assertTrue(t.allow(p, T0));
		assertFalse(t.allow(p, T0 + 500));
		// 跨过整秒 ⇒ 计数归零，不累积
		assertTrue(t.allow(p, T0 + 1000));
		assertTrue(t.allow(p, T0 + 1000));
		assertFalse(t.allow(p, T0 + 1000));
	}

	@Test
	void playersAreIndependent() {
		OpThrottle t = new OpThrottle(1);
		UUID a = UUID.randomUUID();
		UUID b = UUID.randomUUID();
		assertTrue(t.allow(a, T0));
		assertFalse(t.allow(a, T0));
		assertTrue(t.allow(b, T0), "别人的窗口不能被同一个人打满");
	}

	@Test
	void defaultBudgetCoversHumanInput() {
		// 一次拖拽最多 1 个 spread 包（客户端把经过的格子合并发送），连点也远低于 80/秒
		assertTrue(OpThrottle.DEFAULT_PER_SECOND >= 40);
		assertFalse(OpThrottle.defaults().allow(null, T0), "空 uuid 不放行");
	}

	@Test
	void forgetDropsWindow() {
		OpThrottle t = new OpThrottle(1);
		UUID p = UUID.randomUUID();
		assertTrue(t.allow(p, T0));
		assertEquals(1, t.size());
		t.forget(p);
		assertEquals(0, t.size());
		assertTrue(t.allow(p, T0), "退出后再进来不该被旧计数卡住");
	}
}
