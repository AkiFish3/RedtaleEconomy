package com.akira.redtale.economy.command;

import com.akira.redtale.economy.config.BattlePassConfig;
import com.akira.redtale.economy.lang.Lang;
import com.akira.redtale.economy.module.impl.BattlePassModule;
import com.akira.redtale.economy.perm.Permissions;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

/**
 * {@code /pass} —— 战令面板（R20）：看进度、领奖励、解锁付费轨。
 */
public final class PassCommands {

	private PassCommands() {
	}

	public static void register(CommandDispatcher<ServerCommandSource> d) {
		d.register(CommandManager.literal("pass")
				.requires(src -> Permissions.has(src, Permissions.NODE_USE))
				.executes(PassCommands::info)
				.then(CommandManager.literal("claim")
						.then(CommandManager.argument("level", IntegerArgumentType.integer(1, 500))
								.executes(ctx -> claim(ctx, "free"))
								.then(CommandManager.literal("premium").executes(ctx -> claim(ctx, "premium")))))
				.then(CommandManager.literal("unlock").executes(PassCommands::unlock))
				.then(CommandManager.literal("addxp")
						.requires(src -> Permissions.has(src, Permissions.NODE_ADMIN))
						.then(CommandManager.argument("player", EntityArgumentType.player())
								.then(CommandManager.argument("amount", LongArgumentType.longArg(1, 1_000_000))
										.executes(PassCommands::addXp)))));
	}

	private static int info(CommandContext<ServerCommandSource> ctx) {
		ServerCommandSource src = ctx.getSource();
		ServerPlayerEntity p = src.getPlayer();
		if (p == null) return onlyPlayer(src);
		if (!BattlePassModule.enabled()) {
			src.sendError(Text.literal(Lang.msg("pass.disabled")));
			return 0;
		}
		BattlePassModule.PassData d = BattlePassModule.snapshot(p.getUuid());
		int level = BattlePassModule.levelFor(d.xp);
		int pct = (int) Math.round(BattlePassModule.progress(d.xp) * 100);
		src.sendFeedback(() -> Text.literal(Lang.msg("pass.header",
				BattlePassConfig.seasonName(), level, BattlePassConfig.maxLevel())), false);
		src.sendFeedback(() -> Text.literal(Lang.msg("pass.bar", pct, d.xp,
				BattlePassModule.xpForLevel(Math.min(BattlePassConfig.maxLevel(), level + 1)))), false);
		src.sendFeedback(() -> Text.literal(Lang.msg("pass.track",
				d.premium ? Lang.t("pass.unlocked") : Lang.t("pass.locked"))), false);
		src.sendFeedback(() -> Text.literal(Lang.msg("pass.free", freeCount(d), BattlePassConfig.track("free").size())), false);
		src.sendFeedback(() -> Text.literal(Lang.msg("pass.premiumTrack",
			 d.claimedPremium.size(), BattlePassConfig.track("premium").size())), false);
		src.sendFeedback(() -> Text.literal(Lang.msg("pass.hint",
				StringArgumentType.escapeIfRequired("pass claim <level>"))), false);
		return 1;
	}

	private static int freeCount(BattlePassModule.PassData d) {
		return d.claimedFree.size();
	}

	private static int claim(CommandContext<ServerCommandSource> ctx, String track) {
		ServerCommandSource src = ctx.getSource();
		ServerPlayerEntity p = src.getPlayer();
		if (p == null) return onlyPlayer(src);
		int level = IntegerArgumentType.getInteger(ctx, "level");
		BattlePassModule.ClaimResult r = BattlePassModule.claim(p.getUuid(), track, level);
		switch (r) {
			case OK -> {
				src.sendFeedback(() -> Text.literal(Lang.msg("pass.claimed", track, level)), false);
				return 1;
			}
			case LOCKED -> {
				src.sendError(Text.literal(Lang.msg("pass.needUnlock")));
				return 0;
			}
			case NOT_REACHED -> {
				src.sendError(Text.literal(Lang.msg("pass.notReached", level)));
				return 0;
			}
			case ALREADY -> {
				src.sendError(Text.literal(Lang.msg("pass.already")));
				return 0;
			}
			default -> {
				src.sendError(Text.literal(Lang.msg("pass.disabled")));
				return 0;
			}
		}
	}

	private static int unlock(CommandContext<ServerCommandSource> ctx) {
		ServerCommandSource src = ctx.getSource();
		ServerPlayerEntity p = src.getPlayer();
		if (p == null) return onlyPlayer(src);
		if (BattlePassModule.unlockPremium(p.getUuid())) {
			src.sendFeedback(() -> Text.literal(Lang.msg("pass.unlockOk",
					BattlePassConfig.unlockAmount(), BattlePassConfig.unlockCurrency())), false);
			return 1;
		}
		src.sendError(Text.literal(Lang.msg("pass.unlockFail")));
		return 0;
	}

	private static int addXp(CommandContext<ServerCommandSource> ctx) {
		ServerCommandSource src = ctx.getSource();
		try {
			ServerPlayerEntity target = EntityArgumentType.getPlayer(ctx, "player");
			long amount = LongArgumentType.getLong(ctx, "amount");
			BattlePassModule.addXp(target.getUuid(), "admin", amount);
			src.sendFeedback(() -> Text.literal(Lang.msg("pass.addXp",
					target.getName().getString(), amount,
					BattlePassModule.levelFor(BattlePassModule.snapshot(target.getUuid()).xp))), false);
			return 1;
		} catch (Exception e) {
			src.sendError(Text.literal(String.valueOf(e.getMessage())));
			return 0;
		}
	}

	private static int onlyPlayer(ServerCommandSource src) {
		src.sendError(Text.literal(Lang.t("cmd.onlyPlayer")));
		return 0;
	}
}
