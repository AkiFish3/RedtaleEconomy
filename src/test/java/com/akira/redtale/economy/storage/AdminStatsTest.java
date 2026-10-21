package com.akira.redtale.economy.storage;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * T28 控制台的纯函数部分。
 *
 * <p>只测 {@link AdminStats#age(long)}：指标采集要读配置层与市场目录（headless 下未初始化），
 * 属于运行时验收范围；而「时间怎么显示」是纯算术，写死口径能防漂移
 * （历史上出现过「0 秒前」「-1 分钟前」这类显示）。
 */
class AdminStatsTest {

	@Test
	void ageBucketsAreStable() {
		assertEquals("刚刚", AdminStats.age(0));
		assertEquals("刚刚", AdminStats.age(4_999));
		assertEquals("5 秒前", AdminStats.age(5_000));
		assertEquals("59 秒前", AdminStats.age(59_000));
		assertEquals("1 分钟前", AdminStats.age(60_000));
		assertEquals("59 分钟前", AdminStats.age(59 * 60_000L));
		assertEquals("1 小时前", AdminStats.age(60 * 60_000L));
		assertEquals("23 小时前", AdminStats.age(23 * 3_600_000L));
		assertEquals("1 天前", AdminStats.age(24 * 3_600_000L));
		assertEquals("3 天前", AdminStats.age(3 * 24 * 3_600_000L));
	}

	/** 时钟回拨（负差）不能显示成「-1 秒前」。 */
	@Test
	void negativeDeltaReadsAsJustNow() {
		assertEquals("刚刚", AdminStats.age(-1));
		assertEquals("刚刚", AdminStats.age(-60_000));
	}
}
