package com.akira.redtale.economy.command;

import com.akira.redtale.economy.perm.Permissions;
import com.akira.redtale.economy.RedtaleEconomy;
import com.akira.redtale.economy.config.ConfigEditor;
import com.akira.redtale.economy.config.ConfigManager;
import com.akira.redtale.economy.config.CoreConfig;
import com.akira.redtale.economy.lang.Lang;
import com.akira.redtale.economy.module.ModuleManager;
import com.akira.redtale.economy.module.RedtaleModule;
import com.akira.redtale.economy.net.ConfigNetworking;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;

import java.util.List;

/** {@code /redtale} 根命令：reload / save / modules / info。 */
public final class RedtaleCommands {

	private RedtaleCommands() {
	}

	public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
		LiteralArgumentBuilder<ServerCommandSource> root = CommandManager.literal("redtale")
				.then(CommandManager.literal("reload")
						.requires(src -> Permissions.has(src, Permissions.NODE_OWNER))
						.executes(RedtaleCommands::reload))
				.then(CommandManager.literal("save")
						.requires(src -> Permissions.has(src, Permissions.NODE_ADMIN))
						.executes(ctx -> {
							ConfigManager.get().saveAll(true);
							ctx.getSource().sendFeedback(() -> Text.literal("§a配置已保存（含备份）"), true);
							return 1;
						}))
				.then(CommandManager.literal("modules")
						.requires(src -> Permissions.has(src, Permissions.NODE_ADMIN))
						.executes(RedtaleCommands::modules))
				.then(CommandManager.literal("info")
						.executes(RedtaleCommands::info))
				.then(CommandManager.literal("confirm")
						.requires(src -> Permissions.has(src, Permissions.NODE_ADMIN))
						.then(CommandManager.argument("code",
										com.mojang.brigadier.arguments.StringArgumentType.word())
								.executes(RedtaleCommands::confirmTicket)))
				.then(CommandManager.literal("cancel")
						.requires(src -> Permissions.has(src, Permissions.NODE_ADMIN))
						.then(CommandManager.argument("code",
										com.mojang.brigadier.arguments.StringArgumentType.word())
								.executes(RedtaleCommands::cancelTicket)))
				.then(CommandManager.literal("help")
						.executes(RedtaleCommands::help))
				// B24 补发奖励：统一走邮件（物品进 Contact 信箱/队列，货币进待领取队列）
				.then(CommandManager.literal("grant")
						.requires(src -> Permissions.has(src, Permissions.NODE_ADMIN))
						.then(CommandManager.literal("money")
								.then(CommandManager.argument("player",
												net.minecraft.command.argument.EntityArgumentType.player())
										.then(CommandManager.argument("amount",
														com.mojang.brigadier.arguments.LongArgumentType.longArg(1, 9_000_000_000_000L))
												.executes(RedtaleCommands::grantMoney))))
						.then(CommandManager.literal("item")
								.then(CommandManager.argument("player",
												net.minecraft.command.argument.EntityArgumentType.player())
										.then(CommandManager.argument("item", ItemIdArgument.itemId())
												.then(CommandManager.argument("count",
																com.mojang.brigadier.arguments.IntegerArgumentType.integer(1, 64))
														.executes(RedtaleCommands::grantItem))))))
				.then(MigrateCommand.build(CoreConfig.perm("adminPermissionLevel", 4)))
				.then(CommandManager.literal("edit")
						.requires(src -> Permissions.has(src, Permissions.NODE_OWNER))
						.executes(RedtaleCommands::editList)
						.then(CommandManager.argument("file",
										com.mojang.brigadier.arguments.StringArgumentType.word())
								.suggests((ctx, b) -> net.minecraft.command.CommandSource.suggestMatching(
										ConfigManager.get().definedFiles(), b))
								.executes(RedtaleCommands::edit)))
				.then(CommandManager.literal("lang")
						.requires(src -> Permissions.has(src, Permissions.NODE_ADMIN))
						.then(CommandManager.argument("code", com.mojang.brigadier.arguments.StringArgumentType.word())
								.executes(RedtaleCommands::lang)));

		com.mojang.brigadier.tree.LiteralCommandNode<ServerCommandSource> node = dispatcher.register(root);
		dispatcher.register(CommandManager.literal("rt").redirect(node));

