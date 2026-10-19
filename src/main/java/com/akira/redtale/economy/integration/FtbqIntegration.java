package com.akira.redtale.economy.integration;

import com.akira.redtale.economy.RedtaleEconomy;
import com.akira.redtale.economy.config.ConfigManager;
import com.akira.redtale.economy.config.FtbqConfig;
import dev.ftb.mods.ftblibrary.icon.Icon;
import dev.ftb.mods.ftbquests.quest.reward.RewardType;
import dev.ftb.mods.ftbquests.quest.reward.RewardTypes;
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
	private static boolean registered;

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
		if (registered) return;
		if (!FtbqConfig.registerReward()) {
			RedtaleEconomy.LOGGER.info("[ftbq] registerReward=false，不注册 money 奖励类型");
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
