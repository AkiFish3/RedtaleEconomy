package com.akira.redtale.economy.util;

import com.akira.redtale.economy.RedtaleEconomy;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 日志规范小工具（B14）。
 *
 * <p>约定：
 * <ul>
 *   <li>{@code error} —— 数据写不进去 / 注册失败这类必须人工介入的事，每次都要打</li>
 *   <li>{@code warn} —— 可恢复异常（某个玩家的事件处理失败），同一个键 60 秒最多一条，避免每 tick 刷屏</li>
 *   <li>{@code info} —— 一次性状态（模块加载、目录解析、配置重载）</li>
 * </ul>
 *
 * <p>tick 循环里的异常一律走 {@link #warnOnce(String, String, Object...)}，
 * 否则一个坏掉的玩家能把 latest.log 写成几个 G。
 */
public final class Logs {

	private static final Map<String, Long> LAST = new ConcurrentHashMap<>();
	private static final long WINDOW_MS = 60_000L;

	private Logs() {
	}

	public static void warnOnce(String key, String format, Object... args) {
		long now = System.currentTimeMillis();
		Long last = LAST.get(key);
		if (last != null && now - last < WINDOW_MS) return;
		LAST.put(key, now);
		RedtaleEconomy.LOGGER.warn(format, args);
	}

	/** 仅供测试/热重载时清空限流表。 */
	public static void reset() {
		LAST.clear();
	}
}
