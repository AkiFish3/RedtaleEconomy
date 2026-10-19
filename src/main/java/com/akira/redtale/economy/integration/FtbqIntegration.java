package com.akira.redtale.economy.integration;

import com.akira.redtale.economy.RedtaleEconomy;
import com.akira.redtale.economy.config.ConfigManager;
import com.akira.redtale.economy.config.FtbqConfig;
import dev.ftb.mods.ftblibrary.icon.Icon;
import dev.ftb.mods.ftbquests.quest.reward.RewardType;
import dev.ftb.mods.ftbquests.events.ObjectCompletedEvent;
import dev.ftb.mods.ftbquests.quest.reward.RewardTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

/**
 * FTB Quests 集成入口（R9）。
 *
 * <p><b>软依赖铁律</b>：这个类只在 {@code FabricLoader.isModLoaded("ftbquests")} 为真时
 * 被 {@code RedtaleEconomy} 调用；类里的 FTBQ 类型引用因此不会在无 FTBQ 环境下触发
 * {@code NoClassDefFoundError}。
 *
 * <p>注册内容：
 * <ol>
 *   <li>奖励类型 {@code redtale_economy:money} → {@link MoneyReward}</li>
 *   <li>{@code /quests} 快捷入口（转发 {@link FtbqConfig#entryCommand()}，默认 {@code ftbquests open}）</li>
 * </ol>
 */
public final class FtbqIntegration {

	private static RewardType moneyType;
	private static RewardType bpXpType;
	private static boolean registered;
	private static boolean listenerRegistered;

	private FtbqIntegration() {
	}

	/** 由主类调用；FTBQ 加载且配置允许时注册奖励类型。 */
	public static void init() {
		if (!FabricLoader.getInstance().isModLoaded("ftbquests")) {
			RedtaleEconomy.LOGGER.info("[ftbq] 未检测到 FTB Quests，跳过集成");
			return;
		}
		if (!FtbqConfig.enabled()) {
			RedtaleEconomy.LOGGER.info("[ftbq] ftbq.json → enabled=false，跳过集成");
			return;
		}
		if (registered) {
			registerBpListener();
			return;
		}
		if (!FtbqConfig.registerReward()) {
			RedtaleEconomy.LOGGER.info("[ftbq] registerReward=false，不注册 money 奖励类型");
			registerBpListener();
			return;
		}
		try {
			moneyType = RewardTypes.register(MoneyReward.TYPE_ID, MoneyReward::new,
					() -> Icon.getIcon("minecraft:textures/item/gold_ingot.png"));
			moneyType.setDisplayName(Text.literal("Redtale 货币"));
			registered = true;
			ConfigManager.logEdit(FtbqConfig.FILE, "register.reward", MoneyReward.TYPE_ID.toString());
			RedtaleEconomy.LOGGER.info("[ftbq] 已注册奖励类型 {}", MoneyReward.TYPE_ID);
		} catch (Throwable t) {
			// FTBQ 版本差异不应让本 mod 起不来
			RedtaleEconomy.LOGGER.error("[ftbq] 注册奖励类型失败（FTBQ 版本不兼容？）：{}", t.toString());
		}
		registerBpXpType();
		registerBpListener();
	}

	/** R22：注册 {@code redtale_economy:bp_xp} 奖励类型（第一优先级来源）。 */
	private static void registerBpXpType() {
		if (bpXpType != null) return;
		try {
			bpXpType = RewardTypes.register(BpXpReward.TYPE_ID, BpXpReward::new,
					() -> Icon.getIcon("minecraft:textures/item/experience_bottle.png"));
			bpXpType.setDisplayName(Text.literal("战令经验"));
			RedtaleEconomy.LOGGER.info("[ftbq] 已注册奖励类型 {}", BpXpReward.TYPE_ID);
		} catch (Throwable t) {
			RedtaleEconomy.LOGGER.error("[ftbq] 注册 bp_xp 奖励类型失败：{}", t.toString());
		}
	}

	public static RewardType bpXpType() {
		return bpXpType;
	}

	/**
	 * R22：任务完成 → 给队伍在线成员发战令经验（四层优先级：bp_xp 奖励 → byQuest → byChapter → default）。
	 *
	 * <p>FTBQ 的进度是队伍级，事件里拿不到「谁完成的」；按规格发给队伍在线成员，
	 * 并用 (season, questCode, uuid) 去重。
	 */
	private static void registerBpListener() {
		if (listenerRegistered) return;
		listenerRegistered = true;
		try {
			ObjectCompletedEvent.QUEST.register(event -> {
				try {
					if (!com.akira.redtale.economy.config.BattlePassConfig.ftbqEnabled()) {
						return dev.architectury.event.EventResult.pass();
					}
					var quest = event.getQuest();
					if (quest == null) return dev.architectury.event.EventResult.pass();
					long xp = resolveQuestXp(quest);
					if (xp <= 0) return dev.architectury.event.EventResult.pass();
					String code = quest.getCodeString();
					for (ServerPlayerEntity p : event.getOnlineMembers()) {
						com.akira.redtale.economy.module.impl.BattlePassModule.addQuestXp(p.getUuid(), code, xp);
					}
				} catch (Exception e) {
					RedtaleEconomy.LOGGER.warn("[ftbq] 发放战令经验失败：{}", e.getMessage());
				}
				return dev.architectury.event.EventResult.pass();
			});
			RedtaleEconomy.LOGGER.info("[ftbq] 已挂接 ObjectCompletedEvent.QUEST → 战令经验");
		} catch (Throwable t) {
			RedtaleEconomy.LOGGER.error("[ftbq] 挂接任务完成事件失败：{}", t.toString());
		}
	}

	/** 四层优先级取值；都取不到返回 0。 */
	private static long resolveQuestXp(dev.ftb.mods.ftbquests.quest.Quest quest) {
		try {
			for (var reward : quest.getRewards()) {
				if (reward instanceof BpXpReward bp) return bp.getXp();
			}
		} catch (Exception ignored) {
			// 奖励表读不出来就继续用配置兜底
		}
		String code = quest.getCodeString();
		long byQuest = com.akira.redtale.economy.config.BattlePassConfig.ftbqByQuest(code);
		if (byQuest >= 0) return byQuest;
		try {
			var chapter = quest.getChapter();
			if (chapter != null) {
				long byChapter = com.akira.redtale.economy.config.BattlePassConfig
						.ftbqByChapter(chapter.getCodeString());
				if (byChapter >= 0) return byChapter;
			}
		} catch (Exception ignored) {
			// 章节信息缺失时走兜底
		}
		return com.akira.redtale.economy.config.BattlePassConfig.ftbqDefault();
	}

	public static RewardType moneyType() {
		return moneyType;
	}

	public static boolean registered() {
		return registered;
	}

	/** {@code /quests} 实际执行的命令（未装 FTBQ 返回 null）。 */
	public static String entryCommand() {
		if (!FabricLoader.getInstance().isModLoaded("ftbquests")) return null;
		if (!FtbqConfig.enabled() || !FtbqConfig.showEntry()) return null;
		return FtbqConfig.entryCommand();
	}

	/** 供命令层判空用。 */
	public static boolean available() {
		return FabricLoader.getInstance().isModLoaded("ftbquests");
	}
}
