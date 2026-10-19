package com.akira.redtale.economy.command;

import com.akira.redtale.economy.perm.Permissions;
import com.akira.redtale.economy.economy.BalanceService;
import com.akira.redtale.economy.lang.Lang;
import com.akira.redtale.economy.module.impl.CheckinModule;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

/** {@code /checkin} {@code /checkin make-up} {@code /checkin status}。 */
public final class CheckinCommands {

	private CheckinCommands() {
	}

	public static void register(CommandDispatcher<ServerCommandSource> d) {
		d.register(CommandManager.literal("checkin").requires(src -> Permissions.has(src, Permissions.NODE_USE))
				.executes(CheckinCommands::doCheckin)
				.then(CommandManager.literal("make-up").executes(CheckinCommands::doMakeup))
				.then(CommandManager.literal("status").executes(CheckinCommands::status)));
	}

	private static int doCheckin(CommandContext<ServerCommandSource> ctx) {
		ServerCommandSource src = ctx.getSource();
		ServerPlayerEntity p = src.getPlayer();
		if (p == null) {
			src.sendError(Text.literal(Lang.t("cmd.onlyPlayer")));
			return 0;
		}
		CheckinModule.Outcome o = CheckinModule.checkin(p.getUuid());
		switch (o.result()) {
			case OK -> src.sendFeedback(() -> Text.literal(
					Lang.msg("checkin.ok", o.streak(), BalanceService.format(o.reward()))), false);
			case NO_TIER -> src.sendFeedback(() -> Text.literal(
					Lang.msg("checkin.noTier", o.streak())), false);
			case ALREADY -> src.sendFeedback(() -> Text.literal(
					Lang.msg("checkin.already", o.streak())), false);
			default -> src.sendError(Text.literal(Lang.msg("checkin.disabled")));
		}
		// U3：同步刷新可视化面板
		com.akira.redtale.economy.net.CheckinNetworking.sendSnapshot(p);
		return 1;
	}

	private static int doMakeup(CommandContext<ServerCommandSource> ctx) {
		ServerCommandSource src = ctx.getSource();
		ServerPlayerEntity p = src.getPlayer();
		if (p == null) {
			src.sendError(Text.literal(Lang.t("cmd.onlyPlayer")));
			return 0;
		}
		CheckinModule.MakeupResult r = CheckinModule.makeup(p.getUuid());
		String key = switch (r) {
			case OK -> "checkin.makeup.ok";
			case DISABLED -> "checkin.makeup.disabled";
			case LIMIT -> "checkin.makeup.limit";
			case NOT_NEEDED -> "checkin.makeup.notNeeded";
			case NO_FUNDS -> "checkin.makeup.noFunds";
			case TOO_OLD -> "checkin.makeup.tooOld";
			case NO_TICKET -> "checkin.makeup.noTicket";
		};
		String ticket = CheckinModule.store().getString("makeup.ticketItem", "");
		long price = CheckinModule.store().getPathLong("makeup.price", 500);
		String cost = ticket == null || ticket.isBlank()
				? BalanceService.format(price) : ticket;
		Text msg = Text.literal(Lang.msg(key, cost));
		if (r == CheckinModule.MakeupResult.OK) {
			src.sendFeedback(() -> msg, false);
			com.akira.redtale.economy.net.CheckinNetworking.sendSnapshot(p);
			return 1;
		}
		src.sendError(msg);
		return 0;
	}

	private static int status(CommandContext<ServerCommandSource> ctx) {
		ServerCommandSource src = ctx.getSource();
		ServerPlayerEntity p = src.getPlayer();
		if (p == null) {
			src.sendError(Text.literal(Lang.t("cmd.onlyPlayer")));
			return 0;
		}
		CheckinModule.State s = CheckinModule.state(p.getUuid());
		String today = com.akira.redtale.economy.util.TimeUtil.today(CheckinModule.resetHour());
		boolean signedToday = today.equals(s.lastDate);
		src.sendFeedback(() -> Text.literal(Lang.msg("checkin.status", s.streak, s.totalDays,
				Lang.t(signedToday ? "checkin.status.signed" : "checkin.status.notSigned"))), false);
		com.akira.redtale.economy.net.CheckinNetworking.sendSnapshot(p);
		return 1;
	}
}
