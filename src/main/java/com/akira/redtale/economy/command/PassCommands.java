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
				// T02：归档某赛季（写快照 + 冻结进度）
				.then(CommandManager.literal("archive")
						.requires(src -> Permissions.has(src, Permissions.NODE_OWNER))
						.then(CommandManager.argument("season", StringArgumentType.word())
								.executes(ctx -> archive(ctx, StringArgumentType.getString(ctx, "season")))))
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

	private static int claim(CommandContext<ServerCommandSource> ctx, String track) {
		ServerCommandSource src = ctx.getSource();
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
