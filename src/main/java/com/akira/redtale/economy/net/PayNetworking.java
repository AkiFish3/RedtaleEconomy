package com.akira.redtale.economy.net;

import com.akira.redtale.economy.config.EconomyConfig;
import com.akira.redtale.economy.economy.BalanceService;
import com.akira.redtale.economy.module.PayService;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.text.Text;

/**
 * 界面内转账。
 *
 * <p>★这里不再自己做校验★：全部转交给 {@link PayService}，与 {@code /pay} 共用
 * 一份规则（开关 / 上下限 / 冷却 / 税率 / 自转）。以前这条路径自己写了一遍，
 * 结果界面转账免税、无冷却、不限额度、可自转，还能对离线玩家报「找不到在线玩家」。
 */
public final class PayNetworking {

	private PayNetworking() {
	}

	public static void register() {
		ServerPlayNetworking.registerGlobalReceiver(PacketIds.C2S_PAY,
				(server, player, h, buf, sender) -> {
					String target = buf.readString(32);
					long amount = buf.readLong();
					server.execute(() -> {
						PayService.Result r = PayService.pay(player, target, amount);
						player.sendMessage(Text.literal(message(r, target)), true);
						if (r.code() == PayService.Code.OK) {
							BalanceNetworking.send(player);
						}
					});
				});
	}

	/** 界面路径走 actionbar（Hub 内一切回显都不许进聊天栏）。 */
	private static String message(PayService.Result r, String rawTarget) {
		return switch (r.code()) {
			case OK -> "§a已转给 " + r.targetName() + " " + BalanceService.format(r.amount())
					+ (r.queued() ? "（对方离线，上线后自动到账）" : "")
					+ (r.tax() > 0 ? "（手续费 " + BalanceService.format(r.tax()) + "）" : "");
			case DISABLED -> "§c转账功能已关闭";
			case BAD_AMOUNT -> "§c金额必须大于 0";
			case UNKNOWN_PLAYER -> "§c找不到玩家：" + rawTarget + "（对方需至少登录过一次）";
			case SELF -> "§c不能转给自己";
			case MIN -> "§c单笔最少 " + BalanceService.format(EconomyConfig.payMin());
			case MAX -> "§c单笔最多 " + BalanceService.format(EconomyConfig.payMax());
			case COOLDOWN -> "§c请等待 " + r.waitSec() + " 秒后再转账";
			case INSUFFICIENT -> "§c余额不足";
		};
	}
}
