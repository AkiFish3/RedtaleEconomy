package com.akira.redtale.economy.shop;

import com.akira.redtale.economy.RedtaleEconomy;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * T13 公开 API：商店成交事件，供其他模组（或本 mod 的 KubeJS 桥）监听。
 *
 * <pre>{@code
 * ShopEvents.listen(tx -> { if (tx.total() > 1000) log(tx); });
 * }</pre>
 *
 * <p>三条硬约束：
 * <ul>
 *   <li><b>只发成功交易</b>：失败/退款路径不发，否则外部统计会把已回滚的流水算成收入。</li>
 *   <li><b>监听器抛异常不许影响交易</b>：逐个 try/catch + ERROR 日志。第三方代码不该让玩家丢钱。</li>
 *   <li><b>不持锁回调</b>：{@code fire} 在交易事务提交之后调用（此时钱与物品都已落定）。</li>
 * </ul>
 *
 * <p>为什么不用 Fabric 的 Event 机制：本事件的参数是 mod 自己的 record，
 * 用 Fabric API 需要额外的注册样板且客户端/服务端语义不清；一个静态监听列表足够，
 * 且能脱离游戏做单测（{@link #clearAll} 用于隔离测试）。
 */
public final class ShopEvents {

	/** 一笔成交（买或卖）。{@code unitPrice} 是含税/折后、玩家实际承担的单价。 */
	public record Transaction(UUID player, String shopId, String itemId, int count,
			long unitPrice, long total, long balanceAfter, boolean buy) {
	}

	private static final List<Consumer<Transaction>> LISTENERS = new CopyOnWriteArrayList<>();

	private ShopEvents() {
	}

	public static void listen(Consumer<Transaction> listener) {
		if (listener != null) LISTENERS.add(listener);
	}

	public static void unlisten(Consumer<Transaction> listener) {
		LISTENERS.remove(listener);
	}

	/** 通知所有监听器；单个监听器异常只记日志。 */
	public static void fire(Transaction tx) {
		if (tx == null) return;
		for (Consumer<Transaction> l : LISTENERS) {
			try {
				l.accept(tx);
			} catch (Throwable t) {
				try {
					RedtaleEconomy.LOGGER.error("[shop] 成交事件监听器异常（已忽略）：{}", t.toString());
				} catch (Throwable ignored) {
					// 日志本身不可用时也不能让交易失败
				}
			}
		}
	}

	public static int listenerCount() {
		return LISTENERS.size();
	}

	/** 测试/重载用：清空监听器（生产环境不该调用）。 */
	public static void clearAll() {
		LISTENERS.clear();
	}
}
