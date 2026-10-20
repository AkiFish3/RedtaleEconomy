package com.akira.redtale.economy.net;

import com.akira.redtale.economy.config.GuildConfig;
import com.akira.redtale.economy.module.impl.GuildModule;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.ArrayList;
import java.util.List;

/** 协会快照下发。 */
public final class GuildNetworking {

	private GuildNetworking() {
	}

	public static void send(ServerPlayerEntity player) {
		if (player == null) return;
		long rep = GuildModule.rep(player.getUuid());
		GuildConfig.Rank cur = GuildModule.rankOf(player.getUuid());
		List<GuildProtocol.Rank> ranks = new ArrayList<>();
		for (GuildConfig.Rank r : GuildConfig.ranks()) {
			ranks.add(new GuildProtocol.Rank(r.name(), r.rep()));
		}
		GuildProtocol.Snapshot snap = new GuildProtocol.Snapshot(
				cur == null ? "" : cur.name(), GuildModule.rankIndex(player.getUuid()), rep, ranks);
		PacketByteBuf buf = new PacketByteBuf(io.netty.buffer.Unpooled.buffer());
		GuildProtocol.write(buf, snap);
		ServerPlayNetworking.send(player, PacketIds.S2C_GUILD, buf);
	}
}
