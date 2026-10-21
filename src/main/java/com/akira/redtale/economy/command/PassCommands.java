package com.akira.redtale.economy.command;

import com.akira.redtale.economy.config.BattlePassConfig;
import com.akira.redtale.economy.lang.Lang;
import com.akira.redtale.economy.module.impl.BattlePassModule;
import com.akira.redtale.economy.perm.Permissions;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

/**
 * {@code /pass} —— 战令面板（R20）：看进度、领奖励、解锁付费轨。
 */
public final class PassCommands {

	private PassCommands() {
	}

	public static void register(CommandDispatcher<ServerCommandSource> d) {
		CommandTable.declare(d, "pass", "battlepass");
		d.register(CommandManager.literal("pass")
				.requires(src -> Permissions.has(src, Permissions.NODE_USE))
				.executes(PassCommands::info)
				.then(CommandManager.literal("claim")
						.then(CommandManager.argument("level", IntegerArgumentType.integer(1, 500))
								.executes(ctx -> claim(ctx, "free"))
								.then(CommandManager.literal("premium").executes(ctx -> claim(ctx, "premium")))))
				.then(CommandManager.literal("unlock").executes(PassCommands::unlock))
				// T02：赛季清单（含归档状态与快照摘要）
				.then(CommandManager.literal("seasons").executes(PassCommands::seasons))
				// T04：结算并切到下一个赛季（先归档，再改配置指针）
				.then(CommandManager.literal("rollover")
						.requires(src -> Permissions.has(src, Permissions.NODE_OWNER))
						.then(CommandManager.argument("next", StringArgumentType.word())
								.executes(ctx -> rollover(ctx, StringArgumentType.getString(ctx, "next")))))
				// T02：归档某赛季（写快照 + 冻结进度）
				.then(CommandManager.literal("archive")
						.requires(src -> Permissions.has(src, Permissions.NODE_OWNER))
						.then(CommandManager.argument("season", StringArgumentType.word())
								.executes(ctx -> archive(ctx, StringArgumentType.getString(ctx, "season")))))
				// T03：历史赛季补领（accessMode=off 时一律拒绝）
				.then(CommandManager.literal("legacy")
						.then(CommandManager.argument("season", StringArgumentType.word())
								.executes(ctx -> legacyInfo(ctx, StringArgumentType.getString(ctx, "season")))
								.then(CommandManager.literal("claim")
										.then(CommandManager.argument("level", IntegerArgumentType.integer(1, 500))
												.executes(ctx -> legacyClaim(ctx, "free"))
												.then(CommandManager.literal("premium")
														.executes(ctx -> legacyClaim(ctx, "premium")))))
								.then(CommandManager.literal("all").executes(PassCommands::legacyAll))))
				.then(CommandManager.literal("addxp")
						.requires(src -> Permissions.has(src, Permissions.NODE_ADMIN))
						.then(CommandManager.argument("player", EntityArgumentType.player())
								.then(CommandManager.argument("amount", LongArgumentType.longArg(1, 1_000_000))
										.executes(PassCommands::addXp)))));
	}

	private static int info(CommandContext<ServerCommandSource> ctx) {
		ServerCommandSource src = ctx.getSource();
		ServerPlayerEntity p = src.getPlayer();
  PageCommands.hintLegacy(p, "pass", "pass");
		if (p == null) return onlyPlayer(src);
		if (!BattlePassModule.enabled()) {
			src.sendError(Text.literal(Lang.msg("pass.disabled")));
			return 0;
		}
		// 只下发快照，界面自己渲染；聊天框保持干净
		com.akira.redtale.economy.net.PassNetworking.send(p);
		return 1;
	}

	/** T02：列出赛季（哪个是当前、哪个已归档、归档时多少人多少经验）。 */
	private static int seasons(CommandContext<ServerCommandSource> ctx) {
		ServerCommandSource src = ctx.getSource();
		var list = com.akira.redtale.economy.module.impl.PassSeasons.list();
		if (list.isEmpty()) {
			src.sendFeedback(() -> Text.literal("§e[pass] 还没有赛季记录（开服或 /redtale reload 后自动登记当前赛季）"),
					false);
			return 1;
		}
		String cur = BattlePassConfig.seasonId();
		src.sendFeedback(() -> Text.literal("§6[pass] 当前赛季 §f" + cur + " §7（" + list.size() + " 条记录）"), false);
		for (var e : list) {
			var snap = com.akira.redtale.economy.module.impl.PassSeasons.snapshotOf(e.id());
			String tail = e.archived()
					? " §7| 已归档 §f" + e.archivedAt()
					: (e.id().equals(cur) ? " §a| 进行中" : " §7| 未归档");
			String stat = snap == null ? "" : " §7| §f" + snap.players() + "§7人 §f" + snap.totalXp() + "§7xp";
			src.sendFeedback(() -> Text.literal("§8- §f" + e.id() + " §7" + e.name()
					+ " §8[" + (e.startAt().isBlank() ? "?" : e.startAt()) + " ~ "
					+ (e.endAt().isBlank() ? "?" : e.endAt()) + "]" + tail + stat), false);
		}
		return 1;
	}

