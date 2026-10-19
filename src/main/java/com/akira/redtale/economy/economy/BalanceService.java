package com.akira.redtale.economy.economy;

import com.akira.redtale.economy.RedtaleEconomy;
import com.akira.redtale.economy.config.EconomyConfig;

import java.util.UUID;

/**
 * 余额统一入口。上层（命令 / 商店 / 签到 / 任务）只调这里，不直接碰 provider。
 *
 * <p>选源规则见 {@code economy.json → provider}：
 * auto = 装了 Numismatic 且桥接可用就用它，否则内部 JSON；
 * 强制指定但不可用时记 WARN 并回落，绝不抛到业务层。
 */
public final class BalanceService {

	private static InternalProvider internal;
	private static NumismaticBridge numismatic;
	private static BalanceProvider active;
	private static boolean inited;

	private BalanceService() {
	}

	public static synchronized void init() {
		if (internal == null) {
			internal = new InternalProvider();
		} else {
			internal.resolvePaths();
		}
		if (numismatic == null) {
			numismatic = new NumismaticBridge();
		}

		String mode = EconomyConfig.provider();
		BalanceProvider chosen;
		if ("internal".equals(mode)) {
			chosen = internal;
		} else if ("numismatic".equals(mode)) {
			if (numismatic.available()) {
				chosen = numismatic;
			} else {
				RedtaleEconomy.LOGGER.warn("[provider] provider=numismatic 但桥接不可用，回落到内部余额");
				chosen = internal;
			}
		} else {
			chosen = numismatic.available() ? numismatic : internal;
		}

		if (active != chosen) {
			RedtaleEconomy.LOGGER.info("[provider] 余额来源：{}（配置 {}）", chosen.id(), mode);
		}
		active = chosen;
		inited = true;
	}

	public static BalanceProvider provider() {
		if (!inited) init();
		return active;
	}

	/** 余额；未知（如 Numismatic 的离线玩家）返回 -1。 */
	public static long get(UUID uuid) {
		return provider().get(uuid);
	}

	public static boolean has(UUID uuid, long amount) {
		long v = get(uuid);
		return v >= 0 && v >= amount;
	}

	public static void add(UUID uuid, long amount) {
		add(uuid, amount, com.akira.redtale.economy.storage.Ledger.TYPE_ADMIN, "");
	}

	/** 入账 + 记流水（流水写的是入账后的余额，便于对账）。 */
	public static void add(UUID uuid, long amount, String type, String ref) {
		if (amount == 0) return;
		provider().modify(uuid, Math.abs(amount));
		long after = get(uuid);
		if (after >= 0) {
			com.akira.redtale.economy.storage.Ledger.record(uuid, Math.abs(amount), after, type, ref);
		}
	}

	/** 扣款；余额不足返回 false，不做任何改动。 */
	public static boolean take(UUID uuid, long amount) {
		return take(uuid, amount, com.akira.redtale.economy.storage.Ledger.TYPE_ADMIN, "");
	}

	public static boolean take(UUID uuid, long amount, String type, String ref) {
		if (amount <= 0) return true;
		long v = get(uuid);
		if (v < 0 || v < amount) return false;
		provider().modify(uuid, -amount);
		long after = get(uuid);
		if (after >= 0) {
			com.akira.redtale.economy.storage.Ledger.record(uuid, -amount, after, type, ref);
		}
		return true;
	}

	public static String format(long raw) {
		return Currency.format(raw);
	}

	public static InternalProvider internal() {
		return internal;
	}

	public static NumismaticBridge numismatic() {
		return numismatic;
	}

	/** 关服落盘。 */
	public static void shutdown() {
		if (internal != null) internal.flushAll();
	}
}
