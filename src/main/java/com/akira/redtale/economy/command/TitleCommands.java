package com.akira.redtale.economy.command;

import com.akira.redtale.economy.config.GuildConfig;
import com.akira.redtale.economy.lang.Lang;
import com.akira.redtale.economy.module.impl.TitleModule;
import com.akira.redtale.economy.module.impl.TitleStyle;
import com.akira.redtale.economy.perm.Permissions;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.item.ItemStack;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

/**
 * {@code /title} —— 称号设置与称号物品（R34 / R35）。
 *
 * <p>{@code /title} 看自己的称号；{@code /title set|clear|give} 是管理操作（admin 权限）。
 * 真正写聊天名走 StyledChat 命令，写不写得进去都以服务端返回为准。
 */
public final class TitleCommands {

	private TitleCommands() {
	}

	public static void register(CommandDispatcher<ServerCommandSource> d) {
		CommandTable.declare(d, "titles", "title");
		// ★不能叫 "title"★：原版自带 /title（requires 权限等级 2），Brigadier 的 addChild
		// 只合并子节点与 executor、**不合并 requires** ⇒ 普通玩家看到的是
		// 「Unknown or incomplete command」（2026-10-21 假人实测）。改名 /titles。
		d.register(CommandManager.literal("titles")
				.requires(src -> Permissions.has(src, Permissions.NODE_USE))
				.executes(TitleCommands::self)
				.then(CommandManager.literal("list").executes(TitleCommands::list))
				.then(CommandManager.literal("set")
						.requires(src -> Permissions.has(src, Permissions.NODE_ADMIN))
						.then(CommandManager.argument("player", EntityArgumentType.player())
								.then(CommandManager.argument("title", StringArgumentType.greedyString())
										.executes(TitleCommands::set))))
				.then(CommandManager.literal("clear")
						.requires(src -> Permissions.has(src, Permissions.NODE_ADMIN))
						.then(CommandManager.argument("player", EntityArgumentType.player())
								.executes(TitleCommands::clear)))
				.then(CommandManager.literal("give")
						.requires(src -> Permissions.has(src, Permissions.NODE_ADMIN))
						.then(CommandManager.argument("player", EntityArgumentType.player())
								.then(CommandManager.argument("title", StringArgumentType.greedyString())
										.executes(TitleCommands::give))))
				// T23：后缀槽位。text 传 "-" 表示清除。
				.then(CommandManager.literal("suffix")
						.requires(src -> Permissions.has(src, Permissions.NODE_ADMIN))
						.then(CommandManager.argument("player", EntityArgumentType.player())
								.then(CommandManager.argument("text", StringArgumentType.greedyString())
										.executes(TitleCommands::suffix))))
				// T23：颜色 / 渐变（solid 就地改色，gradient 再补终止色）。
				.then(CommandManager.literal("style")
						.requires(src -> Permissions.has(src, Permissions.NODE_ADMIN))
						.then(CommandManager.argument("player", EntityArgumentType.player())
								.then(CommandManager.literal("solid")
										.then(CommandManager.argument("color", StringArgumentType.greedyString())
												.executes(TitleCommands::solid)))
								.then(CommandManager.literal("gradient")
										// ★# 不在 Brigadier 非引号字符串允许的字符集里★
										// （string()/word() 只吃 0-9A-Za-z_.+-），所以颜色用一个
										// greedy 参数收，再按 ':' 或空格拆两段。
										.then(CommandManager.argument("colors", StringArgumentType.greedyString())
												.executes(TitleCommands::gradient)))))
				// T60：槽位解锁（1..slots.max，0 = 回到配置默认）
				.then(CommandManager.literal("slots")
						.requires(src -> Permissions.has(src, Permissions.NODE_ADMIN))
						.then(CommandManager.argument("player", EntityArgumentType.player())
								.then(CommandManager.argument("count",
										com.mojang.brigadier.arguments.IntegerArgumentType.integer(0, 3))
										.executes(TitleCommands::slots))))
				// T60：装备到指定槽（不带 slot = 追加一个空槽）。★不能用 set★：
				// setTitle 是「单槽语义」，会清空所有槽 ⇒ 解锁到 3 槽也只剩一个称号。
				.then(CommandManager.literal("equip")
						.requires(src -> Permissions.has(src, Permissions.NODE_ADMIN))
						.then(CommandManager.argument("player", EntityArgumentType.player())
								// ★必须 greedy★：string()/word() 的字符集不含中文，`titles equip QaBot 铁匠`
								// 会报 "Expected whitespace to end one argument"。槽位顺序 = 追加顺序。
								.then(CommandManager.argument("title", StringArgumentType.greedyString())
										.executes(TitleCommands::equip))))
				// T60：卸下某一槽
				.then(CommandManager.literal("unequip")
						.requires(src -> Permissions.has(src, Permissions.NODE_ADMIN))
						.then(CommandManager.argument("player", EntityArgumentType.player())
								.then(CommandManager.argument("slot",
										com.mojang.brigadier.arguments.IntegerArgumentType.integer(0, 2))
										.executes(TitleCommands::unequip))))
				// T66：强制称号（id 来自 title.json 的 forced[]；"-" = 全部取消）
				.then(CommandManager.literal("force")
						.requires(src -> Permissions.has(src, Permissions.NODE_ADMIN))
						.then(CommandManager.argument("player", EntityArgumentType.player())
								.then(CommandManager.argument("id", StringArgumentType.greedyString())
										.executes(TitleCommands::force)))));
	}

