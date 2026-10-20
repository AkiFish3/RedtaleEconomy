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
				.executes(BountyCommands::open)
				.then(CommandManager.literal("list").executes(BountyCommands::list))
				.then(CommandManager.literal("claim")
						.then(CommandManager.argument("index", IntegerArgumentType.integer(1, Math.max(1, com.akira.redtale.economy.config.GuildConfig.maxBountySlots())))
								.executes(BountyCommands::claim)))
				.then(CommandManager.literal("claimall").executes(BountyCommands::claimAll))
				.then(CommandManager.literal("reroll")
						.requires(src -> Permissions.has(src, Permissions.NODE_ADMIN))
						.executes(BountyCommands::reroll)));
	}

	/** U7：列表类信息进 GUI；文本列表保留在 {@code /bounty list}。 */
	private static int open(CommandContext<ServerCommandSource> ctx) {
		ServerPlayerEntity p = ctx.getSource().getPlayer();
  PageCommands.hintLegacy(p, "bounty", "bounty");
		if (p == null) return onlyPlayer(ctx.getSource());
		if (!BountyModule.enabled()) {
			ctx.getSource().sendError(Text.literal(Lang.msg("bounty.disabled")));
			return 0;
		}
		com.akira.redtale.economy.net.BountyNetworking.send(p);
		return 1;
	}

	private static int list(CommandContext<ServerCommandSource> ctx) {
		ServerCommandSource src = ctx.getSource();
		ServerPlayerEntity p = src.getPlayer();
		if (p == null) return onlyPlayer(src);
		if (!BountyModule.enabled()) {
			src.sendError(Text.literal(Lang.msg("bounty.disabled")));
			return 0;
		}
		// 只下发快照（Hub 用它画卡片），不刷聊天
		com.akira.redtale.economy.net.BountyNetworking.send(p);
		return 1;
	}

	private static int claim(CommandContext<ServerCommandSource> ctx) {
		ServerCommandSource src = ctx.getSource();
		ServerPlayerEntity p = src.getPlayer();
		if (p == null) return onlyPlayer(src);
		int index = IntegerArgumentType.getInteger(ctx, "index") - 1;
		long reward = BountyModule.claim(p.getUuid(), index);
		if (reward < 0) {
			p.sendMessage(Text.literal(Lang.msg("bounty.notReady")), true);
			return 0;
		}
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
			p.sendMessage(Text.literal(Lang.msg("bounty.notReady")), true);
			return 0;
		}
		final int count = n;
		final long sum = total;
		return 1;
	}

	private static int reroll(CommandContext<ServerCommandSource> ctx) {
		ServerCommandSource src = ctx.getSource();
		ServerPlayerEntity p = src.getPlayer();
		if (p == null) return onlyPlayer(src);
		BountyModule.reroll(p.getUuid());
		return list(ctx);
	}

	private static int onlyPlayer(ServerCommandSource src) {
		src.sendError(Text.literal(Lang.t("cmd.onlyPlayer")));
		return 0;
	}
}
