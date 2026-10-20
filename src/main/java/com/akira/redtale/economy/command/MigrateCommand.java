package com.akira.redtale.economy.command;

import com.akira.redtale.economy.perm.Permissions;
import com.akira.redtale.economy.RedtaleEconomy;
import com.akira.redtale.economy.config.ConfigManager;
import com.akira.redtale.economy.config.EconomyConfig;
import com.akira.redtale.economy.economy.BalanceProvider;
import com.akira.redtale.economy.economy.BalanceService;
import com.akira.redtale.economy.economy.InternalProvider;
import com.akira.redtale.economy.economy.NumismaticBridge;
import com.akira.redtale.economy.config.StorageConfig;
import com.akira.redtale.economy.storage.MysqlBackend;
import com.akira.redtale.economy.util.DataRoot;
import com.akira.redtale.economy.util.PathResolver;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * {@code /redtale migrate provider <internal|numismatic> [confirm]}（spec v0.5 §12.3）。
 *
 * <p>只迁移<b>当前在线</b>玩家的余额 —— 离线玩家在 Numismatic 侧没有可读组件，
 * 静默跳过会让人误以为迁移完整，所以这里明确列出跳过人数并要求二次确认。
 */
public final class MigrateCommand {

	private MigrateCommand() {
	}

	public static LiteralArgumentBuilder<ServerCommandSource> build(int perm) {
		return CommandManager.literal("migrate")
				.requires(src -> Permissions.has(src, Permissions.NODE_OWNER))
				.then(CommandManager.literal("provider")
						.then(CommandManager.argument("to", StringArgumentType.word())
								.suggests((c, b) -> {
									b.suggest("internal");
									b.suggest("numismatic");
									return b.buildFuture();
								})
								.executes(ctx -> run(ctx, false))
								.then(CommandManager.literal("confirm")
										.executes(ctx -> run(ctx, true)))))
				.then(CommandManager.literal("storage")
						.then(CommandManager.argument("to", StringArgumentType.word())
								.suggests((c, b) -> {
									b.suggest("json");
									b.suggest("mysql");
									return b.buildFuture();
								})
								.executes(ctx -> storage(ctx, false))
								.then(CommandManager.literal("confirm")
										.executes(ctx -> storage(ctx, true)))))
				.then(CommandManager.literal("sdm")
						.then(CommandManager.literal("scan").executes(ctx -> sdm(ctx, "scan")))
						.then(CommandManager.literal("apply")
								.executes(ctx -> sdm(ctx, "apply"))
								.then(CommandManager.literal("confirm").executes(ctx -> sdm(ctx, "apply-confirm"))))
						.then(CommandManager.literal("rollback")
								.executes(ctx -> sdm(ctx, "rollback"))
								.then(CommandManager.literal("confirm").executes(ctx -> sdm(ctx, "rollback-confirm"))))
						.then(CommandManager.literal("status").executes(ctx -> sdm(ctx, "status"))))
				.then(CommandManager.literal("identity")
						.then(CommandManager.argument("from", StringArgumentType.word())
								.then(CommandManager.argument("to", StringArgumentType.word())
										.executes(ctx -> identity(ctx, false))
										.then(CommandManager.literal("confirm")
												.executes(ctx -> identity(ctx, true))))));
	}

