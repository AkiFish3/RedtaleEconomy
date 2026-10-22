package com.akira.redtale.economy.command;

import com.akira.redtale.economy.perm.Permissions;
import com.akira.redtale.economy.config.CoreConfig;
import com.akira.redtale.economy.config.EconomyConfig;
import com.akira.redtale.economy.economy.BalanceService;
import com.akira.redtale.economy.economy.Currency;
import com.akira.redtale.economy.lang.Lang;
import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.util.UUID;

/** {@code /bal} {@code /pay}。所有金额在服务端换算，客户端只传字符串。 */
public final class EconomyCommands {

	private EconomyCommands() {
	}

	public static void register(CommandDispatcher<ServerCommandSource> d) {
		d.register(bal());
		d.register(pay());
	}

	/**
	 * U8 收尾：把 {@code bal} / {@code pay} 也挂到 {@code /rt} 下（{@code /rt bal}、{@code /rt pay}）。
	 *
	 * <p>根命名空间的 {@code /bal} {@code /pay} 按 spec §7 <b>保留</b>（玩家手里有老快捷键），
	 * 这里只是让「一个入口」这件事彻底成立。字面量优先于 {@code /rt <page>} 的 page 参数节点，
	 * 所以不会互相抢。
	 */
	public static void attach(com.mojang.brigadier.builder.LiteralArgumentBuilder<ServerCommandSource> root) {
		root.then(bal()).then(pay());
	}

	private static com.mojang.brigadier.builder.LiteralArgumentBuilder<ServerCommandSource> bal() {
		return CommandManager.literal("bal").requires(src -> Permissions.has(src, Permissions.NODE_USE))
				.executes(ctx -> self(ctx))
				.then(CommandManager.argument("player", StringArgumentType.word()).suggests(Suggest.players())
						.requires(src -> Permissions.has(src, Permissions.NODE_USE))
						.executes(EconomyCommands::other));
	}

	private static com.mojang.brigadier.builder.LiteralArgumentBuilder<ServerCommandSource> pay() {
		return CommandManager.literal("pay").requires(src -> Permissions.has(src, Permissions.NODE_USE))
				.then(CommandManager.argument("player", StringArgumentType.word()).suggests(Suggest.players())
						.then(CommandManager.argument("amount", StringArgumentType.word())
								.executes(EconomyCommands::pay)));
	}

	private static int self(CommandContext<ServerCommandSource> ctx) {
		ServerPlayerEntity p = ctx.getSource().getPlayer();
		if (p == null) {
			ctx.getSource().sendError(Text.literal("该命令只能由玩家执行"));
			return 0;
		}
		long v = BalanceService.get(p.getUuid());
		ctx.getSource().sendFeedback(() -> Text.literal(Lang.msg("cmd.bal.self", BalanceService.format(v))), false);
		return 1;
	}

	private static int other(CommandContext<ServerCommandSource> ctx) {
		String name = StringArgumentType.getString(ctx, "player");
		UUID id = resolve(ctx.getSource(), name);
		if (id == null) {
			ctx.getSource().sendError(Text.literal(Lang.msg("cmd.bal.unknown", name)));
			return 0;
		}
		long v = BalanceService.get(id);
		if (v < 0) {
			ctx.getSource().sendError(Text.literal(Lang.msg("cmd.bal.unknown", name)));
			return 0;
		}
		ctx.getSource().sendFeedback(() -> Text.literal(Lang.msg("cmd.bal.other", name, BalanceService.format(v))), false);
		return 1;
	}

	private static int pay(CommandContext<ServerCommandSource> ctx) {
		ServerCommandSource src = ctx.getSource();
		ServerPlayerEntity sender = src.getPlayer();
		if (sender == null) {
			src.sendError(Text.literal("该命令只能由玩家执行"));
			return 0;
		}

		String name = StringArgumentType.getString(ctx, "player");
		String raw = StringArgumentType.getString(ctx, "amount");
		long amount = Currency.parse(raw);
		if (amount <= 0) {
			src.sendError(Text.literal(Lang.msg("cmd.pay.badAmount", raw)));
			return 0;
		}

		// ★校验与账务只有一份★（PayService）：界面路径 PayNetworking 也调它。
		// 以前这里和 PayNetworking 各写一套，界面那条完全不读配置（免税 / 无冷却 / 可自转）。
		com.akira.redtale.economy.module.PayService.Result r =
				com.akira.redtale.economy.module.PayService.pay(sender, name, amount);
		switch (r.code()) {
			case OK -> {
				src.sendFeedback(() -> Text.literal(Lang.msg("cmd.pay.sent", r.targetName(),
						BalanceService.format(r.amount()), BalanceService.format(r.tax()))
						+ (r.queued() ? "（对方离线，上线后自动到账）" : "")), false);
				return 1;
			}
			case DISABLED -> src.sendError(Text.literal(Lang.msg("cmd.pay.disabled")));
			case BAD_AMOUNT -> src.sendError(Text.literal(Lang.msg("cmd.pay.badAmount", raw)));
			case UNKNOWN_PLAYER -> src.sendError(Text.literal(
					"找不到玩家：" + name + "（需要对方至少登录过一次服务器）"));
			case SELF -> src.sendError(Text.literal(Lang.msg("cmd.pay.self")));
			case MIN -> src.sendError(Text.literal(
					Lang.msg("cmd.pay.min", BalanceService.format(EconomyConfig.payMin()))));
			case MAX -> src.sendError(Text.literal(
					Lang.msg("cmd.pay.max", BalanceService.format(EconomyConfig.payMax()))));
			case COOLDOWN -> src.sendError(Text.literal(Lang.msg("cmd.pay.cooldown", r.waitSec())));
			default -> src.sendError(Text.literal(Lang.msg("cmd.pay.insufficient",
					BalanceService.format(BalanceService.get(sender.getUuid())),
					BalanceService.format(amount))));
		}
		return 0;
	}

	private static UUID resolve(ServerCommandSource src, String name) {
		ServerPlayerEntity online = src.getServer().getPlayerManager().getPlayer(name);
		if (online != null) return online.getUuid();
		return src.getServer().getUserCache().findByName(name).map(GameProfile::getId).orElse(null);
	}
}
