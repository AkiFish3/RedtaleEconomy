package com.akira.redtale.economy.command;

import com.akira.redtale.economy.perm.Permissions;
import com.akira.redtale.economy.economy.BalanceService;
import com.akira.redtale.economy.lang.Lang;
import com.akira.redtale.economy.module.impl.TaskModule;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

/**
 * {@code /tasks} 系列。
 *
 * <p>数量校验全在服务端：先按 id 统计背包真实持有量，再扣除；
 * detect 模式只校验不扣。客户端无法伪造已完成状态。
 */
public final class TaskCommands {

	private TaskCommands() {
	}

	public static void register(CommandDispatcher<ServerCommandSource> d) {
		d.register(CommandManager.literal("tasks").requires(src -> Permissions.has(src, Permissions.NODE_USE))
				.executes(TaskCommands::list)
				.then(CommandManager.literal("refresh").executes(TaskCommands::refresh))
				.then(CommandManager.literal("submit")
						.then(CommandManager.argument("slot", IntegerArgumentType.integer(0, 63))
								.suggests(Suggest.slots(taskSlots()))
								.executes(TaskCommands::submit)))
				.then(CommandManager.literal("claim")
						.then(CommandManager.argument("slot", IntegerArgumentType.integer(0, 63))
								.suggests(Suggest.slots(taskSlots()))
								.executes(TaskCommands::claim))));
	}

	/** 补全用：当前配置的每日任务数（不缓存，改配置后立刻反映）。 */
	private static int taskSlots() {
		return (int) com.akira.redtale.economy.module.impl.TaskModule.settings().getLong("tasksPerDay", 3);
	}

	private static int list(CommandContext<ServerCommandSource> ctx) {
		ServerCommandSource src = ctx.getSource();
		ServerPlayerEntity p = src.getPlayer();
		if (p == null) return onlyPlayer(src);

		TaskModule.Daily d = TaskModule.load(p.getUuid());
		src.sendFeedback(() -> Text.literal(Lang.msg("tasks.header", d.date, d.refreshesLeft)), false);
		for (TaskModule.Task t : d.tasks) {
			StringBuilder sb = new StringBuilder();
			sb.append(t.claimed ? "§8✔ " : "§f").append("§e#").append(t.slot).append(" §7[")
					.append(t.pool).append("] ");
			for (TaskModule.ItemCount ic : t.items) {
				sb.append("§f").append(displayName(ic.id)).append("§7×").append(ic.count).append("  ");
			}
			sb.append("§7→ §a").append(BalanceService.format(t.reward));
			sb.append("§8 (").append(t.completion).append(")");
			src.sendFeedback(() -> Text.literal(sb.toString()), false);
		}
		if (d.tasks.isEmpty()) {
			src.sendFeedback(() -> Text.literal(Lang.t("tasks.empty")), false);
		}
		// U2：同时下发可视化面板（客户端没装本模组时会忽略这个包，聊天栏输出照常）
		com.akira.redtale.economy.net.TaskNetworking.sendSnapshot(p);
		return 1;
	}

	private static int refresh(CommandContext<ServerCommandSource> ctx) {
		ServerCommandSource src = ctx.getSource();
		ServerPlayerEntity p = src.getPlayer();
		if (p == null) return onlyPlayer(src);
		TaskModule.Action a = TaskModule.refresh(p.getUuid());
		if (a == TaskModule.Action.NO_REFRESH) {
			src.sendError(Text.literal(Lang.msg("tasks.noRefresh")));
			return 0;
		}
		src.sendFeedback(() -> Text.literal(Lang.msg("tasks.refreshed")), false);
		return list(ctx);
	}