		// FTB Quests 快捷入口（R9）：未装 FTBQ 时不注册，玩家看不到 /quests
		if (QuestsCommand.available()) {
			dispatcher.register(QuestsCommand.build(CoreConfig.perm("usePermissionLevel", 0)));
		}
	}

	private static int reload(CommandContext<ServerCommandSource> ctx) {
		ServerCommandSource src = ctx.getSource();
		ConfigManager cfg = ConfigManager.get();
		try {
			cfg.reloadAll();
			Lang.reload();
			Permissions.init();   // 权限 provider / 节点名可能被改
			ModuleManager.get().reload(cfg);
		} catch (Exception e) {
			RedtaleEconomy.LOGGER.error("[cmd] reload 失败", e);
			src.sendError(Text.literal("§c重载失败：" + e.getMessage()));
			return 0;
		}
		List<String> errors = cfg.lastLoadErrors();
		if (errors.isEmpty()) {
			int missing = Lang.missingCount();
			src.sendFeedback(() -> Text.literal("§a配置已重载（" + cfg.definedFiles().size() + " 个文件"
					+ (missing > 0 ? "，§e" + missing + " 个语言 key 缺失§a" : "") + "）"), true);
			return 1;
		}
		src.sendFeedback(() -> Text.literal("§e重载完成，但有 " + errors.size() + " 个文件出错："), false);
		for (String e : errors) {
			src.sendFeedback(() -> Text.literal("§c  · " + e), false);
		}
		return errors.size();
	}

	/** 不带参数：列出可编辑的配置文件。 */
	private static int editList(CommandContext<ServerCommandSource> ctx) {
		List<String> files = ConfigManager.get().definedFiles();
		ctx.getSource().sendFeedback(() -> Text.literal(
				"§6可编辑配置（" + files.size() + "）："), false);
		for (String f : files) {
			ctx.getSource().sendFeedback(() -> Text.literal("§8- §f" + f), false);
		}
		ctx.getSource().sendFeedback(() -> Text.literal("§7用法：§f/redtale edit <file>"), false);
		return 1;
	}

	/** 打开表单式编辑器（V1 只做标量字段）。 */
	private static int edit(CommandContext<ServerCommandSource> ctx) {
		String file = com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "file");
		if (!ConfigManager.get().isDefined(file)) {
			ctx.getSource().sendError(Text.literal("§c未登记的配置文件：" + file));
			return 0;
		}
		var player = ctx.getSource().getPlayer();
		if (player == null) {
			ctx.getSource().sendError(Text.literal(Lang.msg("cmd.onlyPlayer")));
			return 0;
		}
		var entries = ConfigEditor.snapshot(file);
		if (entries.isEmpty()) {
			ctx.getSource().sendError(Text.literal("§e" + file + " 没有可编辑的标量字段"));
			return 0;
		}
		ConfigNetworking.sendOpen(player, file, entries);
		return 1;
	}

	private static int modules(CommandContext<ServerCommandSource> ctx) {
		ServerCommandSource src = ctx.getSource();
		ModuleManager mm = ModuleManager.get();
		src.sendFeedback(() -> Text.literal("§6模块状态："), false);
		for (RedtaleModule m : mm.all()) {
			boolean on = mm.isEnabled(m.id());
			String line = on
					? "§a  ✔ " + m.id()
					: "§7  ✘ " + m.id() + " §8(" + mm.reason(m.id()) + ")";
			src.sendFeedback(() -> Text.literal(line), false);
		}
		src.sendFeedback(() -> Text.literal("§8改 config/redtale/modules.json 后 /redtale reload 生效"), false);
		return 1;
	}

	private static int lang(CommandContext<ServerCommandSource> ctx) {
		ServerCommandSource src = ctx.getSource();
		String code = com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "code").toLowerCase();
		if (!code.matches("[a-z0-9_]{2,20}")) {
			src.sendError(Text.literal("§c语言代码不合法（示例：zh_cn / en_us）"));
			return 0;
		}
		com.akira.redtale.economy.config.ConfigStore core = CoreConfig.store();
		core.setPath("language", code);
		core.save(true);
		Lang.reload();
		int missing = Lang.missingCount();
		var msg = Text.literal(Lang.msg("lang.switched", Lang.code(),
				missing > 0 ? "§e" + missing : "§a0"));
		for (net.minecraft.server.network.ServerPlayerEntity p : src.getServer().getPlayerManager().getPlayerList()) {
			p.sendMessage(msg, false);
		}
		src.sendFeedback(() -> Text.literal("§a语言已切换为 " + Lang.code()), true);
		return 1;
	}

	/** B12：一条命令看懂全部入口（文案走 Lang，运营可改）。 */
	/** {@code /redtale confirm <code>}：核销票据并执行对应动作（R25）。 */
	private static int confirmTicket(CommandContext<ServerCommandSource> ctx) {
		ServerCommandSource src = ctx.getSource();
		var player = src.getPlayer();
		if (player == null) {
			src.sendError(Text.literal(Lang.t("cmd.onlyPlayer")));
			return 0;
		}
		String code = com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "code");
		ConfirmTickets.Ticket t = ConfirmTickets.take(player.getUuid(), code);
		if (t == null) {
			src.sendError(Text.literal("§c票据无效、已过期或已被使用"));
			return 0;
		}
		String[] parts = t.payload().split("\\|", 2);
		boolean ok = switch (t.action()) {
			case "shop.remove" -> parts.length == 2
					&& com.akira.redtale.economy.shop.ShopCatalog.removeItem(parts[0], parts[1]);
			case "shop.removeshop" -> parts.length >= 1
					&& com.akira.redtale.economy.shop.ShopCatalog.removeShop(parts[0]);
			default -> false;
		};
		if (!ok) {
			src.sendError(Text.literal("§c执行失败（动作 " + t.action() + "），请检查参数或日志"));
			return 0;
		}
		src.sendFeedback(() -> Text.literal("§a已执行：§f" + t.action() + " §7" + t.payload()), true);
		return 1;
	}

	/** {@code /redtale cancel <code>}：作废票据。 */
	private static int cancelTicket(CommandContext<ServerCommandSource> ctx) {
		ServerCommandSource src = ctx.getSource();
		var player = src.getPlayer();
		if (player == null) {
			src.sendError(Text.literal(Lang.t("cmd.onlyPlayer")));
			return 0;
		}
		String code = com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "code");
		boolean ok = ConfirmTickets.cancel(player.getUuid(), code);
		src.sendFeedback(() -> Text.literal(ok ? "§a票据已作废" : "§c没有这张票据"), false);
		return ok ? 1 : 0;
	}

	/** B24：补发货币（进待领取队列，玩家 /mail claim 领取）。 */
	private static int grantMoney(CommandContext<ServerCommandSource> ctx) {
		ServerCommandSource src = ctx.getSource();
		try {
			net.minecraft.server.network.ServerPlayerEntity target = net.minecraft.command.argument.EntityArgumentType.getPlayer(ctx, "player");
			long amount = com.mojang.brigadier.arguments.LongArgumentType.getLong(ctx, "amount");
			if (!com.akira.redtale.economy.mail.MailService.sendMoney(target.getUuid(), amount, "admin:grant")) {
				src.sendError(Text.literal("§c补发失败：金额超出 mail.json 的 maxQueueMoney"));
				return 0;
			}
			src.sendFeedback(() -> Text.literal("§a已给 " + target.getName().getString()
					+ " 补发 " + com.akira.redtale.economy.economy.BalanceService.format(amount)
					+ "（/mail claim 领取）"), true);
			return 1;
		} catch (Exception e) {
			src.sendError(Text.literal("§c补发失败：" + e.getMessage()));
			return 0;
		}
	}

	/** B24：补发物品（装 Contact 进信箱，否则进待领取队列）。 */
	private static int grantItem(CommandContext<ServerCommandSource> ctx) {
		ServerCommandSource src = ctx.getSource();
		try {
			net.minecraft.server.network.ServerPlayerEntity target = net.minecraft.command.argument.EntityArgumentType.getPlayer(ctx, "player");
			String itemId = com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "item");
			int count = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "count");
			net.minecraft.item.Item found;
			try {
				var id = net.minecraft.util.Identifier.tryParse(itemId);
				found = id == null ? null : net.minecraft.registry.Registries.ITEM.get(id);
			} catch (Exception e) {
				found = null;
			}
			final net.minecraft.item.Item item = found;
			if (item == null) {
				src.sendError(Text.literal("§c找不到物品：" + itemId));
				return 0;
			}
			boolean box = com.akira.redtale.economy.mail.MailService.sendItem(RedtaleEconomy.server(),
					target.getUuid(), new net.minecraft.item.ItemStack(item, count), "admin:grant");
			src.sendFeedback(() -> Text.literal("§a已给 " + target.getName().getString() + " 补发 "
					+ count + " 个 " + item.getName().getString()
					+ (box ? "（已进 Contact 信箱）" : "（已进待领取队列）")), true);
			return 1;
		} catch (Exception e) {
			src.sendError(Text.literal("§c补发失败：" + e.getMessage()));
			return 0;
		}
	}

	private static int help(CommandContext<ServerCommandSource> ctx) {
		ServerCommandSource src = ctx.getSource();
		src.sendFeedback(() -> Text.literal("§6" + Lang.t("help.title", "Redtale Economy 命令")), false);
		for (String line : Lang.t("help.body", "").split("\n")) {
			if (!line.isBlank()) {
				String l = line;
				src.sendFeedback(() -> Text.literal("§7" + l), false);
			}
		}
		return 1;
	}

	private static int info(CommandContext<ServerCommandSource> ctx) {
		ServerCommandSource src = ctx.getSource();
		ConfigManager cfg = ConfigManager.get();
		src.sendFeedback(() -> Text.literal("§6Redtale Economy §7v" + RedtaleEconomy.version()), false);
		src.sendFeedback(() -> Text.literal("§7配置目录：§f" + cfg.baseDir()), false);
		src.sendFeedback(() -> Text.literal("§7语言：§f" + CoreConfig.language()
				+ " §7时区：§f" + CoreConfig.timezone()
				+ " §7provider：§f" + com.akira.redtale.economy.config.EconomyConfig.provider()), false);
		src.sendFeedback(() -> Text.literal("§7后端：§f" + com.akira.redtale.economy.config.StorageConfig.backend()
				+ " §7数据根：§f" + com.akira.redtale.economy.config.StorageConfig.root()), false);
		return 1;
	}
}
