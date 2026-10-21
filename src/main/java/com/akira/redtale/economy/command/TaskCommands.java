package com.akira.redtale.economy.command;

import com.akira.redtale.economy.perm.Permissions;
import com.akira.redtale.economy.economy.BalanceService;
import com.akira.redtale.economy.lang.Lang;
import com.akira.redtale.economy.module.impl.BattlePassModule;
import com.akira.redtale.economy.module.impl.BountyModule;
import com.akira.redtale.economy.module.impl.GuildModule;
import com.akira.redtale.economy.module.impl.TaskModule;
import com.akira.redtale.economy.module.impl.WarehouseModule;
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
		CommandTable.declare(d, "tasks", "task");
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
		PageCommands.hintLegacy(p, "tasks", "tasks");

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
			// R29：次数用完 → 尝试消耗刷新券（背包优先，其次仓库）
			String ticket = TaskModule.refreshTicketItem();
			if (ticket.isEmpty() || !consumeTicket(p, ticket)) {
				src.sendError(Text.literal(Lang.msg("tasks.noRefresh")));
				return 0;
			}
			TaskModule.refreshByTicket(p.getUuid());
			src.sendFeedback(() -> Text.literal(Lang.msg("tasks.refreshedByTicket", displayName(ticket))), false);
			return list(ctx);
		}
		src.sendFeedback(() -> Text.literal(Lang.msg("tasks.refreshed")), false);
		return list(ctx);
	}

	private static int submit(CommandContext<ServerCommandSource> ctx) {
		ServerCommandSource src = ctx.getSource();
		ServerPlayerEntity p = src.getPlayer();
		if (p == null) return onlyPlayer(src);
		// T18：门禁必须在扣物品之前 —— 放到 claimAndSave 里就晚了（那时仓库/背包已经被扣）
		if (altBlocked(src, p)) return 0;
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

		// 先全量校验再扣，避免扣了一半发现不够（R27：仓库也算可用量）
		for (TaskModule.ItemCount ic : t.items) {
			if (countAvailable(p, ic.id) < ic.count) {
				src.sendError(Text.literal(Lang.msg("tasks.missing", displayName(ic.id), ic.count)));
				return 0;
			}
		}

		// E2：扣物品与记进度必须一次落盘。先写 intent（同步 fsync），
		// 每扣完一条就把 taken=true 刷下去；崩溃重启时按 taken 逐条退还，
		// 不会出现「物品扣了、任务没完成」又无人知道的情况。
		com.google.gson.JsonObject payload = new com.google.gson.JsonObject();
		payload.addProperty("slot", slot);
		com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
		for (TaskModule.ItemCount ic : t.items) {
			com.google.gson.JsonObject o = new com.google.gson.JsonObject();
			o.addProperty("id", ic.id);
			o.addProperty("count", ic.count);
			o.addProperty("taken", false);
			arr.add(o);
		}
		payload.add("items", arr);
		com.akira.redtale.economy.journal.TxnJournal.Txn txn =
				com.akira.redtale.economy.journal.TxnJournal.begin(
						com.akira.redtale.economy.journal.TxnJournal.TASK_SUBMIT, p.getUuid(), payload);

		for (int i = 0; i < t.items.size(); i++) {
			TaskModule.ItemCount ic = t.items.get(i);
			// R27：先扣仓库，不够的部分再扣背包
			int fromWarehouse = WarehouseModule.takeFor(p.getUuid(), ic.id, ic.count);
			int rest = ic.count - fromWarehouse;
			if (rest > 0) removeFrom(p, ic.id, rest);
			arr.get(i).getAsJsonObject().addProperty("taken", true);
			txn.flush();
		}

		long reward = TaskModule.claimAndSave(p.getUuid(), slot);
		if (reward < 0) {
			// 进度没写成：把刚扣的物品原样还回去（物品已离开背包，直接 insert）
			for (TaskModule.ItemCount ic : t.items) giveBack(p, ic);
			txn.dismiss();
			src.sendError(Text.literal(Lang.msg("tasks.already")));
			return 0;
		}
		txn.state("claimed");
		txn.commit();
		// R20：领任务给战令经验
		BattlePassModule.addXp(p.getUuid(), "taskClaim");
		// R31：submit 类悬赏跟着缴纳一起推进（按实际缴纳的物品算）
		for (TaskModule.ItemCount ic : t.items) {
			BountyModule.onSubmit(p.getUuid(), ic.id, ic.count);
		}
		// R32：缴纳涨协会声望
		GuildModule.addRep(p.getUuid(), "submit");
		final long r = reward;
		src.sendFeedback(() -> Text.literal(Lang.msg("tasks.claimed", slot, BalanceService.format(r))), false);
		return 1;
	}

	private static int claim(CommandContext<ServerCommandSource> ctx) {
		ServerCommandSource src = ctx.getSource();
		ServerPlayerEntity p = src.getPlayer();
		if (p == null) return onlyPlayer(src);
		if (altBlocked(src, p)) return 0;
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
		// T05：事件型任务只认服务端记账，不数背包
		if (t.event != null && !t.event.isBlank()) {
			int need = TaskModule.needOf(t);
			int have = TaskModule.eventHave(p.getUuid(), TaskModule.load(p.getUuid()), t);
			if (have < need) {
				String lb = t.label == null || t.label.isBlank() ? t.event : t.label;
				src.sendError(Text.literal(lb + " " + have + "/" + need));
				return 0;
			}
		} else {
			for (TaskModule.ItemCount ic : t.items) {
				if (countOf(p, ic.id) < ic.count) {
					src.sendError(Text.literal(Lang.msg("tasks.missing", displayName(ic.id), ic.count)));
					return 0;
				}
			}
		}
		long reward = TaskModule.claimAndSave(p.getUuid(), slot);
		if (reward < 0) {
			src.sendError(Text.literal(Lang.msg("tasks.already")));
			return 0;
		}
		// R20：领任务给战令经验
		BattlePassModule.addXp(p.getUuid(), "taskClaim");
		final long r = reward;
		src.sendFeedback(() -> Text.literal(Lang.msg("tasks.claimed", slot, BalanceService.format(r))), false);
		return 1;
	}

	// ---------------------------------------------------------------- 背包 / 仓库

	/** 背包 + 仓库的可用总量（R27 缴纳校验用）。 */
	private static int countAvailable(ServerPlayerEntity p, String itemId) {
		return countOf(p, itemId) + WarehouseModule.countFor(p.getUuid(), itemId);
	}

	/** E2 回滚：把已扣的物品塞回背包，塞不下就丢在脚下（不能凭空消失）。 */
	private static void giveBack(ServerPlayerEntity p, TaskModule.ItemCount ic) {
		Item item = item(ic.id);
		if (item == null || ic.count <= 0) return;
		ItemStack back = new ItemStack(item, ic.count);
		p.getInventory().insertStack(back);
		if (!back.isEmpty()) p.dropItem(back, false);
		p.getInventory().markDirty();
	}

	/** R29：消耗 1 张刷新券；背包优先，其次仓库。 */
	private static boolean consumeTicket(ServerPlayerEntity p, String ticketId) {
		if (countOf(p, ticketId) > 0) {
			removeFrom(p, ticketId, 1);
			return true;
		}
		if (TaskModule.refreshTicketFromWarehouse()
				&& WarehouseModule.takeFor(p.getUuid(), ticketId, 1) > 0) {
			return true;
		}
		return false;
	}

	private static int countOf(ServerPlayerEntity p, String itemId) {
		Item item = item(itemId);
		if (item == null) return 0;
		int n = 0;
		com.akira.redtale.economy.shop.ContainerScan.Policy policy = containerPolicy();
		for (int i = 0; i < p.getInventory().size(); i++) {
			ItemStack st = p.getInventory().getStack(i);
			if (st.isEmpty()) continue;
			if (st.getItem() == item) {
				n += st.getCount();
			} else if (policy != com.akira.redtale.economy.shop.ContainerScan.Policy.IGNORE
					&& st.getNbt() != null) {
				// R26：开关打开才解析容器内容（默认 ignore 走不到这里）
				n += (int) com.akira.redtale.economy.shop.ContainerScan.count(st.getNbt(), itemId, policy);
			}
		}
		return n;
	}

	private static com.akira.redtale.economy.shop.ContainerScan.Policy containerPolicy() {
		return com.akira.redtale.economy.shop.ContainerScan.Policy
				.of(com.akira.redtale.economy.module.impl.TaskModule.containerPolicy());
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
		// R26：背包格不够时再从容器物品里扣（只在开关打开时）
		com.akira.redtale.economy.shop.ContainerScan.Policy policy = containerPolicy();
		if (left > 0 && policy != com.akira.redtale.economy.shop.ContainerScan.Policy.IGNORE) {
			for (int i = 0; i < p.getInventory().size() && left > 0; i++) {
				ItemStack st = p.getInventory().getStack(i);
				if (st.isEmpty() || st.getItem() == item || st.getNbt() == null) continue;
				left -= com.akira.redtale.economy.shop.ContainerScan.take(st.getNbt(), itemId, left, policy);
			}
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

	/**
	 * T18：防小号门禁。返回 {@code true} 表示已拒绝（调用方立刻 return 0，不要动物品）。
	 *
	 * <p>默认配置 {@code enabled=false} 时恒放行，等于零开销的一次 map 查询。
	 */
	private static boolean altBlocked(ServerCommandSource src, ServerPlayerEntity p) {
		com.akira.redtale.economy.storage.AltGuard.Decision d =
				com.akira.redtale.economy.storage.AltGuard.gate(p.getUuid(), p.getIp(), "task");
		if (d.allowed()) return false;
		src.sendError(Text.literal("§c[防小号] " + d.reason()));
		return true;
	}

	private static int onlyPlayer(ServerCommandSource src) {
		src.sendError(Text.literal(Lang.t("cmd.onlyPlayer")));
		return 0;
	}
}
