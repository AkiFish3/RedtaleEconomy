package com.akira.redtale.economy.util;

import java.util.Arrays;

/** 任务预算求解（纯函数，无游戏依赖）。 */
public final class TaskMath {

	private TaskMath() {
	}

	/**
	 * 求 {@code c_i ≥ 1} 使 {@code Σ c_i·w_i == T}。
	 *
	 * <p>做法：每项先预置 1（占用 {@code S0 = Σw}），余量 {@code R = T - S0}
	 * 用「完全背包」拆成权重组合；{@code from[r]} 记录凑出 r 时最后用的硬币下标。
	 *
	 * @return 各物品数量；无整数解返回 {@code null}
	 */
	public static int[] solve(int T, int[] w) {
		if (w == null || w.length == 0) return null;
		if (T <= 0) return null;
		int s0 = 0;
		for (int x : w) {
			// 权重必须为正：0 会让「余量」永远凑不完，负数会越界（r - w[i] > R）
			if (x <= 0) return null;
			s0 += x;
		}
		int R = T - s0;
		if (R < 0) return null;
		if (R == 0) {
			int[] exact = new int[w.length];
			Arrays.fill(exact, 1); // 每项恰好 1 个就够预算
			return exact;
		}

		int[] from = new int[R + 1];
		Arrays.fill(from, -2);
		from[0] = -1;
		for (int r = 1; r <= R; r++) {
			for (int i = 0; i < w.length; i++) {
				if (w[i] <= r && from[r - w[i]] != -2) {
					from[r] = i;
					break;
				}
			}
		}
		if (from[R] == -2) return null;

		int[] counts = new int[w.length];
		Arrays.fill(counts, 1);
		int r = R;
		while (r > 0) {
			int i = from[r];
			counts[i]++;
			r -= w[i];
		}
		return counts;
	}

	/** 校验解是否满足预算（自检 / 单测用）。 */
	public static int total(int[] counts, int[] w) {
		int t = 0;
		for (int i = 0; i < counts.length; i++) t += counts[i] * w[i];
		return t;
	}
}
