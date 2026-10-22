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
		CommandTable.declare(d, "checkin", "checkin");
		d.register(CommandManager.literal("checkin").requires(src -> Permissions.has(src, Permissions.NODE_USE))
				.executes(CheckinCommands::doCheckin)
				.then(CommandManager.literal("make-up").executes(CheckinCommands::doMakeup))
				.then(CommandManager.literal("status").executes(CheckinCommands::status)));
	}

	private static int doCheckin(CommandContext<ServerCommandSource> ctx) {
		ServerCommandSource src = ctx.getSource();
		ServerPlayerEntity p = src.getPlayer();
		PageCommands.hintLegacy(p, "checkin", "checkin");
		if (p == null) {
			src.sendError(Text.literal(Lang.t("cmd.onlyPlayer")));
			return 0;
		}
		// T18：防小号（默认关闭）。拒绝时不改状态、不发奖，直接回动作栏
		com.akira.redtale.economy.storage.AltGuard.Decision alt =
				com.akira.redtale.economy.storage.AltGuard.gate(p.getUuid(), p.getName().getString(), p.getIp(), "checkin");
		if (!alt.allowed()) {
			p.sendMessage(Text.literal("§c[防小号] " + alt.reason()), true);
			return 0;
		}
		CheckinModule.Outcome o = CheckinModule.checkin(p.getUuid());		switch (o.result()) {
			case OK -> Feedback.success(src, Text.literal(
					Lang.msg("checkin.ok", o.streak(), BalanceService.format(o.reward()))
							+ (o.bonus().isEmpty() ? "" : " · " + o.bonus())));
			case NO_TIER -> p.sendMessage(Text.literal(
					Lang.msg("checkin.noTier", o.streak())
							+ (o.bonus().isEmpty() ? "" : " · " + o.bonus())), true);
			case ALREADY -> p.sendMessage(Text.literal(
					Lang.msg("checkin.already", o.streak())), true);
			// 落盘失败（未发奖，可原地重试）：复用通用写盘失败文案，不新增 assets 键
			case IO_FAIL -> src.sendError(Text.literal(Lang.msg("market.err.saveFail")));
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
			// 落盘失败（成本已退回）：复用通用的写盘失败文案，避免新增 assets 键（assets 归 B）
			case IO_FAIL -> "market.err.saveFail";
		};
		String ticket = CheckinModule.store().getString("makeup.ticketItem", "");
		long price = CheckinModule.store().getPathLong("makeup.price", 500);
		String cost = ticket == null || ticket.isBlank()
				? BalanceService.format(price) : ticket;
		Text msg = Text.literal(Lang.msg(key, cost)
				+ (r == CheckinModule.MakeupResult.OK
						? " · " + CheckinModule.LAST_MAKEUP_DATE
								+ (CheckinModule.LAST_MAKEUP_BONUS.isEmpty()
										? "" : " · " + CheckinModule.LAST_MAKEUP_BONUS)
						: ""));
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
		// ★只下发快照，不往聊天里写东西★
		// Hub 打开时会调用本命令拉数据，若在这里 sendFeedback，玩家每开一次界面就被刷一条"已签到"。
		com.akira.redtale.economy.net.CheckinNetworking.sendSnapshot(p);
		return 1;
	}
}
