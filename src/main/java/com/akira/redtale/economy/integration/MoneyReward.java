package com.akira.redtale.economy.integration;

import com.akira.redtale.economy.economy.BalanceService;
import com.akira.redtale.economy.lang.Lang;
import dev.ftb.mods.ftbquests.quest.Quest;
import dev.ftb.mods.ftbquests.quest.reward.Reward;
import dev.ftb.mods.ftbquests.quest.reward.RewardType;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

/**
 * FTB Quests 自定义奖励类型 {@code redtale_economy:money}（R9）。
 *
 * <p>金额单位是<b>铜分值</b>，与内部余额一致；任务编辑器里填 100 = 1 银。
 * 领取时全额记流水（{@code ftbq.reward}），余额来源是 Numismatic 时同样走
 * {@link BalanceService#add}，账单自洽。
 *
 * <p>只在 FTB Quests 已加载时由 {@link FtbqIntegration} 实例化，
 * 未安装时这个类不会被加载（避免 NoClassDefFoundError）。
 */
public final class MoneyReward extends Reward {

	/** 固定类型 id，改名字会让已有任务书里的奖励失效。 */
	public static final Identifier TYPE_ID = new Identifier("redtale_economy", "money");

	private long amount;

	public MoneyReward(long id, Quest quest) {
		super(id, quest);
	}

	public long getAmount() {
		return amount;
	}

	public void setAmount(long amount) {
		this.amount = Math.max(0, amount);
	}

	@Override
	public RewardType getType() {
		return FtbqIntegration.moneyType();
	}

	@Override
	public void claim(ServerPlayerEntity player, boolean notify) {
		if (amount <= 0) return;
		// 领奖失败不能把 FTBQ 的领取流程炸掉：捕获后只记日志，任务仍算已领
		try {
			doClaim(player, notify);
		} catch (Exception e) {
			com.akira.redtale.economy.RedtaleEconomy.LOGGER.error(
					"[ftbq] 发放奖励失败 {} amount={}：{}", player.getUuid(), amount, e.toString());
		}
	}

	private void doClaim(ServerPlayerEntity player, boolean notify) {
		// R110：FTBQ 的奖励对象是「已经注册进任务书数据里的东西」，关掉集成也摘不掉；
		// 只关注册（FtbqIntegration）挡不住运行期关掉开关后玩家继续领钱 ⇒ 发钱这一刻再判一次。
		if (!com.akira.redtale.economy.config.FtbqConfig.enabled()) {
			com.akira.redtale.economy.util.Logs.warnOnce("ftbq.claim.disabled",
					"[ftbq] 集成已关闭，拒绝发放奖励（uuid={} amount={}）", player.getUuid(), amount);
			return;
		}
		String ref = "quest:" + (getQuest() == null ? "?" : getQuest().getId());
		boolean premium = "premium".equalsIgnoreCase(com.akira.redtale.economy.config.FtbqConfig.rewardCurrency());
		if (premium) {
			com.akira.redtale.economy.module.impl.PremiumModule.add(player.getUuid(), amount);
		} else if (com.akira.redtale.economy.config.FtbqConfig.logClaim()) {
			BalanceService.add(player.getUuid(), amount, "ftbq.reward", ref);
		} else {
			// 配置明确要求不记账：直接改余额，不写流水
			BalanceService.provider().modify(player.getUuid(), amount);
		}
		if (notify && com.akira.redtale.economy.config.FtbqConfig.notifyOnClaim()) {
			String shown = premium ? String.valueOf(amount) : BalanceService.format(amount);
			player.sendMessage(Text.literal(Lang.t("msg.ftbq.reward", "已获得 %s")
					.replace("%s", shown)), false);
		}
	}

	// ---------------------------------------------------------------- 持久化

	@Override
	public void writeData(NbtCompound tag) {
		super.writeData(tag);
		tag.putLong("redtale_amount", amount);
	}

	@Override
	public void readData(NbtCompound tag) {
		super.readData(tag);
		amount = tag.getLong("redtale_amount");
	}

	@Override
	public void writeNetData(PacketByteBuf buf) {
		super.writeNetData(buf);
		buf.writeLong(amount);
	}

	@Override
	public void readNetData(PacketByteBuf buf) {
		super.readNetData(buf);
		amount = buf.readLong();
	}
}