	private static int self(CommandContext<ServerCommandSource> ctx) {
		ServerCommandSource src = ctx.getSource();
		ServerPlayerEntity p = src.getPlayer();
		if (p == null) return onlyPlayer(src);
		if (!TitleModule.enabled()) {
			src.sendError(Text.literal(Lang.msg("title.disabled")));
			return 0;
		}
		var d = TitleModule.data(p.getUuid());
		var parts = TitleModule.effectiveParts(d);
		if (parts.isEmpty()) {
			src.sendFeedback(() -> Text.literal(Lang.msg("title.none")), false);
		} else {
			StringBuilder sb = new StringBuilder();
			for (TitleModule.Part part : parts) {
				if (sb.length() > 0) sb.append("·");
				sb.append(part.text());
			}
			String shown = sb.toString();
			src.sendFeedback(() -> Text.literal(Lang.msg("title.current", shown)), false);
		}
		// T60：槽位使用情况（管理员排查「为什么第二个称号不显示」时最常看这行）
		int slots = TitleModule.slotsOf(d);
		String slotInfo = "槽位 " + Math.min(d.equipped.size(), slots) + "/" + slots
				+ (d.forced.isEmpty() ? "" : " · 强制 " + d.forced.size());
		src.sendFeedback(() -> Text.literal(slotInfo), false);
		return 1;
	}

	private static int list(CommandContext<ServerCommandSource> ctx) {
		ServerCommandSource src = ctx.getSource();
		var ranks = GuildConfig.ranks();
		src.sendFeedback(() -> Text.literal(Lang.msg("title.listHeader")), false);
		for (GuildConfig.Rank r : ranks) {
			String t = GuildConfig.title(r.id());
			if (t.isEmpty()) continue;
			src.sendFeedback(() -> Text.literal(Lang.msg("title.listLine", r.id(), r.name(), t)), false);
		}
		return 1;
	}

	private static int set(CommandContext<ServerCommandSource> ctx) {
		ServerCommandSource src = ctx.getSource();
		try {
			ServerPlayerEntity target = EntityArgumentType.getPlayer(ctx, "player");
			String title = StringArgumentType.getString(ctx, "title");
			boolean applied = TitleModule.grant(target, title);   // 加入拥有列表并立即生效
			src.sendFeedback(() -> Text.literal(Lang.msg(applied ? "title.set" : "title.setNoChat",
					target.getName().getString(), title)), false);
			return 1;
		} catch (Exception e) {
			src.sendError(Text.literal(String.valueOf(e.getMessage())));
			return 0;
		}
	}

	private static int clear(CommandContext<ServerCommandSource> ctx) {
		ServerCommandSource src = ctx.getSource();
		try {
			ServerPlayerEntity target = EntityArgumentType.getPlayer(ctx, "player");
			TitleModule.clearTitle(target);
			src.sendFeedback(() -> Text.literal(Lang.msg("title.cleared", target.getName().getString())), false);
			return 1;
		} catch (Exception e) {
			src.sendError(Text.literal(String.valueOf(e.getMessage())));
			return 0;
		}
	}

