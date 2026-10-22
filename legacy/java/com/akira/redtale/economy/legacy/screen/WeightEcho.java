package com.akira.redtale.economy.legacy.screen;

/**
 * 权重「乐观回显」跟踪器（第 61 轮）。
 *
 * <h2>为什么需要它</h2>
 * 编辑器改权重是「客户端算新值 → 发 {@code ACT_SET_WEIGHT} → 服务端改 → 回发完整 state」。
 * 旧实现每次点击都拿 {@link com.akira.redtale.economy.legacy.screen.client.PackageEditClientState}
 * 里的**镜像值**当基准（镜像只在收到的 state 包里更新）。于是：在回包到达之前连点两次 / 长按连发
 * 的每一次，都从同一个旧值重新算 —— 第二次算出来的值和服务端已有值相同，被
 * {@code sendWeight} 的「值没变不发包」守卫丢掉。**这些点击静默消失**。
 *
 * <p>本机单人也躲不掉：state 包最早也要下一个 tick 才处理，而长按连发周期是 50ms
 * = 正好一个 tick（{@code HOLD_REPEAT_MS}），公网（FRP，RTT 常常 80~150ms）下更严重 ——
 * 连发实际只有一半甚至三分之一的步进落地，玩家感受是「按住 + 加得特别慢」，零报错、零日志。
 *
 * <h2>做法</h2>
 * 记住「最后一次发出去的值」，只要它还在**同一方向**上领先镜像、且没超时，就用它当基准与显示值：
 * <pre>
 * 镜像 4 → 点 + (发 5, pending=5) → 回包前再点 + ⇒ 基准取 5 ⇒ 发 6（旧实现：发 5，被吞）
 * 回包到 5 ⇒ pending 5 仍领先 0 ⇒ 继续用 5（显示不回跳）
 * 回包到 6 ⇒ pending 已追平 ⇒ 回落镜像
 * </pre>
 *
 * <p>★方向判据不能省★：只用「pending != mirror」的话，用户从 13 往回减到 5 时，一个迟到的
 * 中间回包会让基准跳回 13，下一次点击又变成 12（点一下就回弹，看起来像界面不听话）。
 *
 * <p>★必须带超时★：服务端可能否决这次改动（槽位被清空、条目被删、包裹被换走）⇒ 那个值永远
 * 不会出现在任何 state 包里。没有 TTL 就是「界面永远显示一个服务端并不承认的数字」。
 */
public final class WeightEcho {

    /** 超过这个时间还没被回包确认，就当服务端否决了这次改动，回落到镜像值。 */
    public static final long TTL_MS = 2000L;

    private int slot = -1;
    private int value = 0;
    /** 发出方向：+1 / -1（0 = 没有待确认值）。 */
    private int dir = 0;
    private long sentAtMs = 0L;

    /** 该格当前应显示 / 应作为步进基准的权重。{@code nowMs} 显式传入 ⇒ 探针能造时间。 */
    public int base(int slot, int mirror, long nowMs) {
        if (this.dir == 0 || slot != this.slot) return mirror;
        if (nowMs - this.sentAtMs > TTL_MS) {
            clear();
            return mirror;
        }
        if (Integer.signum(this.value - mirror) != this.dir) {
            // 镜像已追平/越过（回包到了），待确认值退休
            clear();
            return mirror;
        }
        return this.value;
    }

    public int base(int slot, int mirror) {
        return base(slot, mirror, System.currentTimeMillis());
    }

    /**
     * 记下「刚发出去的值」。
     *
     * @param sentValue 实际发出去（已钳位）的值
     * @param mirror    发送那一刻的镜像值 —— 两者相同表示这一步没有产生变化，不进入待确认态
     */
    public void sent(int slot, int sentValue, int mirror, long nowMs) {
        int d = Integer.signum(sentValue - mirror);
        if (d == 0) {
            clear();
            return;
        }
        this.slot = slot;
        this.value = sentValue;
        this.dir = d;
        this.sentAtMs = nowMs;
    }

    public void sent(int slot, int sentValue, int mirror) {
        sent(slot, sentValue, mirror, System.currentTimeMillis());
    }

    /** 清空待确认态（换包裹 / 删条目 / 关界面时用）。 */
    public void clear() {
        this.slot = -1;
        this.value = 0;
        this.dir = 0;
        this.sentAtMs = 0L;
    }

    /** 是否有待确认值（探针用）。 */
    public boolean pending() {
        return this.dir != 0;
    }

    public int pendingSlot() {
        return this.slot;
    }

    public int pendingValue() {
        return this.value;
    }
}
