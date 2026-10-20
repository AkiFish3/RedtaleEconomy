package com.akira.redtale.economy.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R38b：跨天判定纯函数（不依赖服务器实例）。
 *
 * <p>这条判定是整个跨天机制的闸门：
 * <ul>
 *   <li>开服时 {@code lastFired} 取磁盘记录 —— 停机跨天就是靠它发现「昨天没跑」</li>
 *   <li>定时器/登录兜底都走同一个判定，保证同一天重复触发是空操作</li>
 * </ul>
 */
class DayRolloverTest {

	@Test
	void sameDayDoesNothing() {
		assertFalse(DayRollover.needsRollover("2026-10-20", "2026-10-20"));
	}

	@Test
	void crossDayFires() {
		assertTrue(DayRollover.needsRollover("2026-10-19", "2026-10-20"));
	}

	@Test
	void multiDayDowntimeFiresOnce() {
		// 停机三天：开服一次补跑即可（回调幂等），不按天数补多次
		assertTrue(DayRollover.needsRollover("2026-10-17", "2026-10-20"));
	}

	@Test
	void missingRecordIsHandledByCaller() {
		// null/空 表示「首次安装」——调用方会直接把今天写为基线，不该在这里判 true 触发补跑
		assertFalse(DayRollover.needsRollover(null, null));
		assertFalse(DayRollover.needsRollover("2026-10-20", ""));
	}
}