	private static int give(CommandContext<ServerCommandSource> ctx) {
		ServerCommandSource src = ctx.getSource();
		try {
			ServerPlayerEntity target = EntityArgumentType.getPlayer(ctx, "player");
			String title = StringArgumentType.getString(ctx, "title");
			ItemStack stack = TitleModule.makeItem(title, "@p");
			if (stack.isEmpty()) {
				src.sendError(Text.literal(Lang.msg("title.itemDisabled")));
				return 0;
			}
			if (!target.getInventory().insertStack(stack)) {
				target.dropItem(stack, false);
			}
			src.sendFeedback(() -> Text.literal(Lang.msg("title.given",
					target.getName().getString(), title)), false);
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

	// ---------------------------------------------------------------- T23 后缀 / 配色

	private static int suffix(CommandContext<ServerCommandSource> ctx) {
		ServerCommandSource src = ctx.getSource();
		try {
			ServerPlayerEntity target = EntityArgumentType.getPlayer(ctx, "player");
			String raw = StringArgumentType.getString(ctx, "text");
			boolean clearing = raw == null || raw.isBlank() || "-".equals(raw.trim());
			TitleModule.setSuffix(target, clearing ? "" : raw);
			String shown = clearing ? "（已清除）" : "[" + target.getName().getString() + "] 后缀：" + raw;
			src.sendFeedback(() -> Text.literal(shown), false);
			return 1;
		} catch (Exception e) {
			src.sendError(Text.literal(String.valueOf(e.getMessage())));
			return 0;
		}
	}

	private static int solid(CommandContext<ServerCommandSource> ctx) {
		return style(ctx, false);
	}

	private static int gradient(CommandContext<ServerCommandSource> ctx) {
		return style(ctx, true);
	}

	private static int style(CommandContext<ServerCommandSource> ctx, boolean gradient) {
		ServerCommandSource src = ctx.getSource();
		try {
			ServerPlayerEntity target = EntityArgumentType.getPlayer(ctx, "player");
			String raw = StringArgumentType.getString(ctx, gradient ? "colors" : "color");
			String[] parts = raw == null ? new String[0] : raw.trim().split("[:\\s]+");
			int c1 = parts.length > 0 ? TitleStyle.parse(parts[0], -1) : -1;
			if (c1 < 0) {
				src.sendError(Text.literal("颜色格式应为 #RRGGBB，例如 #FFD700"));
				return 0;
			}
			int c2 = gradient && parts.length > 1 ? TitleStyle.parse(parts[1], -1) : c1;
			if (c2 < 0) {
				src.sendError(Text.literal("终止色格式应为 #RRGGBB，例如 #8A2BE2"));
				return 0;
			}
			TitleModule.setStyle(target, gradient, c1, c2);
			String msg = gradient
					? "配色已设为渐变 " + TitleStyle.hex(c1) + " → " + TitleStyle.hex(c2)
					: "配色已设为纯色 " + TitleStyle.hex(c1);
			src.sendFeedback(() -> Text.literal(msg), false);
			return 1;
		} catch (Exception e) {
			src.sendError(Text.literal(String.valueOf(e.getMessage())));
			return 0;
		}
	}

	// ---------------------------------------------------------------- T60 槽位 / T66 强制

	private static int slots(CommandContext<ServerCommandSource> ctx) {
		ServerCommandSource src = ctx.getSource();
		try {
			ServerPlayerEntity target = EntityArgumentType.getPlayer(ctx, "player");
			int want = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "count");
			int now = TitleModule.setSlots(target, want);
			src.sendFeedback(() -> Text.literal(target.getName().getString() + " 的称号槽位：" + now
					+ (want == 0 ? "（配置默认）" : "")), false);
			com.akira.redtale.economy.net.TitleNetworking.send(target);
			return 1;
		} catch (Exception e) {
			src.sendError(Text.literal(String.valueOf(e.getMessage())));
			return 0;
		}
	}

	/** T60：装备到某个槽（{@code slot} 缺省 = 追加到末尾；1 起，内部转 0 起）。 */
	private static int equip(CommandContext<ServerCommandSource> ctx) {
		ServerCommandSource src = ctx.getSource();
		try {
			ServerPlayerEntity target = EntityArgumentType.getPlayer(ctx, "player");
			String title = StringArgumentType.getString(ctx, "title");
			boolean chat = TitleModule.equip(target, title, -1);   // -1 = 追加到末尾
			var d = TitleModule.data(target.getUuid());
			int slots = TitleModule.slotsOf(d);
			int used = Math.min(TitleModule.equipped(target.getUuid()).size(), slots);
			com.akira.redtale.economy.net.TitleNetworking.send(target);
			src.sendFeedback(() -> Text.literal(target.getName().getString() + " 已装备称号「" + title
					+ "」（槽位 " + used + "/" + slots + "）"
					+ (chat ? "" : " · 未改聊天名")), false);
			return 1;
		} catch (Exception e) {
			src.sendError(Text.literal(String.valueOf(e.getMessage())));
			return 0;
		}
	}

	private static int unequip(CommandContext<ServerCommandSource> ctx) {
		ServerCommandSource src = ctx.getSource();
		try {
			ServerPlayerEntity target = EntityArgumentType.getPlayer(ctx, "player");
			int slot = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "slot");
			TitleModule.unequipSlot(target, slot);
			src.sendFeedback(() -> Text.literal(target.getName().getString() + " 已卸下槽位 " + (slot + 1)), false);
			com.akira.redtale.economy.net.TitleNetworking.send(target);
			return 1;
		} catch (Exception e) {
			src.sendError(Text.literal(String.valueOf(e.getMessage())));
			return 0;
		}
	}

	private static int force(CommandContext<ServerCommandSource> ctx) {
		ServerCommandSource src = ctx.getSource();
		try {
			ServerPlayerEntity target = EntityArgumentType.getPlayer(ctx, "player");
			String id = StringArgumentType.getString(ctx, "id");
			boolean clearing = id == null || id.isBlank() || "-".equals(id.trim());
			if (!clearing && com.akira.redtale.economy.config.TitleConfig.forcedById(id.trim()) == null) {
				src.sendError(Text.literal("title.json 的 forced[] 里没有这个 id：" + id.trim()));
				return 0;
			}
			var now = TitleModule.setForced(target, clearing ? "-" : id.trim());
			String msg = now.isEmpty()
					? target.getName().getString() + " 的强制称号已清空"
					: target.getName().getString() + " 的强制称号：" + String.join(", ", now);
			src.sendFeedback(() -> Text.literal(msg), false);
			com.akira.redtale.economy.net.TitleNetworking.send(target);
			return 1;
		} catch (Exception e) {
			src.sendError(Text.literal(String.valueOf(e.getMessage())));
			return 0;
		}
	}
}
