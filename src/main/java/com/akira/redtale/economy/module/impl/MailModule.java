package com.akira.redtale.economy.module.impl;

import com.akira.redtale.economy.module.ModuleEvents;
import com.akira.redtale.economy.RedtaleEconomy;
import com.akira.redtale.economy.config.ConfigManager;
import com.akira.redtale.economy.config.MailConfig;
import com.akira.redtale.economy.economy.BalanceService;
import com.akira.redtale.economy.integration.ContactMail;
import com.akira.redtale.economy.lang.Lang;
import com.akira.redtale.economy.mail.MailQueue;
import com.akira.redtale.economy.mail.MailService;
import com.akira.redtale.economy.module.RedtaleModule;
import com.akira.redtale.economy.perm.Permissions;
import com.akira.redtale.economy.util.DataRoot;
import com.akira.redtale.economy.util.PathResolver;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 邮件模块（R21）：物品走 Contact 信箱，货币走待领取队列。
 *
 * <p>{@code /mail} 看自己的待领取；{@code /mail claim} 领货币；
 * 管理用 {@code /mail give}、{@code /mail money} 发东西。
 */
public final class MailModule implements RedtaleModule {

	private static volatile boolean hooksInstalled;
	private static int tick;

	@Override
	public String id() {
		return "mail";
	}

	@Override
	public void defineConfig(ConfigManager cfg) {
		cfg.define(MailConfig.FILE, MailConfig.SCHEMA);
	}

	@Override
	public void onLoad(ConfigManager cfg) {
		resolveDir();
		installHooks();
		// T62：Hub 邮件页的服务端收包（重复 onLoad/热重载无害，register 内部幂等）
		com.akira.redtale.economy.net.MailNetworking.register();
		if (!ContactMail.available()) {
			RedtaleEconomy.LOGGER.info("[mail] 未检测到 Contact，物品奖励改为进待领取队列");
		}
	}

	@Override
	public void onReload(ConfigManager cfg) {
		resolveDir();
		MailQueue.clearCache();
	}

	@Override
	public void registerCommands(CommandDispatcher<ServerCommandSource> dispatcher) {
		registerCommandsInternal(dispatcher);
	}

	/** R36：开服后数据目录重绑（{@code DataRoot.onWorldReady} 调用，%world% 这时才可用）。 */
	@Override
	public void rebindDataDir() {
		resolveDir();
	}

	private static void resolveDir() {
		try {
			PathResolver.Resolved r = DataRoot.resolve();
			Path d = r.path().resolve("mail");
			Files.createDirectories(d);
			MailQueue.setDir(d);
		} catch (Exception e) {
			MailQueue.setDir(null);
			RedtaleEconomy.LOGGER.error("[mail] 目录不可用：{}", e.getMessage());
		}
	}

