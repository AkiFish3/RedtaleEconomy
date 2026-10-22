package com.akira.redtale.economy.module;

import com.akira.redtale.economy.config.EconomyConfig;
import com.akira.redtale.economy.economy.BalanceService;
import com.akira.redtale.economy.storage.Ledger;
import com.akira.redtale.economy.storage.PendingPay;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 转账的唯一实现（Aki 2026-10-23）。
 *
 * <p>★为什么要有这个类★：以前有两条转账路径 —— {@code /pay} 命令做全套校验，
 * 而 Hub 转账页走的 {@code PayNetworking} 自己写了一遍：<b>不读配置</b>
 * （免税 / 无冷却 / 无上下限 / 可自转），而且<b>只认在线玩家</b>。
 * 同一个动作两套规则，玩家用界面就能绕过税和冷却。现在两边都只调 {@link #pay}。
 *
 * <p>离线收款：目标不在线时余额后端（Numismatic）拿不到组件，直接入账会
 * 「扣了发送方、收款方分文未得」。所以离线一律进 {@link PendingPay} 挂账，
 * 玩家上线时由 {@link #claim} 入账（详见 PendingPay 的类注释）。
 */
public final class PayService {

	/** 失败原因。{@link #OK} 之外都由调用方翻成文案。 */
	public enum Code {
		OK, DISABLED, BAD_AMOUNT, UNKNOWN_PLAYER, SELF, MIN, MAX, COOLDOWN, INSUFFICIENT
	}

	/**
	 * 一次转账的结果。
	 *
	 * @param online  目标是否在线（false = 已挂账，等对方上线）
	 * @param queued  是否写进了离线挂账
	 * @param waitSec 冷却剩余秒数（仅 COOLDOWN 有意义）
	 */
	public record Result(Code code, long amount, long tax, long net, int waitSec,
			String targetName, boolean online, boolean queued) {

		static Result fail(Code code) {
			return new Result(code, 0, 0, 0, 0, "", false, false);
		}

		static Result fail(Code code, int waitSec, String targetName) {
			return new Result(code, 0, 0, 0, waitSec, targetName, false, false);
		}
	}

	/** 上次转账时间（冷却）。两条入口共用这一份，界面绕不过冷却。 */
	private static final Map<UUID, Long> LAST = new ConcurrentHashMap<>();

	private PayService() {
	}

	/**
	 * 税率（向上取整，税从收款方扣）。抽成纯函数便于单测。
	 *
	 * @param percent 0..100
	 */
	public static long taxOf(long amount, double percent) {
		if (amount <= 0 || percent <= 0) return 0L;
		long raw = (long) Math.ceil(amount * (percent / 100.0));
		return Math.min(Math.max(0L, raw), amount);
	}

	/** 执行一次转账（校验 + 扣款 + 入账 / 挂账 + 记流水）。 */
	public static Result pay(ServerPlayerEntity from, String rawName, long amount) {
		if (from == null) return Result.fail(Code.UNKNOWN_PLAYER);
		if (!EconomyConfig.payEnabled()) return Result.fail(Code.DISABLED);
		if (amount <= 0) return Result.fail(Code.BAD_AMOUNT);

		String name = rawName == null ? "" : rawName.trim();
		if (name.isEmpty()) return Result.fail(Code.UNKNOWN_PLAYER);

		// 1) 先在线，再翻用户缓存（离线玩家只要登录过服务器就能解析出 UUID）
		UUID toId;
		String toName;
		boolean online;
		ServerPlayerEntity target = from.server.getPlayerManager().getPlayer(name);
		if (target != null) {
			toId = target.getUuid();
			toName = target.getGameProfile().getName();
			online = true;
		} else {
			Optional<com.mojang.authlib.GameProfile> profile;
			try {
				var cache = from.server.getUserCache();
				profile = cache == null ? Optional.empty() : cache.findByName(name);
			} catch (Exception e) {
				profile = Optional.empty();
			}
			if (profile == null || profile.isEmpty() || profile.get().getId() == null) {
				return Result.fail(Code.UNKNOWN_PLAYER);
			}
			toId = profile.get().getId();
			toName = profile.get().getName() == null ? name : profile.get().getName();
			online = false;
		}

		if (toId.equals(from.getUuid()) && !EconomyConfig.payAllowSelf()) {
			return Result.fail(Code.SELF);
		}

		long min = EconomyConfig.payMin();
		if (min > 0 && amount < min) return Result.fail(Code.MIN);
		long max = EconomyConfig.payMax();
		if (max > 0 && amount > max) return Result.fail(Code.MAX);

		int cd = EconomyConfig.payCooldownSeconds();
		if (cd > 0) {
			long last = LAST.getOrDefault(from.getUuid(), 0L);
			long waitMs = last + cd * 1000L - System.currentTimeMillis();
			if (waitMs > 0) {
				return Result.fail(Code.COOLDOWN, (int) (waitMs / 1000L) + 1, toName);
			}
		}

		long have = BalanceService.get(from.getUuid());
		if (have < 0 || have < amount) return Result.fail(Code.INSUFFICIENT);

		long tax = taxOf(amount, EconomyConfig.payTaxPercent());
		long net = amount - tax;
		String fromName = from.getGameProfile().getName();

		// 2) 扣款（幂等性：失败就不入账）
		if (!BalanceService.take(from.getUuid(), amount, Ledger.TYPE_PAY,
				"to:" + toId + "/tax:" + tax)) {
			return Result.fail(Code.INSUFFICIENT);
		}
		LAST.put(from.getUuid(), System.currentTimeMillis());

		// 3) 入账：在线直接进余额；离线进挂账（上线第一刻补发）
		if (online) {
			BalanceService.add(toId, net, Ledger.TYPE_PAY, "from:" + from.getUuid());
			com.akira.redtale.economy.net.BalanceNetworking.send(target);
		} else {
			PendingPay.add(toId, toName, from.getUuid(), fromName, net);
		}

		// 4) 给双方发提示（注意别写 net.minecraft.text.Text：局部变量 net 会把它解析成 long.minecraft）（命令与界面只决定「哪一行显示」，不重算账）
		if (online && target != null) {
			target.sendMessage(Text.literal("§a收到 " + fromName
					+ " 转账 " + BalanceService.format(amount)
					+ (tax > 0 ? "（扣税 " + BalanceService.format(tax) + "，到账 "
					+ BalanceService.format(net) + "）" : "")), false);
		}

		return new Result(Code.OK, amount, tax, net, 0, toName, online, !online);
	}

	/**
	 * 玩家上线时领取挂账（由 EconomyModule 的 join 钩子调用）。
	 *
	 * @return 领取总额（0 = 没有挂账）
	 */
	public static long claim(ServerPlayerEntity player) {
		if (player == null) return 0L;
		var list = PendingPay.takeAll(player.getUuid());
		if (list.isEmpty()) return 0L;
		long total = 0;
		for (PendingPay.Entry e : list) {
			BalanceService.add(player.getUuid(), e.amount(), Ledger.TYPE_PAY,
					"offline-from:" + e.from());
			total += e.amount();
		}
		player.sendMessage(Text.literal("§a收到 " + list.size()
				+ " 笔离线转账，共 " + BalanceService.format(total) + "（已自动入账）"), false);
		com.akira.redtale.economy.net.BalanceNetworking.send(player);
		return total;
	}
}
