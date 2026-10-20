package com.akira.redtale.economy.net;

import com.akira.redtale.economy.RedtaleEconomy;
import com.akira.redtale.economy.config.BountyConfig;
import com.akira.redtale.economy.module.impl.BountyModule;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.ArrayList;
import java.util.List;

/** 悬赏面板下发（U7）。软失败：发包异常只记日志，不影响命令本身。 */
public final class BountyNetworking {

	private BountyNetworking() {
	}

	/** 由服务端权威状态生成快照；未启用的悬赏定义直接跳过。 */
	public static BountyProtocol.Snapshot snapshot(java.util.UUID uuid) {
		List<BountyProtocol.Entry> out = new ArrayList<>();
		var slots = BountyModule.slots(uuid);
		for (int i = 0; i < slots.size(); i++) {
			com.akira.redtale.economy.bounty.BountyState.Slot s = slots.get(i);
			BountyConfig.Def d = BountyModule.definition(s.id);
			if (d == null) continue;
			out.add(new BountyProtocol.Entry(i, d.id(), s.progress, d.count(), d.money(), s.claimed));
		}
		return new BountyProtocol.Snapshot(out);
	}

	public static void send(ServerPlayerEntity player) {
		try {
			PacketByteBuf buf = new PacketByteBuf(io.netty.buffer.Unpooled.buffer());
			BountyProtocol.write(buf, snapshot(player.getUuid()));
			ServerPlayNetworking.send(player, PacketIds.S2C_BOUNTY, buf);
		} catch (Exception e) {
			RedtaleEconomy.LOGGER.error("[bounty] 下发悬赏面板失败：{}", e.getMessage());
		}
	}
}
