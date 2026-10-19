package com.akira.redtale.economy.command;

import java.security.SecureRandom;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 危险操作的二次确认票据（R25）。
 *
 * <p>{@code /shop admin remove ...} 这类不可逆操作先发一张 8 位票据，5 分钟内有效，
 * 玩家执行 {@code /redtale confirm <票据>} 才真正动手。票据：
 * <ul>
 *   <li>一次性 —— take 成功即从表里删掉，重复 confirm 无效</li>
 *   <li>绑定玩家 —— 别人拿你的票也用不了</li>
 *   <li>过期即废 —— 过期条目在每次 issue/take 时顺手清理</li>
 * </ul>
 *
 * <p>是否强制确认由 {@code core.json → confirm.dangerousOps} 控制，默认关闭以免破坏现有脚本。
 */
public final class ConfirmTickets {

	private static final SecureRandom RND = new SecureRandom();
	private static final char[] ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ".toCharArray();
	private static final int CODE_LEN = 8;

	private static final Map<String, Ticket> TICKETS = new ConcurrentHashMap<>();

	/**
	 * @param code     票据码
	 * @param player   只能由该玩家使用
	 * @param action   动作标识（如 shop.remove）
	 * @param payload  动作参数（由调用方自己解释）
	 * @param expireAt epoch millis
	 */
	public record Ticket(String code, UUID player, String action, String payload, long expireAt) {
	}

	private ConfirmTickets() {
	}

	public static int ttlSeconds() {
		try {
			return (int) Math.max(10, com.akira.redtale.economy.config.CoreConfig
					.store().getPathLong("confirm.ttlSeconds", 300));
		} catch (Throwable t) {
			return 300; // 配置还没就绪（如单测）时用默认值，不影响票据语义
		}
	}

	public static boolean required() {
		try {
			return com.akira.redtale.economy.config.CoreConfig.store().getBool("confirm.dangerousOps", false);
		} catch (Throwable t) {
			return false;
		}
	}

	/** 生成票据（同玩家同动作的旧票据保留，各自独立过期）。 */
	public static Ticket issue(UUID player, String action, String payload) {
		purge();
		String code = newCode();
		Ticket t = new Ticket(code, player, action, payload,
				System.currentTimeMillis() + ttlSeconds() * 1000L);
		TICKETS.put(code, t);
		return t;
	}

	/**
	 * 取出并核销票据。
	 *
	 * @return 有效票据；码不存在 / 不属于该玩家 / 已过期 都返回 null（过期的同时删除）
	 */
	public static Ticket take(UUID player, String code) {
		if (player == null || code == null) return null;
		Ticket t = TICKETS.get(code.trim().toUpperCase(java.util.Locale.ROOT));
		if (t == null) return null;
		if (!t.player().equals(player)) return null;
		if (t.expireAt() < System.currentTimeMillis()) {
			TICKETS.remove(t.code());
			return null;
		}
		// 一次性：核销后不可重复使用
		TICKETS.remove(t.code(), t);
		return t;
	}

	/** 主动作废（{@code /redtale cancel <票据>}）。 */
	public static boolean cancel(UUID player, String code) {
		if (player == null || code == null) return false;
		Ticket t = TICKETS.get(code.trim().toUpperCase(java.util.Locale.ROOT));
		if (t == null || !t.player().equals(player)) return false;
		return TICKETS.remove(t.code(), t);
	}

	/** 清理过期票据。 */
	public static void purge() {
		long now = System.currentTimeMillis();
		Iterator<Map.Entry<String, Ticket>> it = TICKETS.entrySet().iterator();
		while (it.hasNext()) {
			if (it.next().getValue().expireAt() < now) it.remove();
		}
	}

	public static int size() {
		return TICKETS.size();
	}

	/** 仅供测试：清空所有票据。 */
	static void clearForTest() {
		TICKETS.clear();
	}

	/** 人类可读的剩余秒数（用于提示）。 */
	public static long remainingSeconds(Ticket t) {
		return Math.max(0, (t.expireAt() - System.currentTimeMillis()) / 1000L);
	}

	private static String newCode() {
		StringBuilder sb = new StringBuilder(CODE_LEN);
		for (int i = 0; i < CODE_LEN; i++) {
			sb.append(ALPHABET[RND.nextInt(ALPHABET.length)]);
		}
		return sb.toString();
	}

	/** 纯函数：是否过期（便于单测）。 */
	public static boolean expired(long expireAt, long now) {
		return expireAt < now;
	}
}