	private static void installHooks() {
		if (hooksInstalled) return;
		hooksInstalled = true;
		ModuleEvents.onQuit("mail", p -> {
			try {
				MailQueue.evict(p.getUuid());
			} catch (Exception ignored) {
				// 退出落盘失败不影响存档
			}
		});
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (!enabled()) return;
			int every = Math.max(1, MailConfig.retryIntervalSeconds() * 20);
			if (++tick < every) return;
			tick = 0;
			for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
				try {
					MailService.retryItems(server, p.getUuid());
				} catch (Exception ignored) {
					// 单个玩家重投失败不影响其余
				}
			}
		});
	}

	public static boolean enabled() {
		return com.akira.redtale.economy.module.ModuleManager.get().isEnabled("mail")
				&& MailConfig.enabled();
	}

	// ---------------------------------------------------------------- 命令

	private static void registerCommandsInternal(CommandDispatcher<ServerCommandSource> d) {
		for (String name : MailConfig.openCommands()) {
			if (name == null || name.isBlank()) continue;
			d.register(CommandManager.literal(name)
					.requires(src -> Permissions.has(src, Permissions.NODE_USE))
					.executes(MailModule::info)
					.then(CommandManager.literal("claim").executes(MailModule::claim))
					.then(CommandManager.literal("give")
							.requires(src -> Permissions.has(src, Permissions.NODE_ADMIN))
							.then(CommandManager.argument("player", EntityArgumentType.player())
									.then(CommandManager.argument("item", StringArgumentType.string())
											.then(CommandManager.argument("count", IntegerArgumentType.integer(1, 64))
													.executes(MailModule::give)))))
					.then(CommandManager.literal("money")
							.requires(src -> Permissions.has(src, Permissions.NODE_ADMIN))
							.then(CommandManager.argument("player", EntityArgumentType.player())
									.then(CommandManager.argument("amount", LongArgumentType.longArg(1, 9_000_000_000_000L))
											.executes(MailModule::money)))));
		}
	}

	private static int info(CommandContext<ServerCommandSource> ctx) {
		ServerCommandSource src = ctx.getSource();
		ServerPlayerEntity p = src.getPlayer();
		if (p == null) return onlyPlayer(src);
		if (!enabled()) {
			src.sendError(Text.literal(Lang.msg("mail.disabled")));
			return 0;
		}
		MailQueue q = MailQueue.of(p.getUuid());
		p.sendMessage(Text.literal(Lang.msg("mail.info",
				BalanceService.format(q.pendingMoney), q.items.size())), false);
		p.sendMessage(Text.literal(Lang.msg("mail.provider",
				ContactMail.available() ? Lang.t("mail.provider.contact") : Lang.t("mail.provider.queue"))), false);
		if (q.pendingMoney > 0) {
			// 提示不刷聊天：界面上已有按钮
		}
		return 1;
	}

	private static int claim(CommandContext<ServerCommandSource> ctx) {
		ServerCommandSource src = ctx.getSource();
		ServerPlayerEntity p = src.getPlayer();
		if (p == null) return onlyPlayer(src);
		long amount = MailService.claimMoney(p.getUuid());
		if (amount <= 0) {
			src.sendError(Text.literal(Lang.msg("mail.nothing")));
			return 0;
		}
		p.sendMessage(Text.literal(Lang.msg("mail.claimed", BalanceService.format(amount))), true);
		return 1;
	}

	private static int give(CommandContext<ServerCommandSource> ctx) {
		ServerCommandSource src = ctx.getSource();
		try {
			ServerPlayerEntity target = EntityArgumentType.getPlayer(ctx, "player");
			String itemId = StringArgumentType.getString(ctx, "item");
			int count = IntegerArgumentType.getInteger(ctx, "count");
			Item item = item(itemId);
			if (item == null) {
				src.sendError(Text.literal(Lang.msg("mail.badItem", itemId)));
				return 0;
			}
			boolean box = MailService.sendItem(RedtaleEconomy.server(), target.getUuid(),
					new ItemStack(item, count), "admin:give");
			src.sendFeedback(() -> Text.literal(Lang.msg(box ? "mail.sentBox" : "mail.sentQueue",
					target.getName().getString(), count, item.getName().getString())), false);
			return 1;
		} catch (Exception e) {
			src.sendError(Text.literal(String.valueOf(e.getMessage())));
			return 0;
		}
	}

	private static int money(CommandContext<ServerCommandSource> ctx) {
		ServerCommandSource src = ctx.getSource();
		try {
			ServerPlayerEntity target = EntityArgumentType.getPlayer(ctx, "player");
			long amount = LongArgumentType.getLong(ctx, "amount");
			if (!MailService.sendMoney(target.getUuid(), amount, "admin:money")) {
				src.sendError(Text.literal(Lang.msg("mail.queueRejected")));
				return 0;
			}
			src.sendFeedback(() -> Text.literal(Lang.msg("mail.moneyQueued",
					target.getName().getString(), BalanceService.format(amount))), false);
			return 1;
		} catch (Exception e) {
			src.sendError(Text.literal(String.valueOf(e.getMessage())));
			return 0;
		}
	}

	private static Item item(String id) {
		try {
			Identifier ident = Identifier.tryParse(id);
			if (ident == null || !Registries.ITEM.containsId(ident)) return null;
			return Registries.ITEM.get(ident);
		} catch (Exception e) {
			return null;
		}
	}

	private static int onlyPlayer(ServerCommandSource src) {
		src.sendError(Text.literal(Lang.t("cmd.onlyPlayer")));
		return 0;
	}
}
