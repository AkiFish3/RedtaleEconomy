package com.akira.redtale.economy.command;

import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.command.CommandSource;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.ArrayList;
import java.util.List;

/** 命令补全的共享 provider（B13）。 */
public final class Suggest {

	private Suggest() {
	}

	/** 在线玩家名。 */
	public static SuggestionProvider<ServerCommandSource> players() {
		return (ctx, builder) -> {
			List<String> names = new ArrayList<>();
			for (ServerPlayerEntity p : ctx.getSource().getServer().getPlayerManager().getPlayerList()) {
				names.add(p.getGameProfile().getName());
			}
			return CommandSource.suggestMatching(names, builder);
		};
	}

	/** 0..count-1 的任务槽位。 */
	public static SuggestionProvider<ServerCommandSource> slots(int count) {
		return (ctx, builder) -> {
			List<String> out = new ArrayList<>();
			for (int i = 0; i < Math.max(1, count); i++) out.add(String.valueOf(i));
			return CommandSource.suggestMatching(out, builder);
		};
	}

	/**
	 * 日 + 周两段槽位（T06）：日 {@code 0..daily-1}、周 {@code 100..100+weekly-1}。
	 *
	 * <p>只补日任务的话，周任务的 slot 永远不会出现在补全里 —— 玩家照着补全点，周任务永远点不到。
	 */
	public static SuggestionProvider<ServerCommandSource> taskSlots(int daily, int weekly) {
		return (ctx, builder) -> {
			List<String> out = new ArrayList<>();
			for (int i = 0; i < Math.max(0, daily); i++) out.add(String.valueOf(i));
			for (int i = 0; i < Math.max(0, weekly); i++) {
				out.add(String.valueOf(com.akira.redtale.economy.module.impl.TaskModule.WEEKLY_SLOT_BASE + i));
			}
			return CommandSource.suggestMatching(out, builder);
		};
	}
}
