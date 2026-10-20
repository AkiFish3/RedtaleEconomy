package com.akira.redtale.economy.net;

import com.akira.redtale.economy.config.BattlePassConfig;
import com.akira.redtale.economy.module.impl.BattlePassModule;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.ArrayList;
import java.util.List;

/** 战令快照下发（R20/U4）。 */
public final class PassNetworking {

	private PassNetworking() {
	}

	public static void send(ServerPlayerEntity player) {
		if (player == null) return;
		BattlePassModule.PassData d = BattlePassModule.snapshot(player.getUuid());
		int level = BattlePassModule.levelFor(d.xp);
		long xpNext = BattlePassModule.xpForLevel(level + 1);
		List<PassProtocol.Reward> free = convert(BattlePassModule.track("free"));
		List<PassProtocol.Reward> prem = convert(BattlePassModule.track("premium"));
		PassProtocol.Snapshot snap = new PassProtocol.Snapshot(
				BattlePassConfig.seasonName(), level, d.xp, xpNext, d.premium,
				free, prem, new ArrayList<>(d.claimedFree), new ArrayList<>(d.claimedPremium));
		net.minecraft.network.PacketByteBuf buf =
				new net.minecraft.network.PacketByteBuf(io.netty.buffer.Unpooled.buffer());
		PassProtocol.write(buf, snap);
		ServerPlayNetworking.send(player, PacketIds.S2C_PASS, buf);
	}

	private static List<PassProtocol.Reward> convert(List<BattlePassConfig.Reward> list) {
		List<PassProtocol.Reward> out = new ArrayList<>();
		for (BattlePassConfig.Reward r : list) {
			List<PassProtocol.ItemReward> items = new ArrayList<>();
			for (BattlePassConfig.ItemReward it : r.items()) {
				items.add(new PassProtocol.ItemReward(it.id(), it.count()));
			}
			out.add(new PassProtocol.Reward(r.level(), r.money(), items));
		}
		return out;
	}
}
