package com.akira.redtale.economy.economy;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * 限时货币批次（B23 / spec §15 多币种 FIFO）。
 *
 * <p>赞助货币按「批次」入账：每笔有 {@code amount}、{@code expiresAt}（epoch ms，0 = 永久）。
 * 消费一律 <b>FIFO（先到期先花）</b>：先花最早到期的批次，过期批次既不计入可用余额，
 * 也不会被静默花掉 —— 消费时会被剔除并单独告诉玩家「有 N 过期的已作废」。
 *
 * <p>为什么不是简单一个总数：限时货币一旦和永久货币混成一个数，过期时就说不清
 * 「为什么少了 300」。分批记账才能给出准确提示与对账依据。
 */
public final class TimedBatches {

	/** 一个批次。可变是为了原地扣减。 */
	public static final class Batch {
		public long amount;
		public final long expiresAt;

		public Batch(long amount, long expiresAt) {
			this.amount = Math.max(0, amount);
			this.expiresAt = expiresAt;
		}

		public boolean expired(long now) {
			return expiresAt > 0 && expiresAt <= now;
		}
	}

	/** 消费结果：实际花掉多少、作废多少（过期）。 */
	public record Spend(long spent, long forfietedExpired) {
	}

	private final List<Batch> batches = new ArrayList<>();

	public void add(long amount, long expiresAt) {
		if (amount <= 0) return;
		batches.add(new Batch(amount, expiresAt));
		// 先到期在前；永久（0）排最后
		batches.sort((a, b) -> {
			long ea = a.expiresAt == 0 ? Long.MAX_VALUE : a.expiresAt;
			long eb = b.expiresAt == 0 ? Long.MAX_VALUE : b.expiresAt;
			return Long.compare(ea, eb);
		});
	}

	/** 可用余额（不含已过期）。 */
	public long available(long now) {
		long sum = 0;
		for (Batch b : batches) {
			if (!b.expired(now)) sum += b.amount;
		}
		return sum;
	}

	/** 已过期但还挂在账上的总量（供提示 / 清理）。 */
	public long expiredAmount(long now) {
		long sum = 0;
		for (Batch b : batches) {
			if (b.expired(now)) sum += b.amount;
		}
		return sum;
	}

	/**
	 * FIFO 消费。
	 *
	 * @return 实际花掉的量；不足时返回 0（调用方不应扣减）
	 */
	public Spend spend(long amount, long now) {
		if (amount <= 0) return new Spend(0, 0);
		if (available(now) < amount) return new Spend(0, 0);

		long left = amount;
		long forfeited = 0;
		Iterator<Batch> it = batches.iterator();
		while (it.hasNext()) {
			Batch b = it.next();
			if (b.expired(now)) {
				forfeited += b.amount;
				it.remove();
				continue;
			}
			if (left <= 0) continue;
			long take = Math.min(left, b.amount);
			b.amount -= take;
			left -= take;
			if (b.amount <= 0) it.remove();
		}
		return new Spend(amount - left, forfeited);
	}

	/** 清掉过期批次，返回清掉的量（登录/每日结算时调）。 */
	public long purgeExpired(long now) {
		long removed = 0;
		Iterator<Batch> it = batches.iterator();
		while (it.hasNext()) {
			Batch b = it.next();
			if (b.expired(now)) {
				removed += b.amount;
				it.remove();
			}
		}
		return removed;
	}

	/**
	 * 消费后该给玩家的提示键（B23）：有作废的过期币才提示，否则返回 null 不刷屏。
	 * 参数是该次作废量。
	 */
	public static String noticeKey(Spend s) {
		return s != null && s.forfietedExpired() > 0 ? "economy.timed.expired" : null;
	}

	public int size() {
		return batches.size();
	}

	/** 只读快照（测试 / 存档用）。 */
	public List<Batch> snapshot() {
		List<Batch> out = new ArrayList<>(batches.size());
		for (Batch b : batches) out.add(new Batch(b.amount, b.expiresAt));
		return out;
	}
}
