package com.akira.redtale.economy.client.checkin;

import com.akira.redtale.economy.net.CheckinProtocol;

import java.util.List;

/**
 * 签到按钮的防连点闸门（H04）。
 *
 * <p>症状：在签到界面里连点（或点天数格子）会再次发出 {@code /checkin}。
 * 服务端本来就有同日幂等（返回 ALREADY 不发奖），但玩家会看到一串「今天已经签过了」，
 * 体验上等于「点了两次」。所以客户端也要收口：
 * <ol>
 *   <li>面板显示「今日已签」时按钮直接禁用；</li>
 *   <li>点过一次立刻置灰 + 冷却，不等服务端回包；</li>
 *   <li>点天数格子只做选中，绝不触发签到。</li>
 * </ol>
 * 判定抽成纯函数，便于单测钉死（界面里这类 bug 肉眼很难复现）。
 */
public final class CheckinGate {

	/** 冷却时长：覆盖「发包 → 服务端回包」的往返，避免重复发。 */
	public static final long COOLDOWN_MS = 1500L;

	private CheckinGate() {
	}

	/** 今天那一格已经是「已签」状态吗？空快照视为未签。 */
	public static boolean alreadySigned(List<CheckinProtocol.Point> points) {
		if (points == null) return false;
		for (CheckinProtocol.Point p : points) {
			if (p.current() && p.done()) return true;
		}
		return false;
	}

	/**
	 * 现在能不能点签到。
	 *
	 * @param signed    今日已签（服务端快照）
	 * @param lastClick 上次点击时间戳（0 = 没点过）
	 * @param now       当前时间
	 */
	public static boolean canClaim(boolean signed, long lastClick, long now) {
		if (signed) return false;
		if (lastClick <= 0) return true;
		return now - lastClick >= COOLDOWN_MS;
	}
}
