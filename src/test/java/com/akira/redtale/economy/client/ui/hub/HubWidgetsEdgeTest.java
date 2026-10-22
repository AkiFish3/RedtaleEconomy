package com.akira.redtale.economy.client.ui.hub;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Deque;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 圆角描边闭合性 + 按钮边框语义回归（第 N+73 轮）。
 *
 * <p>缺陷原样：{@link HubWidgets#outline} 只画四条直边，半径 r 的四角一个像素都不画
 * ⇒ 每个角缺一个 L 形缺口，边框断成四段。{@code Variant.OUTLINE} 又用
 * {@link HubTheme#PRIMARY} 画常态边框 ⇒ 「金框」同时代表「样式」和「鼠标悬停」，
 * 用户无法区分。本测试锁两件事：① 一圈必须连通闭合；② 金色只出现在交互态。
 */
class HubWidgetsEdgeTest {

	// ---------- 几何 ----------

	private static boolean[][] mask(int x, int y, int w, int h, int r) {
		boolean[][] m = new boolean[h][w];
		int[] seg = HubWidgets.outlineSegments(x, y, w, h, r);
		for (int i = 0; i < seg.length; i += 4) {
			for (int py = seg[i + 1]; py < seg[i + 3]; py++) {
				for (int px = seg[i]; px < seg[i + 2]; px++) {
					if (px >= x && px < x + w && py >= y && py < y + h) {
						m[py - y][px - x] = true;
					}
				}
			}
		}
		return m;
	}

	/** 8 邻接连通块数量（圆角阶梯是斜向相接，4 邻接会误判成断）。 */
	private static int components(boolean[][] m) {
		int h = m.length;
		int w = m[0].length;
		boolean[][] seen = new boolean[h][w];
		int n = 0;
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				if (!m[y][x] || seen[y][x]) continue;
				n++;
				Deque<int[]> q = new ArrayDeque<>();
				q.add(new int[] { x, y });
				seen[y][x] = true;
				while (!q.isEmpty()) {
					int[] p = q.poll();
					for (int dy = -1; dy <= 1; dy++) {
						for (int dx = -1; dx <= 1; dx++) {
							int nx = p[0] + dx;
							int ny = p[1] + dy;
							if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue;
							if (!m[ny][nx] || seen[ny][nx]) continue;
							seen[ny][nx] = true;
							q.add(new int[] { nx, ny });
						}
					}
				}
			}
		}
		return n;
	}

	/** ① 四角补齐后是一整圈：任意半径都只有 1 个连通块（修复前是 4 个独立直边）。 */
	@Test
	void outlineIsOneClosedRingForEveryRadius() {
		for (int r = 0; r <= 4; r++) {
			boolean[][] m = mask(0, 0, 60, 20, r);
			assertEquals(1, components(m), "r=" + r + " 的描边断开了");
		}
	}

	/** ② 逐角逐行点名：缺口正是「四角一个像素都没有」。 */
	@Test
	void cornersHaveNoGap() {
		int w = 60;
		int h = 20;
		for (int r = 1; r <= 4; r++) {
			boolean[][] m = mask(0, 0, w, h, r);
			for (int i = 0; i < r; i++) {
				int lx = r - 1 - i;
				int rx = w - r + i;
				assertTrue(m[i][lx], "左上角缺 r=" + r + " i=" + i);
				assertTrue(m[i][rx], "右上角缺 r=" + r + " i=" + i);
				assertTrue(m[h - 1 - i][lx], "左下角缺 r=" + r + " i=" + i);
				assertTrue(m[h - 1 - i][rx], "右下角缺 r=" + r + " i=" + i);
			}
		}
	}

	/** ③ 描边不越出按钮矩形，也不往内部糊（内部区域必须整片空）。 */
	@Test
	void outlineStaysOnTheBorder() {
		int w = 60;
		int h = 20;
		int r = 3;
		boolean[][] m = mask(0, 0, w, h, r);
		for (int y = r; y < h - r; y++) {
			for (int x = r; x < w - r; x++) {
				assertFalse(m[y][x], "描边糊进内部 (" + x + "," + y + ")");
			}
		}
	}

	/** ④ 退化尺寸不许抛异常（下拉、小按钮会传很小的 h）。 */
	@Test
	void degenerateSizesAreSafe() {
		assertEquals(0, HubWidgets.outlineSegments(0, 0, 0, 10, 3).length);
		assertEquals(0, HubWidgets.outlineSegments(0, 0, 10, 0, 3).length);
		// 1×1 / 2×2：半径被钳到 h/2，仍然画得出来且不越界
		assertEquals(0, components(mask(0, 0, 1, 1, 3)) - 1);
		assertEquals(0, components(mask(0, 0, 2, 2, 3)) - 1);
		// 半径大于半边：钳到半边，等价于胶囊
		assertEquals(components(mask(0, 0, 20, 8, 99)), components(mask(0, 0, 20, 8, 4)));
	}

	// ---------- 语义：金色只属于交互态 ----------

	/** ⑤ 描边按钮常态不许是金色（否则分不清是样式还是悬停）。 */
	@Test
	void idleOutlineIsNotGold() {
		int idle = HubWidgets.edgeColor(HubWidgets.Variant.OUTLINE, false, true);
		assertNotEquals(HubTheme.PRIMARY, idle, "常态描边不能是主色");
		assertNotEquals(HubTheme.PRIMARY_HOVER, idle, "常态描边不能是悬停色");
		assertEquals(HubTheme.EDGE_IDLE, idle);
	}

	/** ⑥ 悬停才转金：这是「鼠标在这里」的唯一信号。 */
	@Test
	void hoverOutlineTurnsGold() {
		assertEquals(HubTheme.PRIMARY, HubWidgets.edgeColor(HubWidgets.Variant.OUTLINE, true, true));
	}

	/** ⑦ 禁用态回到灰描边，且与常态/悬停都不同色（三态可辨）。 */
	@Test
	void disabledEdgeIsNeutralAndDistinct() {
		int dis = HubWidgets.edgeColor(HubWidgets.Variant.OUTLINE, false, false);
		int idle = HubWidgets.edgeColor(HubWidgets.Variant.OUTLINE, false, true);
		int hov = HubWidgets.edgeColor(HubWidgets.Variant.OUTLINE, true, true);
		assertEquals(HubTheme.OUTLINE, dis);
		assertNotEquals(idle, hov);
		assertNotEquals(dis, hov);
	}

	/** ⑧ 实心变体不描边（主/危险/开关/幽灵），避免又冒出一圈金线。 */
	@Test
	void filledVariantsHaveNoOutline() {
		for (HubWidgets.Variant v : new HubWidgets.Variant[] {
				HubWidgets.Variant.PRIMARY, HubWidgets.Variant.DESTRUCTIVE,
				HubWidgets.Variant.GHOST, HubWidgets.Variant.TOGGLE }) {
			assertEquals(0, HubWidgets.edgeColor(v, false, true), v + " 常态不该有描边");
			assertEquals(0, HubWidgets.edgeColor(v, true, true), v + " 悬停也不该有描边");
		}
	}

	/**
	 * ⑨ 检测器负样本：把「修复前」的形状（只有四条直边、四角空着）喂进同一个
	 * {@link #components} —— 必须数出 4 块，否则上面的断言全是假绿。
	 */
	@Test
	void detectorCatchesTheOldBrokenShape() {
		int w = 60;
		int h = 20;
		int r = 3;
		boolean[][] old = new boolean[h][w];
		for (int x = r; x < w - r; x++) {
			old[0][x] = true;
			old[h - 1][x] = true;
		}
		for (int y = r; y < h - r; y++) {
			old[y][0] = true;
			old[y][w - 1] = true;
		}
		assertEquals(4, components(old), "旧形状应被识别成 4 段断边");
		assertEquals(1, components(mask(0, 0, w, h, r)), "新形状必须是 1 圈");
	}
}
