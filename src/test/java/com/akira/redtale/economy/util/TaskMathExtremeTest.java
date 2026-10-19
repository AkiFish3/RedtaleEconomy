package com.akira.redtale.economy.util;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** B05：权重算法的极端输入（budget=1、kinds 多于物品、全同权重、超大预算、负数）。 */
class TaskMathExtremeTest {

	@Test
	void budgetOneOnlySolvableWithWeightOne() {
		int[] ok = TaskMath.solve(1, new int[]{1});
		assertArrayEquals(new int[]{1}, ok);

		// 唯一物品权重 2 > 预算 1，且每项至少 1 ⇒ 无解
		assertNull(TaskMath.solve(1, new int[]{2}));
	}

	@Test
	void kindsGreaterThanBudgetWithAllWeightOne() {
		// 5 种物品各至少 1 ⇒ 最少 5，预算 3 无解
		assertNull(TaskMath.solve(3, new int[]{1, 1, 1, 1, 1}));
		int[] counts = TaskMath.solve(5, new int[]{1, 1, 1, 1, 1});
		assertNotNull(counts);
		assertArrayEquals(new int[]{1, 1, 1, 1, 1}, counts);
	}

	@Test
	void identicalWeightsDistributeToExactTotal() {
		int[] w = {3, 3, 3, 3};
		assertNotNull(TaskMath.solve(12, w));
		int[] counts = TaskMath.solve(30, w);
		assertNotNull(counts);
		assertEquals(30, TaskMath.total(counts, w));
		assertTrue(counts[0] >= 1 && counts[1] >= 1 && counts[2] >= 1 && counts[3] >= 1);
	}

	@Test
	void zeroAndNegativeInputsAreRejected() {
		assertNull(TaskMath.solve(0, new int[]{1, 2}));
		assertNull(TaskMath.solve(-5, new int[]{1, 2}));
		assertNull(TaskMath.solve(10, new int[]{}));
		// 权重 <= 0 直接判无解（曾因负数导致 r - w[i] > R 越界，2026-10-20 修）
		assertNull(TaskMath.solve(10, new int[]{0, 2}));
		assertNull(TaskMath.solve(10, new int[]{-1, 2}));
	}

	@Test
	void hugeBudgetStaysExact() {
		int[] w = {1, 2, 3};
		int budget = 1_000_000;
		int[] counts = TaskMath.solve(budget, w);
		assertNotNull(counts);
		assertEquals(budget, TaskMath.total(counts, w));
	}

	@Test
	void flexZeroMeansExactOnly() {
		// flex=0 时只接受 T==budget：可解与不可解各一例
		assertNotNull(TaskMath.solve(9, new int[]{4, 5}));
		assertEquals(9, TaskMath.total(TaskMath.solve(9, new int[]{4, 5}), new int[]{4, 5}));
	}

	@Test
	void randomExtremesNeverProduceIllegalSolution() {
		Random rnd = new Random(20261020L);
		for (int i = 0; i < 2000; i++) {
			int kinds = 1 + rnd.nextInt(6);
			int[] w = new int[kinds];
			for (int k = 0; k < kinds; k++) w[k] = 1 + rnd.nextInt(9);
			int budget = 1 + rnd.nextInt(200);
			int[] counts = TaskMath.solve(budget, w);
			if (counts == null) continue;
			assertEquals(kinds, counts.length);
			for (int c : counts) assertTrue(c >= 1, "每项至少 1");
			assertEquals(budget, TaskMath.total(counts, w), "Σc·w 必须精确等于预算");
		}
	}
}