	/**
	 * {@code /redtale migrate identity <from> <to> [confirm]}（B22）。
	 *
	 * <p>离线↔正版切换导致 UUID 变更时：预检报两边账号是否存在、余额多少；
	 * 确认后备份两个账号文件 → 若目标不存在则复制 → 写 {@code accounts/_identities.json} 别名。
	 * 读账号时沿别名回溯，因此<b>无需重启</b>即生效。
	 */
	private static int identity(CommandContext<ServerCommandSource> ctx, boolean confirmed) {
		ServerCommandSource src = ctx.getSource();
		String from = StringArgumentType.getString(ctx, "from").trim();
		String to = StringArgumentType.getString(ctx, "to").trim();
		Path dir = accountDir();
		if (dir == null) {
			src.sendError(Text.literal("账号目录不可用（检查 storage.json 的路径配置）"));
			return 0;
		}
		if (from.equalsIgnoreCase(to)) {
			src.sendError(Text.literal("from 与 to 相同，无需迁移"));
			return 0;
		}
		Path fromFile = dir.resolve(from + ".json");
		Path toFile = dir.resolve(to + ".json");
		boolean fromHas = Files.isRegularFile(fromFile);
		boolean toHas = Files.isRegularFile(toFile);
		if (!fromHas && !toHas) {
			src.sendError(Text.literal("两个 UUID 都没有账号文件：" + from + " / " + to));
			return 0;
		}

		if (!confirmed) {
			src.sendFeedback(() -> Text.literal("§e身份迁移预检：" + from + " → " + to), false);
			src.sendFeedback(() -> Text.literal("§7源账号：" + (fromHas ? "存在，" + peek(fromFile) : "§c不存在")), false);
			src.sendFeedback(() -> Text.literal("§7目标账号：" + (toHas ? "存在，" + peek(toFile) + " §e（将覆盖为源数据）" : "不存在（将复制源文件）")), false);
			src.sendFeedback(() -> Text.literal("§7确认请执行：§f/redtale migrate identity " + from + " " + to + " confirm"), false);
			return 1;
		}

		try {
			Path backup = backupDir();
			if (backup != null) {
				Files.createDirectories(backup);
				if (fromHas) {
					Files.copy(fromFile, backup.resolve("account-" + from + ".json"),
							java.nio.file.StandardCopyOption.REPLACE_EXISTING);
				}
				if (toHas) {
					Files.copy(toFile, backup.resolve("account-" + to + ".json"),
							java.nio.file.StandardCopyOption.REPLACE_EXISTING);
				}
			}
			if (fromHas) {
				Files.createDirectories(dir);
				Files.copy(fromFile, toFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
			}
			boolean ok = com.akira.redtale.economy.storage.IdentityMap.put(dir, from, to, null);
			if (!ok) {
				src.sendError(Text.literal("§c别名表写入失败，账号文件已复制但未建立映射"));
				return 0;
			}
			final Path bak = backup;
			src.sendFeedback(() -> Text.literal("§a身份迁移完成：" + from + " → " + to
					+ "（别名已生效，无需重启；备份目录：" + (bak == null ? "无" : bak) + "）"), true);
			return 1;
		} catch (Exception e) {
			src.sendError(Text.literal("§c身份迁移失败：" + e.getMessage()));
			return 0;
		}
	}

	/** 读账号文件里的余额字段，失败返回 "(无法解析)"。 */
	private static String peek(Path file) {
		try {
			var o = com.google.gson.JsonParser.parseString(Files.readString(file, java.nio.charset.StandardCharsets.UTF_8));
			if (o.isJsonObject() && o.getAsJsonObject().has("balance")) {
				return "balance=" + o.getAsJsonObject().get("balance").getAsLong();
			}
			return "(无 balance 字段)";
		} catch (Exception e) {
			return "(无法解析)";
		}
	}

	/**
	 * {@code /redtale migrate storage <json|mysql> [confirm]}（R8）。
	 *
	 * <p>dry-run 只报条数（spec v0.6 §13.5 要求「先报条数再执行」）；
	 * 执行前把 JSON 账号目录整体备份到 {@code <root>/backup/migrate-<ts>/}。
	 */
	private static int storage(CommandContext<ServerCommandSource> ctx, boolean confirmed) {
		ServerCommandSource src = ctx.getSource();
		String to = StringArgumentType.getString(ctx, "to").toLowerCase();
		if (!to.equals("json") && !to.equals("mysql")) {
			src.sendError(Text.literal("目标只能是 json 或 mysql"));
			return 0;
		}
		String from = StorageConfig.backend().toLowerCase();
		if (from.equals(to)) {
			src.sendError(Text.literal("当前后端已经是 " + to));
			return 0;
		}
		Path accountDir = accountDir();
		int jsonCount = MysqlBackend.countJsonAccounts(accountDir);

		if (to.equals("mysql") && !MysqlBackend.available()) {
			boolean ok = MysqlBackend.init();
			if (!ok) {
				src.sendError(Text.literal("MySQL 连接失败：" + MysqlBackend.lastError()));
				return 0;
			}
		}
		int mysqlCount = MysqlBackend.available() ? MysqlBackend.countMysqlAccounts() : 0;

		if (!confirmed) {
			src.sendFeedback(() -> Text.literal("§e存储迁移预检：" + from + " → " + to), false);
			src.sendFeedback(() -> Text.literal("§7JSON 账号文件：" + jsonCount + " 个（"
					+ (accountDir == null ? "目录不可用" : accountDir.toString()) + "）"), false);
			src.sendFeedback(() -> Text.literal("§7MySQL 账号行：" + mysqlCount + " 行"), false);
			src.sendFeedback(() -> Text.literal("§7确认请执行：§f/redtale migrate storage " + to + " confirm"), false);
			return 1;
		}

		Path backup = backupDir();
		int done;
		if (to.equals("mysql")) {
			done = MysqlBackend.available() ? MysqlBackend.migrateFromJson(accountDir) : 0;
		} else {
			done = MysqlBackend.available() ? MysqlBackend.migrateToJson(accountDir) : 0;
		}
		if (done <= 0) {
			src.sendError(Text.literal("迁移未写入任何记录（源端为空或后端不可用），配置未切换"));
			return 0;
		}

		ConfigManager.get().store(StorageConfig.FILE).setPath("backend", to);
		ConfigManager.get().store(StorageConfig.FILE).save(true);
		ConfigManager.logEdit(StorageConfig.FILE, "migrate.storage", from + "->" + to + " records=" + done);

		// 重载存储模块：切 provider 的缓存/连接
		com.akira.redtale.economy.economy.BalanceService.init();

		final int n = done;
		src.sendFeedback(() -> Text.literal("§a迁移完成：" + n + " 条记录 → " + to
				+ "；备份：" + (backup == null ? "未生成" : backup.toString())), true);
		RedtaleEconomy.LOGGER.info("[migrate] storage {} → {}：{} 条", from, to, n);
		return n;
	}

	private static Path accountDir() {
		PathResolver.Resolved r = DataRoot.resolve();
		return r.path().resolve(StorageConfig.accountsDir().replace("/", java.io.File.separator));
	}

	/** 迁移前整目录备份到 {@code <root>/backup/migrate-<yyyyMMdd-HHmmss>/}；失败返回 null。 */
	private static Path backupDir() {
		try {
			PathResolver.Resolved r = DataRoot.resolve();
			Path src = r.path().resolve(StorageConfig.accountsDir().replace("/", java.io.File.separator));
			if (!Files.isDirectory(src)) return null;
			String ts = java.time.LocalDateTime.now()
					.format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
			Path dst = r.path().resolve("backup").resolve("migrate-" + ts);
			Files.createDirectories(dst);
			try (var s = Files.list(src)) {
				for (Path p : (Iterable<Path>) s::iterator) {
					if (Files.isRegularFile(p)) {
						Files.copy(p, dst.resolve(p.getFileName()), StandardCopyOption.REPLACE_EXISTING);
					}
				}
			}
			return dst;
		} catch (Exception e) {
			RedtaleEconomy.LOGGER.warn("[migrate] 备份失败：{}", e.getMessage());
			return null;
		}
	}

	private static int run(CommandContext<ServerCommandSource> ctx, boolean confirmed) {
		ServerCommandSource src = ctx.getSource();
		String to = StringArgumentType.getString(ctx, "to").toLowerCase();
		if (!to.equals("internal") && !to.equals("numismatic")) {
			src.sendError(Text.literal("目标只能是 internal 或 numismatic"));
			return 0;
		}

		BalanceProvider from = BalanceService.provider();
		if (from.id().equals(to)) {
			src.sendError(Text.literal("当前已经是 " + to + "，无需迁移"));
			return 0;
		}
		BalanceProvider target = to.equals("internal") ? BalanceService.internal() : BalanceService.numismatic();
		if (target == null || !target.available()) {
			src.sendError(Text.literal("目标 provider " + to + " 不可用（Numismatic 未安装？）"));
			return 0;
		}

		var players = src.getServer().getPlayerManager().getPlayerList();
		long total = 0;
		int readable = 0;
		for (ServerPlayerEntity p : players) {
			long v = from.get(p.getUuid());
			if (v >= 0) {
				total += v;
				readable++;
			}
		}

		if (!confirmed) {
			final int onlineCount = players.size();
			final int readableFinal = readable;
			final long totalFinal = total;
			src.sendFeedback(() -> Text.literal("§e迁移预检：" + from.id() + " → " + to), false);
			src.sendFeedback(() -> Text.literal("§7在线玩家 " + onlineCount + " 人，可读余额 " + readableFinal
					+ " 人，合计 " + BalanceService.format(totalFinal)), false);
			src.sendFeedback(() -> Text.literal("§7确认请执行：§f/redtale migrate provider " + to + " confirm"), false);
			return 1;
		}

		int done = 0;
		int skipped = 0;
		for (ServerPlayerEntity p : players) {
			long v = from.get(p.getUuid());
			if (v < 0) {
				skipped++;
				continue;
			}
			try {
				target.set(p.getUuid(), v);
				done++;
				ConfigManager.logEdit("economy.json", "migrate",
						p.getUuid() + " " + from.id() + "->" + to + " " + v);
			} catch (Exception e) {
				skipped++;
				RedtaleEconomy.LOGGER.error("[migrate] {} 迁移失败", p.getUuid(), e);
			}
		}

		// 落盘 + 切换配置
		ConfigManager.get().store(EconomyConfig.FILE).setPath("provider", to);
		ConfigManager.get().store(EconomyConfig.FILE).save(true);
		BalanceService.init();

		final int d = done;
		final int s = skipped;
		src.sendFeedback(() -> Text.literal("§a迁移完成：" + d + " 人已迁移，跳过 " + s
				+ " 人（离线或无余额组件）；provider 已切到 " + to), true);
		RedtaleEconomy.LOGGER.info("[migrate] {} → {}：{} 成功 / {} 跳过", from.id(), to, done, skipped);
		return done;
	}

	/**
	 * {@code /redtale migrate sdm scan|apply|rollback|status}（R23）。
	 *
	 * <p>scan 只读 SDM 内存数据报「人数 / 总额 / 异常」；apply 必须加 confirm，
	 * 执行前会把「谁拿了多少」快照到 backup/，rollback 就按快照扣回（只扣到 0，不变负）。
	 */
	private static int sdm(CommandContext<ServerCommandSource> ctx, String mode) {
		ServerCommandSource src = ctx.getSource();
		if (!com.akira.redtale.economy.integration.SdmMigrate.available()) {
			src.sendError(Text.literal("§c未检测到 sdm_economy，无法迁移"));
			return 0;
		}
		var server = com.akira.redtale.economy.RedtaleEconomy.server();
		if (server == null) {
			src.sendError(Text.literal("§c服务器尚未就绪"));
			return 0;
		}
		switch (mode) {
			case "status" -> {
				String snap = com.akira.redtale.economy.integration.SdmMigrate.snapshotPath();
				src.sendFeedback(() -> Text.literal(snap.isEmpty()
						? "§7还没有迁移快照（apply 后才会生成）"
						: "§7最近一份迁移快照：" + snap), false);
				return 1;
			}
			case "rollback" -> {
				String snap = com.akira.redtale.economy.integration.SdmMigrate.snapshotPath();
				if (snap.isEmpty()) {
					src.sendError(Text.literal("§c没有可用的迁移快照"));
					return 0;
				}
				src.sendFeedback(() -> Text.literal("§e回滚预检：按 " + snap
						+ " 逐人扣回（只扣到 0，不会变负）。确认请执行 §f/redtale migrate sdm rollback confirm"), false);
				return 1;
			}
			case "rollback-confirm" -> {
				int n = com.akira.redtale.economy.integration.SdmMigrate.rollback();
				if (n < 0) {
					src.sendError(Text.literal("§c回滚失败：没有快照或快照损坏"));
					return 0;
				}
				src.sendFeedback(() -> Text.literal("§a已按快照回滚 " + n + " 个账号的余额"), true);
				return 1;
			}
			default -> {
				var rows = com.akira.redtale.economy.integration.SdmMigrate.snapshot(server);
				long total = com.akira.redtale.economy.integration.SdmMigrate.total(rows);
				int neg = com.akira.redtale.economy.integration.SdmMigrate.negativeCount(rows);
				src.sendFeedback(() -> Text.literal("§eSDM 迁移预检：§f" + rows.size() + " §7个账号，合计 §f"
						+ com.akira.redtale.economy.economy.BalanceService.format(total)
						+ (neg > 0 ? " §c（" + neg + " 个负余额）" : "")), false);
				if (rows.isEmpty()) {
					src.sendFeedback(() -> Text.literal("§7没有符合条件的账号（检查 minAmount / ratio / sourceCurrencyId）"), false);
					return 1;
				}
				if ("scan".equals(mode)) {
					int shown = 0;
					for (var r : rows) {
						if (shown++ >= 5) {
							src.sendFeedback(() -> Text.literal("§8…（其余省略）"), false);
							break;
						}
						src.sendFeedback(() -> Text.literal("§8· §f" + r.uuid()
								+ " §7raw=" + r.raw() + " → §a"
								+ com.akira.redtale.economy.economy.BalanceService.format(r.copper())), false);
					}
					src.sendFeedback(() -> Text.literal("§7确认请执行 §f/redtale migrate sdm apply confirm"), false);
					return 1;
				}
				if (!com.akira.redtale.economy.config.SdmMigrateConfig.enabled()) {
					src.sendError(Text.literal("§csdm_migrate.json → enabled=false，已拒绝执行（防误触）"));
					return 0;
				}
				src.sendFeedback(() -> Text.literal("§e将写入 " + rows.size() + " 个账号，确认请执行 §f/redtale migrate sdm apply confirm"), false);
				return 1;
			}
		}
	}
}
