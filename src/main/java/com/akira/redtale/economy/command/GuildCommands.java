package com.akira.redtale.economy.command;

import com.akira.redtale.economy.config.GuildConfig;
import com.akira.redtale.economy.lang.Lang;
import com.akira.redtale.economy.module.impl.GuildModule;
import com.akira.redtale.economy.perm.Permissions;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

/**
 * {@code /guild} —— 冒险者协会（R32）。
 *
 * <p>玩家看自己的等级/声望/下一档与当前特权；管理用 {@code /guild add} 手动补声望（迁移/活动补偿）。
 */
public final class GuildCommands {

	private GuildCommands() {
	}

	public static void register(CommandDispatcher<ServerCommandSource> d) {
		d.register(CommandManager.literal("guild")
				.requires(src -> Permissions.has(src, Permissions.NODE_USE))
				.executes(GuildCommands::info)
				.then(CommandManager.literal("add")
						.requires(src -> Permissions.has(src, Permissions.NODE_ADMIN))
						.then(CommandManager.argument("player", EntityArgumentType.player())
								.then(CommandManager.argument("amount", LongArgumentType.longArg(1, 10_000_000))
										.executes(GuildCommands::add)))));
	}

	private static int info(CommandContext<ServerCommandSource> ctx) {
		ServerCommandSource src = ctx.getSource();
		ServerPlayerEntity p = src.getPlayer();
		if (p == null) return onlyPlayer(src);
		if (!GuildModule.enabled()) {
			src.sendError(Text.literal(Lang.msg("guild.disabled")));
			return 0;
		}
		GuildConfig.Rank cur = GuildModule.rankOf(p.getUuid());
		if (cur == null) {
			src.sendError(Text.literal(Lang.msg("guild.noRanks")));
			return 0;
		}
		long rep = GuildModule.rep(p.getUuid());
		GuildConfig.Rank next = GuildModule.nextRank(p.getUuid());
		src.sendFeedback(() -> Text.literal(Lang.msg("guild.info", cur.id(), cur.name(), rep)), false);
		if (next == null) {
			src.sendFeedback(() -> Text.literal(Lang.t("guild.maxRank")), false);
		} else {
			long need = Math.max(0, next.rep() - rep);
			src.sendFeedback(() -> Text.literal(Lang.msg("guild.next", next.id(), next.name(), need)), false);
		}
		int pct = (int) Math.round(GuildModule.shopMultiplier(p.getUuid()) * 100);
		src.sendFeedback(() -> Text.literal(Lang.msg("guild.perks", 100 - pct,
				GuildModule.bonusBountySlots(p.getUuid()))), false);
		return 1;
	}

	private static int add(CommandContext<ServerCommandSource> ctx) {
		ServerCommandSource src = ctx.getSource();
		try {
			ServerPlayerEntity target = EntityArgumentType.getPlayer(ctx, "player");
			long amount = LongArgumentType.getLong(ctx, "amount");
			GuildModule.addRep(target.getUuid(), "admin", amount);
			src.sendFeedback(() -> Text.literal(Lang.msg("guild.addOk",
					target.getName().getString(), amount, GuildModule.rep(target.getUuid()))), true);
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