	/** T04：手动结算 + 切换赛季。 */
	private static int rollover(CommandContext<ServerCommandSource> ctx, String next) {
		ServerCommandSource src = ctx.getSource();
		var r = BattlePassModule.rolloverTo(next);
		if (!r.switched()) {
			src.sendError(Text.literal("§c未切换（当前仍为 §f" + r.from() + "§c）：目标为空/同名，或配置写入失败，见日志"));
			return 0;
		}
		src.sendFeedback(() -> Text.literal("§a[pass] 赛季 §f" + r.from() + " §a→ §f" + r.to()
				+ " §7| 归档 §f" + r.archive()), true);
		src.sendFeedback(() -> Text.literal("§7旧赛季数据保留在 battlepass/" + r.from() + "/（含 _snapshot.json）"), false);
		return 1;
	}

	/** T02：归档赛季（写 _snapshot.json + 标记 archivedAt；之后该赛季只读）。 */
	private static int archive(CommandContext<ServerCommandSource> ctx, String season) {
		ServerCommandSource src = ctx.getSource();
		var res = com.akira.redtale.economy.module.impl.PassSeasons.archive(season, System.currentTimeMillis());
		switch (res) {
			case OK -> {
				var snap = com.akira.redtale.economy.module.impl.PassSeasons.snapshotOf(season);
				src.sendFeedback(() -> Text.literal("§a[pass] 已归档 §f" + season
						+ (snap == null ? "" : " §7：§f" + snap.players() + " §7人 §f" + snap.totalXp() + "§7 经验")), true);
			}
			case ALREADY -> src.sendError(Text.literal("§e该赛季已归档（快照已存在，不覆盖）"));
			case NO_DATA -> src.sendError(Text.literal("§c找不到赛季或数据目录：" + season));
			default -> src.sendError(Text.literal("§c归档失败，见日志"));
		}
		return 1;
	}

	/** T03：看某历史赛季能补领什么（xp / 档位 / 两条轨剩余等级）。 */
	private static int legacyInfo(CommandContext<ServerCommandSource> ctx, String season) {
		ServerCommandSource src = ctx.getSource();
		ServerPlayerEntity p = src.getPlayer();
		if (p == null) return onlyPlayer(src);
		var v = BattlePassModule.legacy(season, p.getUuid());
		if (!v.archived()) {
			src.sendError(Text.literal("§c赛季 §f" + season + " §c未归档或不存在（没有 _snapshot.json）"));
			return 0;
		}
		src.sendFeedback(() -> Text.literal("§6[pass] 历史赛季 §f" + season
				+ " §7档位 §f" + v.access()
				+ " §7| 冻结 xp §f" + v.xp() + " §7| 当季付费 §f" + (v.premium() ? "是" : "否")), false);
		src.sendFeedback(() -> Text.literal("§7剩余可补领：免费轨 §f" + v.free().size()
				+ " §7项 " + head(v.free()) + " §8| §7付费轨 §f" + v.premiumLevels().size()
				+ " §7项 " + head(v.premiumLevels())), false);
		if (v.access() == com.akira.redtale.economy.module.impl.PassArchive.Access.OFF) {
			src.sendFeedback(() -> Text.literal(
					"§e补领未开启：battlepass.json → archive.accessMode = purchased | all"), false);
		}
		return 1;
	}

	/** T03：补领单个等级（track = free / premium）。 */
	private static int legacyClaim(CommandContext<ServerCommandSource> ctx, String track) {
		ServerCommandSource src = ctx.getSource();
		ServerPlayerEntity p = src.getPlayer();
		if (p == null) return onlyPlayer(src);
		String season = StringArgumentType.getString(ctx, "season");
		int level = IntegerArgumentType.getInteger(ctx, "level");
		var r = BattlePassModule.claimLegacy(p.getUuid(), season, track, level);
		switch (r) {
			case OK -> {
				p.sendMessage(Text.literal("§a[pass] 已补领 §f" + season + " §7" + track + " §fLv." + level), true);
				return 1;
			}
			case ALREADY -> {
				p.sendMessage(Text.literal("§e这一档已经领过了"), true);
				return 0;
			}
			case OFF -> {
				src.sendError(Text.literal("§c补领未开启（battlepass.json → archive.accessMode）"));
				return 0;
			}
			case CURRENT -> {
				src.sendError(Text.literal("§e这是当前赛季，用 §f/pass claim " + level));
				return 0;
			}
			case NO_SNAPSHOT -> {
				src.sendError(Text.literal("§c该赛季未归档（先用 §f/pass archive " + season + "§c）"));
				return 0;
			}
			case NOT_ENTITLED -> {
				src.sendError(Text.literal("§c补领条件不满足（accessMode=purchased 需要当季购买过付费轨）"));
				return 0;
			}
			case LOCKED -> {
				src.sendError(Text.literal("§c该赛季没有购买付费轨"));
				return 0;
			}
			case NOT_REACHED -> {
				src.sendError(Text.literal("§c当季等级不够 Lv." + level));
				return 0;
			}
			case NOT_FOUND -> {
				src.sendError(Text.literal("§c快照里没有你的记录（当季没参加）"));
				return 0;
			}
			case BAD_LEVEL -> {
				src.sendError(Text.literal("§c等级超出配置上限"));
				return 0;
			}
			default -> {
				src.sendError(Text.literal("§c补领失败，见日志"));
				return 0;
			}
		}
	}

