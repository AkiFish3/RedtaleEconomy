package com.akira.redtale.economy.command;

import com.akira.redtale.economy.perm.Permissions;
import com.akira.redtale.economy.RedtaleEconomy;
import com.akira.redtale.economy.config.CoreConfig;
import com.akira.redtale.economy.config.PremiumConfig;
import com.akira.redtale.economy.lang.Lang;
import com.akira.redtale.economy.module.impl.PremiumModule;
import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.util.UUID;

/**
 * {@code /premium} 系列（默认不注册，需 premium.enabled=true）。
 *
 * <p>发放 = OP 权限 + UUID 白名单「与」关系；两条缺一即拒。
 */
public final class PremiumCommands {

	private PremiumCommands() {
	}

	public static void register(CommandDispatcher<ServerCommandSource> d) {
		d.register(CommandManager.literal("premium")
				.executes(PremiumCommands::self)
				.then(CommandManager.literal("give")
						.requires(src -> Permissions.has(src, Permissions.NODE_PREMIUM_GIVE))
						.then(CommandManager.argument("player", StringArgumentType.word()).suggests(Suggest.players())
								.then(CommandManager.argument("amount", IntegerArgumentType.integer(1, 1_000_000))
										.executes(ctx -> give(ctx, ""))
										.then(CommandManager.argument("reason", StringArgumentType.greedyString())
												.executes(ctx -> give(ctx,
														StringArgumentType.getString(ctx, "reason"))))))));
	}

	private static int self(CommandContext<ServerCommandSource> ctx) {
		ServerCommandSource src = ctx.getSource();
		ServerPlayerEntity p = src.getPlayer();
		if (p == null) {
			src.sendError(Text.literal(Lang.t("cmd.onlyPlayer")));
			return 0;
		}
		if (!PremiumConfig.store().getBool("showBalanceToSelf", true)) {
			src.sendError(Text.literal(Lang.msg("premium.hidden")));
			return 0;
		}
		long v = PremiumModule.get(p.getUuid());
		src.sendFeedback(() -> Text.literal(Lang.msg("premium.balance", PremiumModule.displayName(), v)), false);
		return 1;
	}

	private static int give(CommandContext<ServerCommandSource> ctx, String reason) {
		ServerCommandSource src = ctx.getSource();
		String name = StringArgumentType.getString(ctx, "player");
		int amount = IntegerArgumentType.getInteger(ctx, "amount");

		UUID id = resolve(src, name);
		if (id == null) {
			src.sendError(Text.literal(Lang.msg("cmd.bal.unknown", name)));
			return 0;
		}
		if (!PremiumModule.whitelisted(id)) {
			src.sendError(Text.literal(Lang.msg("premium.notWhitelisted", name)));
			RedtaleEconomy.LOGGER.warn("[premium] 拒绝发放：{} 不在白名单（执行者 {}）", id, src.getName());
			return 0;
		}

		PremiumModule.add(id, amount);
		RedtaleEconomy.LOGGER.info("[premium] {} 给 {}（{}）发放 {}，原因：{}",
				src.getName(), name, id, amount, reason.isEmpty() ? "-" : reason);
		src.sendFeedback(() -> Text.literal(Lang.msg("premium.given", name, amount, PremiumModule.displayName())), true);

		ServerPlayerEntity target = src.getServer().getPlayerManager().getPlayer(id);
		if (target != null) {
			target.sendMessage(Text.literal(Lang.msg("premium.received", amount, PremiumModule.displayName())), false);
		}
		return 1;
	}

	private static UUID resolve(ServerCommandSource src, String name) {
		ServerPlayerEntity online = src.getServer().getPlayerManager().getPlayer(name);
		if (online != null) return online.getUuid();
		return src.getServer().getUserCache().findByName(name).map(GameProfile::getId).orElse(null);
	}
}
