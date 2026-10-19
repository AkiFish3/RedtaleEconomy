package com.akira.redtale.economy.command;

import com.akira.redtale.economy.bounty.BountyState;
import com.akira.redtale.economy.config.BountyConfig;
import com.akira.redtale.economy.economy.BalanceService;
import com.akira.redtale.economy.lang.Lang;
import com.akira.redtale.economy.module.impl.BountyModule;
import com.akira.redtale.economy.perm.Permissions;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

/**
 * {@code /bounty} —— 系统悬赏（R31）：看进度、领奖励、管理重掷。
 *
 * <p>索引对外从 1 开始（和界面一致），对内 0 起。
 */
public final class BountyCommands {

	private BountyCommands() {
	}

	public static void register(CommandDispatcher<ServerCommandSource> d) {
		d.register(CommandManager.literal("bounty")
				.requires(src -> Permissions.has(src, Permissions.NODE_USE))
				.executes(BountyCommands::list)
				.then(CommandManager.literal("claim")
						.then(CommandManager.argument("index", IntegerArgumentType.integer(1, Math.max(1, com.akira.redtale.economy.config.GuildConfig.maxBountySlots())))
								.executes(BountyCommands::claim)))
				.then(CommandManager.literal("claimall").executes(BountyCommands::claimAll))
				.then(CommandManager.literal("reroll")
						.requires(src -> Permissions.has(src, Permissions.NODE_ADMIN))
						.executes(BountyCommands::reroll)));
	}

	private static int list(CommandContext<ServerCommandSource> ctx) {
		ServerCommandSource src = ctx.getSource();
		ServerPlayerEntity p = src.getPlayer();
		if (p == null) return onlyPlayer(src);
		if (!BountyModule.enabled()) {
			src.sendError(Text.literal(Lang.msg("bounty.disabled")));
			return 0;
		}
		var slots = BountyModule.slots(p.getUuid());
		src.sendFeedback(() -> Text.literal(Lang.msg("bounty.header")), false);
		if (slots.isEmpty()) {
			src.sendFeedback(() -> Text.literal(Lang.msg("bounty.empty")), false);
			return 1;
		}
		for (int i = 0; i < slots.size(); i++) {
			BountyState.Slot s = slots.get(i);
			BountyConfig.Def d = BountyModule.definition(s.id);
			if (d == null) continue;
			boolean done = s.progress >= d.count();
			String line = Lang.msg("bounty.line", i + 1, d.id(), s.progress, d.count(),
					BalanceService.format(d.money()))
					+ (s.claimed ? Lang.t("bounty.claimed") : done ? Lang.t("bounty.ready") : "");
			src.sendFeedback(() -> Text.literal(line), false);
		}
		src.sendFeedback(() -> Text.literal(Lang.t("bounty.hint")), false);
		return 1;
	}

	private static int claim(CommandContext<ServerCommandSource> ctx) {
		ServerCommandSource src = ctx.getSource();
		ServerPlayerEntity p = src.getPlayer();
		if (p == null) return onlyPlayer(src);
		int index = IntegerArgumentType.getInteger(ctx, "index") - 1;
		long reward = BountyModule.claim(p.getUuid(), index);
		if (reward < 0) {
			src.sendError(Text.literal(Lang.msg("bounty.notReady")));
			return 0;
		}
		src.sendFeedback(() -> Text.literal(Lang.msg("bounty.claimedOk", BalanceService.format(reward))), false);
		return 1;
	}

	private static int claimAll(CommandContext<ServerCommandSource> ctx) {
		ServerCommandSource src = ctx.getSource();
		ServerPlayerEntity p = src.getPlayer();
		if (p == null) return onlyPlayer(src);
		int n = 0;
		long total = 0;
		for (int i = 0; i < BountyModule.slots(p.getUuid()).size(); i++) {
			long r = BountyModule.claim(p.getUuid(), i);
			if (r >= 0) {
				n++;
				total += r;
			}
		}
		if (n == 0) {
			src.sendError(Text.literal(Lang.msg("bounty.notReady")));
			return 0;
		}
		final int count = n;
		final long sum = total;
		src.sendFeedback(() -> Text.literal(Lang.msg("bounty.claimedAll", count, BalanceService.format(sum))), false);
		return 1;
	}

	private static int reroll(CommandContext<ServerCommandSource> ctx) {
		ServerCommandSource src = ctx.getSource();
		ServerPlayerEntity p = src.getPlayer();
		if (p == null) return onlyPlayer(src);
		BountyModule.reroll(p.getUuid());
		src.sendFeedback(() -> Text.literal(Lang.msg("bounty.rerolled")), false);
		return list(ctx);
	}

	private static int onlyPlayer(ServerCommandSource src) {
		src.sendError(Text.literal(Lang.t("cmd.onlyPlayer")));
		return 0;
	}
}