	/** T03：一次补领该赛季所有剩余等级（升序，失败即停并报告进度）。 */
	private static int legacyAll(CommandContext<ServerCommandSource> ctx) {
		ServerCommandSource src = ctx.getSource();
		ServerPlayerEntity p = src.getPlayer();
		if (p == null) return onlyPlayer(src);
		String season = StringArgumentType.getString(ctx, "season");
		var v = BattlePassModule.legacy(season, p.getUuid());
		if (!v.archived()) {
			src.sendError(Text.literal("§c赛季 §f" + season + " §c未归档或不存在"));
			return 0;
		}
		int ok = 0;
		String stop = null;
		for (int lv : v.free()) {
			var r = BattlePassModule.claimLegacy(p.getUuid(), season, "free", lv);
			if (r == com.akira.redtale.economy.module.impl.PassArchive.Result.OK) {
				ok++;
			} else {
				stop = "free Lv." + lv + " → " + r;
				break;
			}
		}
		if (stop == null) {
			for (int lv : v.premiumLevels()) {
				var r = BattlePassModule.claimLegacy(p.getUuid(), season, "premium", lv);
				if (r == com.akira.redtale.economy.module.impl.PassArchive.Result.OK) {
					ok++;
				} else {
					stop = "premium Lv." + lv + " → " + r;
					break;
				}
			}
		}
		int done = ok;
		if (stop == null) {
			p.sendMessage(Text.literal("§a[pass] 已补领 §f" + season + " §a共 §f" + done + " §a项"), true);
			return 1;
		}
		final String reason = stop;
		src.sendError(Text.literal("§c补领中断（已领 §f" + done + "§c 项）：§f" + reason));
		return 0;
	}

	/** 等级列表只显示前 10 个，避免刷屏。 */
	private static String head(java.util.List<Integer> levels) {
		if (levels.isEmpty()) return "";
		StringBuilder sb = new StringBuilder("§8[");
		for (int i = 0; i < levels.size() && i < 10; i++) {
			if (i > 0) sb.append(',');
			sb.append(levels.get(i));
		}
		if (levels.size() > 10) sb.append(",…");
		return sb.append(']').toString();
	}

	private static int claim(CommandContext<ServerCommandSource> ctx, String track) {		ServerCommandSource src = ctx.getSource();
		ServerPlayerEntity p = src.getPlayer();
		if (p == null) return onlyPlayer(src);
		int level = IntegerArgumentType.getInteger(ctx, "level");
		BattlePassModule.ClaimResult r = BattlePassModule.claim(p.getUuid(), track, level);
		switch (r) {
			case OK -> {
				return 1;
			}
			case LOCKED -> {
				p.sendMessage(Text.literal(Lang.msg("pass.needUnlock")), true);
				return 0;
			}
			case NOT_REACHED -> {
				p.sendMessage(Text.literal(Lang.msg("pass.notReached", level)), true);
				return 0;
			}
			case ALREADY -> {
				p.sendMessage(Text.literal(Lang.msg("pass.already")), true);
				return 0;
			}
			default -> {
				p.sendMessage(Text.literal(Lang.msg("pass.disabled")), true);
				return 0;
			}
		}
	}

	private static int unlock(CommandContext<ServerCommandSource> ctx) {
		ServerCommandSource src = ctx.getSource();
		ServerPlayerEntity p = src.getPlayer();
		if (p == null) return onlyPlayer(src);
		if (BattlePassModule.unlockPremium(p.getUuid())) {
			src.sendFeedback(() -> Text.literal(Lang.msg("pass.unlockOk",
					BattlePassConfig.unlockAmount(), BattlePassConfig.unlockCurrency())), false);
			com.akira.redtale.economy.net.PassNetworking.send(p);
			return 1;
		}
		src.sendError(Text.literal(Lang.msg("pass.unlockFail")));
		return 0;
	}

	private static int addXp(CommandContext<ServerCommandSource> ctx) {
		ServerCommandSource src = ctx.getSource();
		try {
			ServerPlayerEntity target = EntityArgumentType.getPlayer(ctx, "player");
			long amount = LongArgumentType.getLong(ctx, "amount");
			BattlePassModule.addXp(target.getUuid(), "admin", amount);
			src.sendFeedback(() -> Text.literal(Lang.msg("pass.addXp",
					target.getName().getString(), amount,
					BattlePassModule.levelFor(BattlePassModule.snapshot(target.getUuid()).xp))), false);
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
