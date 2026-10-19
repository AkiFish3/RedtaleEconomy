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

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** {@code /bal} {@code /pay}。所有金额在服务端换算，客户端只传字符串。 */
public final class EconomyCommands {

	private static final Map<UUID, Long> LAST_PAY = new HashMap<>();

	private EconomyCommands() {
	}

	public static void register(CommandDispatcher<ServerCommandSource> d) {
		d.register(CommandManager.literal("bal").requires(src -> Permissions.has(src, Permissions.NODE_USE))
				.executes(ctx -> self(ctx))
				.then(CommandManager.argument("player", StringArgumentType.word()).suggests(Suggest.players())
						.requires(src -> Permissions.has(src, Permissions.NODE_USE))
						.executes(EconomyCommands::other)));

		d.register(CommandManager.literal("pay").requires(src -> Permissions.has(src, Permissions.NODE_USE))
				.then(CommandManager.argument("player", StringArgumentType.word()).suggests(Suggest.players())
						.then(CommandManager.argument("amount", StringArgumentType.word())
								.executes(EconomyCommands::pay))));
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
		if (!EconomyConfig.payEnabled()) {
			src.sendError(Text.literal(Lang.msg("cmd.pay.disabled")));
			return 0;
		}

		String name = StringArgumentType.getString(ctx, "player");
		String raw = StringArgumentType.getString(ctx, "amount");
		long amount = Currency.parse(raw);
		if (amount <= 0) {
			src.sendError(Text.literal(Lang.msg("cmd.pay.badAmount", raw)));
			return 0;
		}

		ServerPlayerEntity target = src.getServer().getPlayerManager().getPlayer(name);
		if (target == null) {
			src.sendError(Text.literal(Lang.msg("cmd.pay.offline", name)));
			return 0;
		}
		if (target.getUuid().equals(sender.getUuid()) && !EconomyConfig.payAllowSelf()) {
			src.sendError(Text.literal(Lang.msg("cmd.pay.self")));
			return 0;
		}
		if (amount < EconomyConfig.payMin()) {
			src.sendError(Text.literal(Lang.msg("cmd.pay.min", BalanceService.format(EconomyConfig.payMin()))));
			return 0;
		}
		if (amount > EconomyConfig.payMax()) {
			src.sendError(Text.literal(Lang.msg("cmd.pay.max", BalanceService.format(EconomyConfig.payMax()))));
			return 0;
		}

		int cd = EconomyConfig.payCooldownSeconds();
		if (cd > 0) {
			long last = LAST_PAY.getOrDefault(sender.getUuid(), 0L);
			long waitMs = last + cd * 1000L - System.currentTimeMillis();
			if (waitMs > 0) {
				src.sendError(Text.literal(Lang.msg("cmd.pay.cooldown", (waitMs / 1000) + 1)));
				return 0;
			}
		}

		long balance = BalanceService.get(sender.getUuid());
		if (balance < amount) {
			src.sendError(Text.literal(Lang.msg("cmd.pay.insufficient",
					BalanceService.format(balance), BalanceService.format(amount))));
			return 0;
		}

		long taxRaw = (long) Math.ceil(amount * (EconomyConfig.payTaxPercent() / 100.0));
		final long tax = Math.min(taxRaw, amount);
		long net = amount - tax;

		if (!BalanceService.take(sender.getUuid(), amount, com.akira.redtale.economy.storage.Ledger.TYPE_PAY,
				"to:" + target.getUuid() + "/tax:" + tax)) {
			src.sendError(Text.literal(Lang.msg("cmd.pay.insufficient",
					BalanceService.format(balance), BalanceService.format(amount))));
			return 0;
		}
		BalanceService.add(target.getUuid(), net, com.akira.redtale.economy.storage.Ledger.TYPE_PAY,
				"from:" + sender.getUuid());
		LAST_PAY.put(sender.getUuid(), System.currentTimeMillis());

		src.sendFeedback(() -> Text.literal(Lang.msg("cmd.pay.sent", name, BalanceService.format(amount), BalanceService.format(tax))), false);
		target.sendMessage(Text.literal(Lang.msg("cmd.pay.received", sender.getEntityName(), BalanceService.format(amount), BalanceService.format(net))), false);
		return 1;
	}

	private static UUID resolve(ServerCommandSource src, String name) {
		ServerPlayerEntity online = src.getServer().getPlayerManager().getPlayer(name);
		if (online != null) return online.getUuid();
		return src.getServer().getUserCache().findByName(name).map(GameProfile::getId).orElse(null);
	}
}
