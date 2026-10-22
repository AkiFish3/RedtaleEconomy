package com.akira.redtale.economy.client.ui.hub;

/**
 * 余额显示的<b>唯一状态机</b>：服务端快照 → 屏幕上那个数字（A 第 N+78 轮）。
 *
 * <p>★为什么要有这一层★：侧栏头像卡、转账页、紧凑模式浮层各自拿着
 * {@code BalanceClientNetworking.RAW} 直接画，谁想加动画就各写一份。结果是同一个数字
 * 在三处不同步；更要命的是「一次失败的转账」在界面上会变成
 * <b>金额先快速减少、再立刻回升</b> —— 服务端并没有扣钱（UNKNOWN_PLAYER 在
 * {@code take()} 之前就返回了），可屏幕上已经演出了一次扣款。
 *
 * <p>定式两条，缺一不可：
 * <ol>
 *   <li><b>未知快照不参与插值</b>：{@code raw < 0} 表示「不知道余额」（Numismatic 拿不到组件、
 *       刚进服还没同步）。旧写法把它当数值插值，于是数字先往 -1 快速滑一段再弹回来，
 *       看上去就是「余额掉了一截又恢复」。</li>
 *   <li><b>下跌先沉降，回滚不落地</b>：向下变化先进 {@link #SETTLE_MS} 的待确认区，
 *       这段时间内没有更高值回来才真正生效。失败回滚（扣款包 + 恢复包、或先乐观再纠正）
 *       永远落在窗口内 ⇒ <b>屏幕上一次都不变化</b>；真实消费只是晚 300ms 滚下来。</li>
 * </ol>
 *
 * <p>无 MC 依赖，纯逻辑，可单测（{@code BalanceDisplayTest}）。
 */
public final class BalanceDisplay {

	/** 下跌的沉降窗口：这段时间内出现回滚 ⇒ 当作没发生过。 */
	public static final long SETTLE_MS = 300L;
	/** 确认后的数字滚动时长。 */
	public static final long ANIM_MS = 250L;

	/** 已确认的目标值（-1 = 还没收到过任何快照）。 */
	private static long target = -1;
	/** 当前应画出来的值。 */
	private static long shown = -1;
	/** 动画起点与开始时刻（{@code animating=false} 时不看）。 */
	private static long animFrom = -1;
	private static long animAt;
	private static boolean animating;
	/** 待确认的下跌值与其首次到达时刻。 */
	private static long pending = -1;
	private static long pendingAt;

	private BalanceDisplay() {
	}

	/** 清空（换世界 / 退出登录时调用，避免把上一局的数字滚到新局）。 */
	public static synchronized void reset() {
		target = -1;
		shown = -1;
		animFrom = -1;
		animAt = 0;
		animating = false;
		pending = -1;
		pendingAt = 0;
	}

	/**
	 * 喂一个服务端快照。必须每帧调用（或在读显示值之前调用）。
	 *
	 * @param raw 服务端快照；{@code <0} = 未知，<b>直接忽略</b>
	 */
	public static synchronized void feed(long raw, long now) {
		if (raw < 0) return;
		if (target < 0) {                       // 首个快照：直接落定，不做动画
			target = raw;
			shown = raw;
			animating = false;
			pending = -1;
			return;
		}
		if (raw == target) {                    // 回到已确认值 ⇒ 之前的下跌作废（回滚）
			pending = -1;
			return;
		}
		if (raw > target) {                     // 上涨：立即生效（玩家刚买到钱/签到）
			pending = -1;
			retarget(raw, now);
			return;
		}
		// 下跌：先进沉降区，等 SETTLE_MS
		if (pending < 0) pendingAt = now;       // 首次到达的时间为准（窗口不因后续包被无限延长）
		pending = raw;
	}

	/** 当前应显示的值（-1 = 还不知道，调用方画「…」）。 */
	public static synchronized long value(long now) {
		settle(now);
		if (target < 0) return -1;
		if (!animating) return target;
		long el = now - animAt;
		if (el >= ANIM_MS) {
			animating = false;
			shown = target;
			return target;
		}
		float k = el / (float) ANIM_MS;
		shown = animFrom + (long) ((target - animFrom) * k);
		return shown;
	}

	/** 沉降窗口到点则把待确认的下跌落地（{@link #value} 与渲染循环都会调）。 */
	public static synchronized void settle(long now) {
		if (pending < 0 || now - pendingAt < SETTLE_MS) return;
		long v = pending;
		pending = -1;
		retarget(v, now);
	}

	/** 是否真有「待确认的下跌」（探针/单测用）。 */
	public static synchronized boolean hasPending() {
		return pending >= 0;
	}

	private static void retarget(long v, long now) {
		animFrom = target < 0 ? v : value0(now);
		target = v;
		animAt = now;
		animating = true;
	}

	/** 不触发 settle 的当前值（给 retarget 当动画起点用）。 */
	private static long value0(long now) {
		if (target < 0) return -1;
		if (!animating) return target;
		long el = now - animAt;
		if (el >= ANIM_MS) return target;
		float k = el / (float) ANIM_MS;
		return animFrom + (long) ((target - animFrom) * k);
	}
}
