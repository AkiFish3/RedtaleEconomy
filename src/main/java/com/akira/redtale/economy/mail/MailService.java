package com.akira.redtale.economy.mail;

import com.akira.redtale.economy.RedtaleEconomy;
import com.akira.redtale.economy.config.MailConfig;
import com.akira.redtale.economy.economy.BalanceService;
import com.akira.redtale.economy.integration.ContactMail;
import com.akira.redtale.economy.storage.Ledger;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.util.UUID;

/**
 * 邮件投递（R21）：物品 → Contact 信箱（满/未装则进队列），货币 → 待领取队列。
 *
 * <p>其它模块（任务奖励、商店补偿、战令）只需要调这里，不需要知道 Contact 存不存在。
 */
public final class MailService {

	private MailService() {
	}

	/** 投递物品；返回 true 表示已经进了 Contact 信箱，false 表示进了待领取队列。 */
	public static boolean sendItem(MinecraftServer server, UUID target, ItemStack stack, String reason) {
		if (target == null || stack == null || stack.isEmpty()) return false;
		boolean delivered = ContactMail.deliver(server, target, stack, MailConfig.senderName());
		if (!delivered) {
			MailQueue q = MailQueue.of(target);
			q.items.add(stack.copy());
			q.dirty = true;
			q.save();
			RedtaleEconomy.LOGGER.info("[mail] {} 的物品进待领取队列（原因：{}，条目：{}）",
					target, reason, q.items.size());
		}
		notify(target, delivered);
		return delivered;
	}

	/** 记一笔待领取货币。 */
	public static boolean sendMoney(UUID target, long amount, String reason) {
		if (target == null || amount <= 0) return false;
		if (amount > MailConfig.maxQueueMoney()) {
			RedtaleEconomy.LOGGER.warn("[mail] {} 的入队金额 {} 超过上限，已拒绝（原因：{}）",
					target, amount, reason);
			return false;
		}
		MailQueue q = MailQueue.of(target);
		q.pendingMoney += amount;
		q.dirty = true;
		q.save();
		notify(target, false);
		return true;
	}

	/** 领取全部待领取货币；返回领取金额（0 = 没有）。 */
	public static synchronized long claimMoney(UUID target) {
		if (target == null) return 0;
		MailQueue q = MailQueue.of(target);
		long amount = q.pendingMoney;
		if (amount <= 0) return 0;
		q.pendingMoney = 0;
		q.dirty = true;
		q.save();
		BalanceService.add(target, amount, Ledger.TYPE_MAIL, "mail:claim");
		return amount;
	}

	/** 把队列里还没投出去的东西重投一次（信箱空了就能进）。 */
	public static synchronized int retryItems(MinecraftServer server, UUID target) {
		if (server == null || target == null) return 0;
		MailQueue q = MailQueue.of(target);
		if (q.items.isEmpty()) return 0;
		int moved = 0;
		for (int i = 0; i < q.items.size(); ) {
			ItemStack st = q.items.get(i);
			if (ContactMail.deliver(server, target, st, MailConfig.senderName())) {
				q.items.remove(i);
				moved++;
			} else {
				i++;
			}
		}
		if (moved > 0) {
			q.dirty = true;
			q.save();
			notify(target, true);
		}
		return moved;
	}

	private static void notify(UUID target, boolean toMailbox) {
		if (!MailConfig.notifyOnReceive()) return;
		try {
			MinecraftServer server = RedtaleEconomy.server();
			if (server == null) return;
			ServerPlayerEntity p = server.getPlayerManager().getPlayer(target);
			if (p == null) return;
			p.sendMessage(Text.literal(com.akira.redtale.economy.lang.Lang.msg(
					toMailbox ? "mail.newBox" : "mail.newQueue")), true);
		} catch (Exception ignored) {
			// 提示失败不影响投递
		}
	}
}
