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
				// R37：离线服改名（旧名 → 新名，逐条迁移数据；执行前出清单 + 票据确认）
				.then(CommandManager.literal("player")
						.requires(src -> Permissions.has(src, Permissions.NODE_OWNER))
						.then(CommandManager.literal("rename")
								.then(CommandManager.argument("old",
												com.mojang.brigadier.arguments.StringArgumentType.word())
										.then(CommandManager.argument("new",
														com.mojang.brigadier.arguments.StringArgumentType.word())
												.executes(RedtaleCommands::playerRename)))))
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
				// R42：手动解锁战令付费轨（/rt admin pass unlock <玩家> [赛季]）
				.then(CommandManager.literal("admin")
						.requires(src -> Permissions.has(src, Permissions.NODE_OWNER))
						.then(CommandManager.literal("pass")
								.then(CommandManager.literal("unlock")
										.then(CommandManager.argument("player",
														net.minecraft.command.argument.EntityArgumentType.player())
												.executes(ctx -> adminPassUnlock(ctx, null))
												.then(CommandManager.argument("season",
																com.mojang.brigadier.arguments.StringArgumentType.word())
														.executes(ctx -> adminPassUnlock(ctx,
																com.mojang.brigadier.arguments.StringArgumentType
																		.getString(ctx, "season")))))))
						// R36：数据根目录只读诊断（确认数据确实落在存档里，不用翻文件系统）
						.then(CommandManager.literal("data").executes(RedtaleCommands::adminData)))
				.then(CommandManager.literal("lang")
						.requires(src -> Permissions.has(src, Permissions.NODE_ADMIN))
						.then(CommandManager.argument("code", com.mojang.brigadier.arguments.StringArgumentType.word())
								.executes(RedtaleCommands::lang)));

		// U8b：/rt <page> 直达页面（字面量优先，reload/save 等子命令不受影响）
		PageCommands.attach(root);
		// 无参 /rt = 打开签到页（最常用入口）
		root.executes(ctx -> {
			var p = ctx.getSource().getPlayer();
			if (p == null) {
				return help(ctx);
			}
			return PageCommands.open(p, "checkin");
		});

		com.mojang.brigadier.tree.LiteralCommandNode<ServerCommandSource> node = dispatcher.register(root);
		// U8b：/rt 是 /redtale 的别名。
		// 不能用 redirect —— Brigadier 的 redirect 节点自己不执行，裸敲 /rt 会报
		// 「Unknown or incomplete command」（实测 2026-10-20 17:2x 服务端）。
		// 所以新建一个字面量节点：先绑 executes（默认签到页），再把 /redtale 的子节点整个挂上来。
		// Brigadier 的 CommandNode 没有 parent 反向引用，子节点可以同时挂在两棵树上。
		com.mojang.brigadier.tree.LiteralCommandNode<ServerCommandSource> rtNode =
				CommandManager.literal("rt")
						.executes(ctx -> {
							var p = ctx.getSource().getPlayer();
							if (p == null) {
								return help(ctx);
							}
							return PageCommands.open(p, "checkin");
						})
						.build();
		for (com.mojang.brigadier.tree.CommandNode<ServerCommandSource> child : node.getChildren()) {
			rtNode.addChild(child);
		}
		dispatcher.getRoot().addChild(rtNode);
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
	/**
	 * R42：{@code /rt admin pass unlock <玩家> [赛季]}。
	 *
	 * <p>两道门：① 服主权限（NODE_OWNER，默认 OP4）；② {@code pass/settings.json → adminUnlockWhitelist}
	 * 白名单（配置为空时只认权限，但会记一条 WARN 说明「未设白名单」）。执行不改余额，只写
	 * {@code battlepass.admin.unlock} 流水，便于事后对账。
	 */
	private static int adminPassUnlock(CommandContext<ServerCommandSource> ctx, String season) {
		ServerCommandSource src = ctx.getSource();
		try {
			net.minecraft.server.network.ServerPlayerEntity target =
					net.minecraft.command.argument.EntityArgumentType.getPlayer(ctx, "player");
			String opName = src.getName();
			var white = com.akira.redtale.economy.config.BattlePassConfig.adminUnlockWhitelist();
			if (!white.isEmpty()) {
				boolean listed = white.stream().anyMatch(n -> n.equalsIgnoreCase(opName));
				if (!listed) {
					src.sendError(Text.literal("§c你不在 pass/settings.json 的 adminUnlockWhitelist 里，拒绝执行"));
					return 0;
				}
			} else {
				RedtaleEconomy.LOGGER.warn("[pass] {} 执行 admin pass unlock 时白名单为空（只按权限放行）", opName);
			}
			String seasonId = season == null || season.isBlank()
					? com.akira.redtale.economy.module.impl.BattlePassModule.currentSeasonId() : season;
			boolean ok = com.akira.redtale.economy.module.impl.BattlePassModule
					.adminUnlock(target.getUuid(), opName, seasonId);
			if (!ok) {
				src.sendError(Text.literal("§c解锁失败：战令未启用，或该玩家已解锁本赛季付费轨"));
				return 0;
			}
			// 立刻刷新目标玩家的界面（如果开着）
			com.akira.redtale.economy.net.PassNetworking.send(target);
			src.sendFeedback(() -> Text.literal("§a已为 §f" + target.getGameProfile().getName()
					+ " §a解锁战令付费轨 §7(" + seasonId + "，未扣任何货币，已记流水)"), true);
			return 1;
		} catch (Exception e) {
			src.sendError(Text.literal("§c执行失败：" + e.getMessage()));
			return 0;
		}
	}

	/**
	 * R37：{@code /redtale player rename <旧名> <新名>}。
	 *
	 * <p>离线服 UUID 由名字推导，改名 = 换号 ⇒ 先把旧 UUID 名下的数据复制到新 UUID
	 * （{@link com.akira.redtale.economy.util.PlayerRename}），执行前打印清单并要求
	 * {@code /redtale confirm <票据>}。正版服直接拒绝（那边改名走 Mojang，UUID 不变）。
	 */
	private static int playerRename(CommandContext<ServerCommandSource> ctx) {
		ServerCommandSource src = ctx.getSource();
		try {
			if (src.getServer() != null && src.getServer().isOnlineMode()) {
				src.sendError(Text.literal("§c正版服改名请改 Mojang ID（UUID 不变，不需要迁移）；本命令只服务离线服"));
				return 0;
			}
			String oldName = com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "old").trim();
			String newName = com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "new").trim();
			var nameRe = java.util.regex.Pattern.compile("^[A-Za-z0-9_]{3,16}$");
			if (!nameRe.matcher(oldName).matches() || !nameRe.matcher(newName).matches()) {
				src.sendError(Text.literal("§c名字只允许 3-16 位字母/数字/下划线"));
				return 0;
			}
			if (oldName.equalsIgnoreCase(newName)) {
				src.sendError(Text.literal("§c新旧名字相同"));
				return 0;
			}
			var root = com.akira.redtale.economy.util.DataRoot.path();
			var plan = com.akira.redtale.economy.util.PlayerRename.plan(root, oldName, newName);
			if (plan.empty()) {
				src.sendError(Text.literal("§c旧号没有可迁移的数据（" + plan.oldUuid() + "）—— 确认名字拼写，"
						+ "或该玩家从未在本服消费过"));
				return 0;
			}
			if (plan.conflicts() > 0) {
				src.sendError(Text.literal("§c新号已有 " + plan.conflicts()
						+ " 个同名文件，拒绝自动合并（会覆盖新号数据），请人工处理"));
				return 0;
			}
			long bal = com.akira.redtale.economy.economy.BalanceService.get(plan.oldUuid());
			var st = com.akira.redtale.economy.util.PlayerRename.stats(root, plan.oldUuid());
			src.sendFeedback(() -> Text.literal("§6[rename] §f" + oldName + " §7→ §f" + newName), false);
			src.sendFeedback(() -> Text.literal("§7UUID §f" + plan.oldUuid() + " §7→ §f" + plan.newUuid()), false);
			src.sendFeedback(() -> Text.literal("§7余额 §f" + com.akira.redtale.economy.economy.Currency.format(bal)
					+ " §7· 仓库 §f" + st.warehouseSlots() + "§7格(已购 " + st.warehousePurchased() + ")"
					+ " §7· 签到 §f" + st.checkinStreak() + "§7天(共 " + st.checkinTotal() + ")"), false);
			src.sendFeedback(() -> Text.literal("§7战令 §f" + st.passXp() + "xp"
					+ (st.passPremium() ? " §6付费轨" : " §7免费轨")
					+ " §7· 协会声望 §f" + st.guildRep()
					+ " §7· 数据文件 §f" + st.dataFiles() + " 个"), false);
			src.sendFeedback(() -> Text.literal("§7旧数据保留不删（可回退），新号同名文件不动"), false);

			var t = ConfirmTickets.issue(src.getPlayer() == null ? new java.util.UUID(0, 0) : src.getPlayer().getUuid(),
					"player.rename", oldName + "|" + newName);
			src.sendFeedback(() -> Text.literal("§e确认执行：§f/redtale confirm " + t.code()
					+ " §7（" + ConfirmTickets.remainingSeconds(t) + " 秒内有效）"), false);
			return 1;
		} catch (Exception e) {
			src.sendError(Text.literal("§c执行失败：" + e.getMessage()));
			return 0;
		}
	}

	/**
	 * R36：{@code /rt admin data} —— 只读诊断，用来在游戏里确认「数据真的落在存档目录」，
	 * 不用去翻文件系统。不改任何数据、不写盘。
	 */
	private static int adminData(CommandContext<ServerCommandSource> ctx) {
		ServerCommandSource src = ctx.getSource();
		try {
			var root = com.akira.redtale.economy.util.DataRoot.resolve();
			var world = com.akira.redtale.economy.util.PathResolver.worldRoot();
			boolean scoped = com.akira.redtale.economy.util.DataRoot.worldScoped();
			int pending = countFiles(com.akira.redtale.economy.util.DataRoot.pendingRoot());
			int legacy = countFiles(com.akira.redtale.economy.util.DataRoot.legacyRoot());
			int accounts = countFiles(root.path().resolve("accounts"));
			String mark = (com.akira.redtale.economy.util.DataRoot.alreadyMigrated(root.path(), "legacy")
					? "§alegacy ✔" : "§clegacy ✘")
					+ " §7/" + (com.akira.redtale.economy.util.DataRoot
					.alreadyMigrated(root.path(), "pending") ? "§apending ✔" : "§cpending ✘");
			src.sendFeedback(() -> Text.literal("§6[data] §f" + root.path()), false);
			src.sendFeedback(() -> Text.literal("§7模式 §f"
					+ (scoped ? "%world%（跟存档）" : "显式配置（不迁移）")
					+ " §7存档 §f" + (world == null ? "未就绪" : world.toString())), false);
			src.sendFeedback(() -> Text.literal("§7旧目录 §f" + legacy + " 文件 §7| pending §f" + pending
					+ " 文件 §7| accounts §f" + accounts + " 文件"), false);
			src.sendFeedback(() -> Text.literal("§7迁移标记 §f" + mark), false);
			return 1;
		} catch (Exception e) {
			src.sendError(Text.literal("§c读取失败：" + e.getMessage()));
			return 0;
		}
	}

	private static int countFiles(java.nio.file.Path dir) {
		if (dir == null || !java.nio.file.Files.isDirectory(dir)) return 0;
		try (var s = java.nio.file.Files.walk(dir)) {
			return (int) s.filter(java.nio.file.Files::isRegularFile).count();
		} catch (Exception e) {
			return 0;
		}
	}

	/** {@code /redtale confirm <code>}：核销票据并执行对应动作（R25）。 */	private static int confirmTicket(CommandContext<ServerCommandSource> ctx) {
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
			case "player.rename" -> {
				if (parts.length < 2) yield false;
				var root = com.akira.redtale.economy.util.DataRoot.path();
				var plan = com.akira.redtale.economy.util.PlayerRename.plan(root, parts[0], parts[1]);
				var res = com.akira.redtale.economy.util.PlayerRename.execute(root, plan);
				// 迁移完让各模块重新读新 UUID 名下的文件（旧号数据保留）
				com.akira.redtale.economy.util.DataRoot.rebind();
				src.sendFeedback(() -> Text.literal("§a迁移完成：复制 §f" + res.copied() + " §a个文件"
						+ (res.failed() > 0 ? "，§c失败 " + res.failed() : "")
						+ "，别名表 " + (res.aliasWritten() ? "§a已写" : "§c未写")), false);
				RedtaleEconomy.LOGGER.info("[rename] {} → {}：复制 {}，失败 {}，旧数据保留",
						parts[0], parts[1], res.copied(), res.failed());
				yield res.copied() > 0 && res.failed() == 0;
			}
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
