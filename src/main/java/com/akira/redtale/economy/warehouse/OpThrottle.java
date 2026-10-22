package com.akira.redtale.economy.warehouse;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 仓储操作节流（纯逻辑，时钟从外面传 ⇒ 可单测）。
 *
 * <p>为什么需要：每个仓储操作末尾都 {@code WarehouseData.save()}，那是「整份 JSON
 * 序列化 + tmp 写 + 原子 move」，成本固定在毫秒级。真人点击 ~10 次/秒，不会有事；
 * 但改过的客户端可以一秒发几千包，主线程与磁盘会一起被拖住。
 *
 * <p>窗口是「按秒重置的计数器」，不是滑动窗口：实现简单、单测可断言，且 80/秒 的余量
 * 足够任何正常操作（一次拖拽只发 1 个 spread 包）。
 */
public final class OpThrottle {

	/** 默认每秒允许次数。 */
	public static final int DEFAULT_PER_SECOND = 80;

	private final int perSecond;
	private final Map<UUID, int[]> window = new HashMap<>();

	public OpThrottle(int perSecond) {
		this.perSecond = Math.max(1, perSecond);
	}

	public static OpThrottle defaults() {
		return new OpThrottle(DEFAULT_PER_SECOND);
	}

	/**
	 * 记一次操作并判定是否放行。
	 *
	 * @param nowMs 当前毫秒时间（只用来算「第几秒」）
	 */
	public boolean allow(UUID id, long nowMs) {
		if (id == null) return false;
		int sec = (int) (nowMs / 1000L);
		int[] w = window.computeIfAbsent(id, k -> new int[]{-1, 0});
		if (w[0] != sec) {
			w[0] = sec;
			w[1] = 0;
		}
		return ++w[1] <= perSecond;
	}

	/** 玩家退出时清计数，避免长期在线的人表越攒越大。 */
	public void forget(UUID id) {
		if (id != null) window.remove(id);
	}

	public int size() {
		return window.size();
	}
}
