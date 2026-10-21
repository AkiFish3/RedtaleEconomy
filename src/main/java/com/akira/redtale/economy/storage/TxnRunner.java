package com.akira.redtale.economy.storage;

import com.akira.redtale.economy.RedtaleEconomy;
import com.akira.redtale.economy.economy.BalanceService;
import com.akira.redtale.economy.module.impl.PremiumModule;
import com.akira.redtale.economy.shop.CompensationQueue;
import com.akira.redtale.economy.util.TimeUtil;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;

import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * 经济事务的统一回滚出口（T34）。
 *
 * <p>之前商店 / 市场 / 仓储扩容各写了一套退款：
 * <ul>
 *   <li>{@code ShopTransactions} 退款 + 补偿队列 + ERROR 日志（Q2 修过的那套）；</li>
 *   <li>{@code MarketService} 只调 {@code BalanceService.add}，失败没有任何记录；</li>
 *   <li>{@code WarehouseModule} 同样裸调，且 {@code expandTo} 失败时钱已扣、没有退款。</li>
 * </ul>
 * 后果是同一类缺陷要在三处各修一遍，而且只有先修的那处有审计。这里把「退钱 / 回退 / 发货」
 * 收敛成三个入口，业务侧只决定「退给谁、多少钱、记在哪个 ledger 类型下」。
 *
 * <p>语义区分：
 * <ul>
 *   <li>{@link #refund} —— 失败路径的回滚，必须留补偿记录（人工可对账）；</li>
 *   <li>{@link #revert} —— 正常业务回退（被超价、挂单写盘失败退手续费），只记 ledger，
 *       不进补偿队列，否则补偿文件会被日常流水淹掉；</li>
 *   <li>{@link #restore} / {@link #deliver} —— 物品侧，统一「先塞背包、塞不下掉落」的口径。</li>
 * </ul>
 *
 * <p>所有入口都不向外抛异常：事务失败要回一个可判定的结果，不能把异常甩到命令层
 * 变成「玩家看到 Unknown」。
 */
public final class TxnRunner {

	private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

	/** 资金通道：铜币（默认）或赞助点。类型与 ref 只对铜币生效（赞助点没有 ledger）。 */
	public interface Money {
		void add(UUID uuid, long amount, String ledgerType, String ref);
	}

	public static final Money COIN = BalanceService::add;
	public static final Money PREMIUM = (uuid, amount, type, ref) -> PremiumModule.add(uuid, amount);

	public enum Outcome {
		/** 已退回。 */
		REFUNDED,
		/** 退回本身失败 —— 钱需要人工补，日志里带金额与 uuid。 */
		FAILED,
		/** 金额为 0，什么都没做。 */
		SKIPPED
	}

	/** 补偿记录的业务上下文（channel=商店 id / "market"，subject=物品 id / 挂单 id）。 */
	public record Note(UUID player, String channel, String subject, int count, long amount, String reason) {
		public static Note simple(UUID player, String ref, String reason) {
			return new Note(player, "", ref, 0, 0, reason);
		}
	}

	private TxnRunner() {
	}

	// ---------------------------------------------------------------- 退钱

	/** 失败路径回滚：退钱 + 补偿记录。 */
	public static Outcome refund(UUID uuid, long amount, String ledgerType, String ref, Note note) {
		return refund(COIN, uuid, amount, ledgerType, ref, note);
	}

	public static Outcome refund(Money sink, UUID uuid, long amount, String ledgerType, String ref, Note note) {
		if (amount <= 0) return Outcome.SKIPPED;
		Outcome out;
		try {
			sink.add(uuid, amount, ledgerType, ref);
			out = Outcome.REFUNDED;
		} catch (Throwable t) {
			out = Outcome.FAILED;
			RedtaleEconomy.LOGGER.error("[txn] 退款失败，需人工补 {} 给 {}（{}）：{}",
					amount, uuid, ref, t.getMessage());
		}
		audit(note, (out == Outcome.REFUNDED ? "refunded: " : "REFUND-FAILED: ") + note.reason());
		return out;
	}

	/** 正常业务回退：只记 ledger，失败也吞（返回 FAILED 供调用方记日志）。 */
	public static Outcome revert(UUID uuid, long amount, String ledgerType, String ref) {
		return revert(COIN, uuid, amount, ledgerType, ref);
	}

	public static Outcome revert(Money sink, UUID uuid, long amount, String ledgerType, String ref) {
		if (amount <= 0) return Outcome.SKIPPED;
		try {
			sink.add(uuid, amount, ledgerType, ref);
			return Outcome.REFUNDED;
		} catch (Throwable t) {
			RedtaleEconomy.LOGGER.error("[txn] 资金回退失败（{}）：{}", ref, t.getMessage());
			return Outcome.FAILED;
		}
	}

	/** 只写补偿记录（物品侧失败时用：没有钱要退，但要有账可对）。 */
	public static void audit(Note note) {
		if (note == null) return;
		audit(note, note.reason());
	}

	private static void audit(Note note, String reason) {
		if (note == null) return;
		String at;
		try {
			at = TimeUtil.now().format(TS);
		} catch (Throwable t) {
			// 配置未就绪（单测 / 极早期失败）时退回本机时区，审计不能因此丢行
			at = java.time.LocalDateTime.now().format(TS);
		}
		try {
			CompensationQueue.enqueue(new CompensationQueue.Entry(
					at,
					note.player() == null ? "" : note.player().toString(),
					note.channel() == null ? "" : note.channel(),
					note.subject() == null ? "" : note.subject(),
					note.count(), note.amount(), reason));
		} catch (Throwable t) {
			// 审计写失败不能反过来弄坏回滚
			RedtaleEconomy.LOGGER.error("[txn] 补偿记录写入失败：{}", t.getMessage());
		}
	}

	// ---------------------------------------------------------------- 物品

	/**
	 * 尽最大努力把物品还回玩家：先进背包，剩下的掉落。
	 *
	 * @return 仍没交出去的件数（0 = 全部还回）
	 */
	public static int restore(ServerPlayerEntity player, ItemStack stack) {
		if (player == null || stack == null || stack.isEmpty()) return 0;
		ItemStack rest = stack.copy();
		try {
			player.getInventory().insertStack(rest);
			player.getInventory().markDirty();
		} catch (Throwable t) {
			RedtaleEconomy.LOGGER.error("[txn] 回填背包失败：{}", t.getMessage());
		}
		if (rest.isEmpty()) return 0;
		try {
			if (player.dropItem(rest, false) != null) return 0;
		} catch (Throwable t) {
			RedtaleEconomy.LOGGER.error("[txn] 掉落回填失败：{}", t.getMessage());
		}
		return rest.getCount();
	}

	/**
	 * 发货：必须送达，送不到就抛（调用方据此退款）。
	 *
	 * <p>与 {@link #restore} 的区别是「不能静默丢失」—— 买到的货放不下时宁可整笔回滚。
	 */
	public static void deliver(ServerPlayerEntity player, ItemStack stack) {
		if (player == null || stack == null || stack.isEmpty()) return;
		ItemStack rest = stack.copy();
		player.getInventory().insertStack(rest);
		if (!rest.isEmpty()) {
			boolean dropped;
			try {
				dropped = player.dropItem(rest, false) != null;
			} catch (Throwable t) {
				dropped = false;
			}
			if (!dropped) {
				throw new IllegalStateException("背包与地面都放不下 " + stack.getCount() + " 个 "
						+ stack.getItem());
			}
		}
		player.getInventory().markDirty();
	}
}