	private static int submit(CommandContext<ServerCommandSource> ctx) {
		ServerCommandSource src = ctx.getSource();
		ServerPlayerEntity p = src.getPlayer();
		if (p == null) return onlyPlayer(src);
		int slot = IntegerArgumentType.getInteger(ctx, "slot");
		TaskModule.Task t = TaskModule.task(p.getUuid(), slot);
		if (t == null) {
			src.sendError(Text.literal(Lang.msg("tasks.noTask", slot)));
			return 0;
		}
		if (t.claimed) {
			src.sendError(Text.literal(Lang.msg("tasks.already")));
			return 0;
		}
		if (!"submit".equals(t.completion)) {
			src.sendError(Text.literal(Lang.msg("tasks.wrongMode", "claim")));
			return 0;
		}

		// 先全量校验再扣，避免扣了一半发现不够
		for (TaskModule.ItemCount ic : t.items) {
			if (countOf(p, ic.id) < ic.count) {
				src.sendError(Text.literal(Lang.msg("tasks.missing", displayName(ic.id), ic.count)));
				return 0;
			}
		}
		for (TaskModule.ItemCount ic : t.items) {
			removeFrom(p, ic.id, ic.count);
		}

		long reward = TaskModule.claimAndSave(p.getUuid(), slot);
		if (reward < 0) {
			src.sendError(Text.literal(Lang.msg("tasks.already")));
			return 0;
		}
		final long r = reward;
		src.sendFeedback(() -> Text.literal(Lang.msg("tasks.claimed", slot, BalanceService.format(r))), false);
		return 1;
	}

	private static int claim(CommandContext<ServerCommandSource> ctx) {
		ServerCommandSource src = ctx.getSource();
		ServerPlayerEntity p = src.getPlayer();
		if (p == null) return onlyPlayer(src);
		int slot = IntegerArgumentType.getInteger(ctx, "slot");
		TaskModule.Task t = TaskModule.task(p.getUuid(), slot);
		if (t == null) {
			src.sendError(Text.literal(Lang.msg("tasks.noTask", slot)));
			return 0;
		}
		if (t.claimed) {
			src.sendError(Text.literal(Lang.msg("tasks.already")));
			return 0;
		}
		if (!"detect".equals(t.completion)) {
			src.sendError(Text.literal(Lang.msg("tasks.wrongMode", "submit")));
			return 0;
		}
		for (TaskModule.ItemCount ic : t.items) {
			if (countOf(p, ic.id) < ic.count) {
				src.sendError(Text.literal(Lang.msg("tasks.missing", displayName(ic.id), ic.count)));
				return 0;
			}
		}
		long reward = TaskModule.claimAndSave(p.getUuid(), slot);
		if (reward < 0) {
			src.sendError(Text.literal(Lang.msg("tasks.already")));
			return 0;
		}
		final long r = reward;
		src.sendFeedback(() -> Text.literal(Lang.msg("tasks.claimed", slot, BalanceService.format(r))), false);
		return 1;
	}

	// ---------------------------------------------------------------- 背包

	private static int countOf(ServerPlayerEntity p, String itemId) {
		Item item = item(itemId);
		if (item == null) return 0;
		int n = 0;
		for (int i = 0; i < p.getInventory().size(); i++) {
			ItemStack st = p.getInventory().getStack(i);
			if (!st.isEmpty() && st.getItem() == item) n += st.getCount();
		}
		return n;
	}

	private static void removeFrom(ServerPlayerEntity p, String itemId, int count) {
		Item item = item(itemId);
		if (item == null) return;
		int left = count;
		for (int i = 0; i < p.getInventory().size() && left > 0; i++) {
			ItemStack st = p.getInventory().getStack(i);
			if (st.isEmpty() || st.getItem() != item) continue;
			int take = Math.min(left, st.getCount());
			st.decrement(take);
			left -= take;
			if (st.isEmpty()) p.getInventory().setStack(i, ItemStack.EMPTY);
		}
		p.getInventory().markDirty();
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

	private static String displayName(String itemId) {
		Item item = item(itemId);
		return item == null ? itemId : new ItemStack(item).getName().getString();
	}

	private static int onlyPlayer(ServerCommandSource src) {
		src.sendError(Text.literal(Lang.t("cmd.onlyPlayer")));
		return 0;
	}
}
