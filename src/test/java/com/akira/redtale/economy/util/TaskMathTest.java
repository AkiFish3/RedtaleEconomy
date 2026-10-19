package com.akira.redtale.economy.util;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link TaskMath} 的纯逻辑单测：预算可解性 + 解的合法性（不依赖 Minecraft）。 */
class TaskMathTest {

	@Test
	void exactBudgetWithTwoWeights() {
		int[] counts = TaskMath.solve(32, new int[]{1, 2});
		assertNotNull(counts);
		assertEquals(32, TaskMath.total(counts, new int[]{1, 2}));
		assertTrue(counts[0] >= 1 && counts[1] >= 1);
	}

	@Test
	void minimumSumExceedsBudget() {
		// 5 + 7 = 12 > 10，每项至少 1 ⇒ 无解
		assertNull(TaskMath.solve(10, new int[]{5, 7}));
	}

	@Test
	void exactSumReturnsAllOnes() {
		assertArrayEquals(new int[]{1, 1}, TaskMath.solve(3, new int[]{1, 2}));
	}

	@Test
	void emptyPoolIsUnsolvable() {
		assertNull(TaskMath.solve(10, new int[0]));
		assertNull(TaskMath.solve(10, null));
	}

	@Test
	void negativeRemainderIsUnsolvable() {
		assertNull(TaskMath.solve(1, new int[]{4, 5}));
	}

	@Test
	void largeBudgetStaysExact() {
		int[] w = {2, 3, 7};
		int[] counts = TaskMath.solve(999, w);
		assertNotNull(counts);
		assertEquals(999, TaskMath.total(counts, w));
	}

	/** 2000 组随机用例：有解必须精确命中预算，无解必须与暴力枚举一致。 */
	@Test
	void randomCasesMatchBruteForce() {
		Random rnd = new Random(20261020L);
		for (int iter = 0; iter < 2000; iter++) {
			int k = 1 + rnd.nextInt(4);
			int[] w = new int[k];
			for (int i = 0; i < k; i++) w[i] = 1 + rnd.nextInt(9);
			int T = 2 + rnd.nextInt(160);
			int[] counts = TaskMath.solve(T, w);
			if (counts != null) {
				assertEquals(T, TaskMath.total(counts, w), "解与预算不符：" + T + " " + java.util.Arrays.toString(w));
				for (int c : counts) assertTrue(c >= 1, "数量必须 ≥1");
			} else {
				assertTrue(!bruteForceSolvable(T, w), "漏解：" + T + " " + java.util.Arrays.toString(w));
			}
		}
	}

	/** 小范围暴力：每项 1..T，检查是否真无解。 */
	private static boolean bruteForceSolvable(int T, int[] w) {
		return dfs(T, w, 0, 0);
	}

	private static boolean dfs(int T, int[] w, int idx, int sum) {
		if (idx == w.length) return sum == T;
		for (int c = 1; sum + c * w[idx] <= T; c++) {
			if (dfs(T, w, idx + 1, sum + c * w[idx])) return true;
		}
		return false;
	}
}
