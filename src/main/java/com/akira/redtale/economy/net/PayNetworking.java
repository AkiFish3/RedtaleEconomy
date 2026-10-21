package com.akira.redtale.economy.net;

import com.akira.redtale.economy.economy.BalanceService;
import com.akira.redtale.economy.storage.Ledger;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

/** 界面内转账（等价 /pay，但走界面更安全：这里做全部校验）。 */
public final class PayNetworking {

	private PayNetworking() {
	}

	public static void register() {
		ServerPlayNetworking.registerGlobalReceiver(PacketIds.C2S_PAY,
				(server, player, h, buf, sender) -> {
					String target = buf.readString(32);
					long amount = buf.readLong();
					server.execute(() -> doPay(player, target, amount));
				});
	}

	private static void doPay(ServerPlayerEntity from, String targetName, long amount) {
		if (amount <= 0) {
			from.sendMessage(Text.literal("§c金额必须大于 0"), true);
			return;
		}
		ServerPlayerEntity to = from.server.getPlayerManager().getPlayer(targetName);
		if (to == null) {
			from.sendMessage(Text.literal("§c找不到在线玩家：" + targetName), true);
			return;
		}
		if (to.getUuid().equals(from.getUuid())) {
			from.sendMessage(Text.literal("§c不能转给自己"), true);
			return;
		}
		if (!BalanceService.take(from.getUuid(), amount, Ledger.TYPE_PAY, "to:" + targetName)) {
			from.sendMessage(Text.literal("§c余额不足"), true);
			return;
		}
		BalanceService.add(to.getUuid(), amount, Ledger.TYPE_PAY, "from:" + from.getGameProfile().getName());
		from.sendMessage(Text.literal("§a已转给 " + targetName + " " + BalanceService.format(amount)), true);
		to.sendMessage(Text.literal("§a收到 " + from.getGameProfile().getName()
				+ " 转账 " + BalanceService.format(amount)), true);
		com.akira.redtale.economy.net.BalanceNetworking.send(from);
		com.akira.redtale.economy.net.BalanceNetworking.send(to);
	}
}
