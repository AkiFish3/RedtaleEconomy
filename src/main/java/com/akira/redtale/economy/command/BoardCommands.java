package com.akira.redtale.economy.command;

import com.akira.redtale.economy.RedtaleEconomy;
import com.akira.redtale.economy.board.BoardStore;
import com.akira.redtale.economy.config.BoardConfig;
import com.akira.redtale.economy.economy.BalanceService;
import com.akira.redtale.economy.lang.Lang;
import com.akira.redtale.economy.module.impl.BoardModule;
import com.akira.redtale.economy.perm.Permissions;
import com.akira.redtale.economy.storage.Ledger;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.List;
import java.util.UUID;

/**
 * {@code /board} —— 玩家委托板（R30）。
 *
 * <p>发布：赏金 + 手续费一次性扣掉；接单：登记接单者；交付：校验并销毁物品后把赏金打给接单者；
 * 超时未接单：退赏金不退手续费（{@link BoardModule#expireNow()}）。
 */
public final class BoardCommands {

	private BoardCommands() {
	}

	public static void register(CommandDispatcher<ServerCommandSource> d) {
		CommandTable.declare(d, "board", "board");
		d.register(CommandManager.literal("board")
				.requires(src -> Permissions.has(src, Permissions.NODE_USE))
				.executes(BoardCommands::open)
				.then(CommandManager.literal("list").executes(BoardCommands::list))
				.then(CommandManager.literal("post")
						.then(CommandManager.argument("item", ItemIdArgument.itemId())
								.then(CommandManager.argument("count", IntegerArgumentType.integer(1, 2304))
										.then(CommandManager.argument("bounty", LongArgumentType.longArg(1, 1_000_000_000_000L))
												.executes(BoardCommands::post)))))
				.then(CommandManager.literal("accept")
						.then(CommandManager.argument("id", StringArgumentType.word()).executes(BoardCommands::accept)))
				.then(CommandManager.literal("deliver")
						.then(CommandManager.argument("id", StringArgumentType.word()).executes(BoardCommands::deliver)))
				.then(CommandManager.literal("cancel")
						.then(CommandManager.argument("id", StringArgumentType.word()).executes(BoardCommands::cancel))));
	}

	/** 无参 = 开可视化委托板（T12）；控制台/无客户端回落到聊天列表。 */
	private static int open(CommandContext<ServerCommandSource> ctx) {
		ServerCommandSource src = ctx.getSource();
		ServerPlayerEntity p = src.getPlayer();
		if (p == null) return list(ctx);
		if (!BoardModule.enabled()) {
			src.sendError(Text.literal(Lang.msg("board.disabled")));
			return 0;
		}
		PageCommands.hintLegacy(p, "board", null);
		com.akira.redtale.economy.net.BoardNetworking.send(p);
		return 1;
	}

	private static int list(CommandContext<ServerCommandSource> ctx) {
		ServerCommandSource src = ctx.getSource();
		ServerPlayerEntity p = src.getPlayer();
  PageCommands.hintLegacy(p, "board", null);
		if (p == null) return onlyPlayer(src);
		if (!BoardModule.enabled()) {
			src.sendError(Text.literal(Lang.msg("board.disabled")));
			return 0;
		}
		List<BoardStore.Entry> all = BoardStore.all();
		int shown = 0;
		src.sendFeedback(() -> Text.literal(Lang.msg("board.header")), false);
		for (BoardStore.Entry e : all) {
			boolean open = BoardStore.Status.OPEN.name().equals(e.status);
			boolean mine = e.poster.equals(p.getUuidAsString()) || e.accepter.equals(p.getUuidAsString());
			if (!open && !mine) continue;
			shown++;
			String line = Lang.msg("board.line", e.id, e.posterName, displayName(e.itemId), e.count,
					BalanceService.format(e.bounty), e.status)
					+ (open ? "" : Lang.msg("board.by", e.accepterName));
			src.sendFeedback(() -> Text.literal(line), false);
		}
		if (shown == 0) src.sendFeedback(() -> Text.literal(Lang.t("board.empty")), false);
		src.sendFeedback(() -> Text.literal(Lang.t("board.hint")), false);
		return 1;
	}

	private static int post(CommandContext<ServerCommandSource> ctx) {
		ServerCommandSource src = ctx.getSource();
		ServerPlayerEntity p = src.getPlayer();
		if (p == null) return onlyPlayer(src);
		if (!BoardModule.enabled()) {
			src.sendError(Text.literal(Lang.msg("board.disabled")));
			return 0;
		}
		String itemId = StringArgumentType.getString(ctx, "item");
		int count = IntegerArgumentType.getInteger(ctx, "count");
		long bounty = LongArgumentType.getLong(ctx, "bounty");
		if (count < 1 || count > BoardConfig.maxCount()) {
			src.sendError(Text.literal(Lang.msg("board.badCount", BoardConfig.maxCount())));
			return 0;
		}
		if (bounty < BoardConfig.minBounty() || bounty > BoardConfig.maxBounty()) {
			src.sendError(Text.literal(Lang.msg("board.badBounty",
					BalanceService.format(BoardConfig.minBounty()), BalanceService.format(BoardConfig.maxBounty()))));
			return 0;
		}
		Item item = item(itemId);
		if (item == null) {
			src.sendError(Text.literal(Lang.msg("board.badItem", itemId)));
			return 0;
		}
		String fullId = Registries.ITEM.getId(item).toString();
		if (BoardConfig.blockedItems().contains(fullId)) {
			src.sendError(Text.literal(Lang.msg("board.blocked", fullId)));
			return 0;
		}
		if (BoardStore.openCountOf(p.getUuidAsString()) >= BoardConfig.maxOpenPerPlayer()) {
			src.sendError(Text.literal(Lang.msg("board.tooMany", BoardConfig.maxOpenPerPlayer())));
			return 0;
		}
		long fee = (long) Math.ceil(bounty * BoardConfig.feePercent() / 100.0);
		long total = bounty + fee;
		if (!BalanceService.take(p.getUuid(), total, Ledger.TYPE_BOARD, "board:post")) {
			src.sendError(Text.literal(Lang.msg("board.noMoney", BalanceService.format(total))));
			return 0;
		}
		BoardStore.Entry e = new BoardStore.Entry();
		e.id = BoardModule.newId();
		e.poster = p.getUuidAsString();
		e.posterName = p.getGameProfile().getName();
		e.itemId = fullId;
		e.count = count;
		e.bounty = bounty;
		e.fee = fee;
		e.createdAt = System.currentTimeMillis();
		e.expiresAt = e.createdAt + BoardConfig.expireHours() * 3600_000L;
		e.status = BoardStore.Status.OPEN.name();
		// ★R129：钱已经扣了，落盘失败必须退款★ 原来 catch 包住 save()，但 save() 从不抛（只吞错打日志）
		// ⇒ 分支永不执行，条目只活在内存里，重启后赏金凭空蒸发。
		if (!BoardStore.addChecked(e)) {
			BalanceService.add(p.getUuid(), total, Ledger.TYPE_BOARD, "board:post:refund");
			src.sendError(Text.literal(Lang.msg("board.postFail")));
			return 0;
		}
		src.sendFeedback(() -> Text.literal(Lang.msg("board.posted", e.id, displayName(e.itemId), e.count,
				BalanceService.format(e.bounty), BalanceService.format(fee))), false);
		return 1;
	}

	private static int accept(CommandContext<ServerCommandSource> ctx) {
		ServerCommandSource src = ctx.getSource();
		ServerPlayerEntity p = src.getPlayer();
		if (p == null) return onlyPlayer(src);
		// R110：关模块只摘得掉命令根，摘不掉「已经在跑的东西」；接单/交付/撤销这三个会动钱，
		// 判据必须贴在动钱那一刻，不许假定「命令还在 ⇒ 模块还开着」。
		if (!BoardModule.enabled()) {
			src.sendError(Text.literal(Lang.msg("board.disabled")));
			return 0;
		}
		BoardStore.Entry e = BoardStore.byId(StringArgumentType.getString(ctx, "id"));
		if (e == null || !BoardStore.Status.OPEN.name().equals(e.status)) {
			src.sendError(Text.literal(Lang.msg("board.notOpen")));
			return 0;
		}
		if (e.poster.equals(p.getUuidAsString())) {
			src.sendError(Text.literal(Lang.msg("board.ownPost")));
			return 0;
		}
		e.status = BoardStore.Status.ACCEPTED.name();
		e.accepter = p.getUuidAsString();
		e.accepterName = p.getGameProfile().getName();
		BoardStore.save();
		src.sendFeedback(() -> Text.literal(Lang.msg("board.accepted", e.id,
				displayName(e.itemId), e.count, BalanceService.format(e.bounty))), false);
		if (BoardConfig.notifyOnAccept()) {
			try {
				ServerPlayerEntity poster = RedtaleEconomyServerPlayer.of(UUID.fromString(e.poster));
				if (poster != null) {
					poster.sendMessage(Text.literal(Lang.msg("board.posterNotice", e.accepterName, e.id)), false);
				}
			} catch (Exception ignored) {
				// 发布者不在线就不提示
			}
		}
		return 1;
	}

	private static int deliver(CommandContext<ServerCommandSource> ctx) {
		ServerCommandSource src = ctx.getSource();
		ServerPlayerEntity p = src.getPlayer();
		if (p == null) return onlyPlayer(src);
		if (!BoardModule.enabled()) {
			src.sendError(Text.literal(Lang.msg("board.disabled")));
			return 0;
		}
		BoardStore.Entry e = BoardStore.byId(StringArgumentType.getString(ctx, "id"));
		if (e == null || !BoardStore.Status.ACCEPTED.name().equals(e.status)) {
			src.sendError(Text.literal(Lang.msg("board.notAccepted")));
			return 0;
		}
		if (!e.accepter.equals(p.getUuidAsString())) {
			src.sendError(Text.literal(Lang.msg("board.notYours")));
			return 0;
		}
		Item item = item(e.itemId);
		if (item == null) {
			src.sendError(Text.literal(Lang.msg("board.badItem", e.itemId)));
			return 0;
		}
		if (countOf(p, item) < e.count) {
			src.sendError(Text.literal(Lang.msg("board.missing", displayName(e.itemId), e.count)));
			return 0;
		}
		// ★R129：顺序 = 落盘状态 → 销毁物品 → 发赏金★
		// 原来「销毁物品 → save()（void，吞 IO 错）→ 发钱」：保存失败时条目重启后回到 ACCEPTED，
		// 同一个人可以再交一次拿第二份赏金；且物品已销毁、发钱抛异常时没人退。
		com.google.gson.JsonObject payload = new com.google.gson.JsonObject();
		payload.addProperty("id", e.id);
		payload.addProperty("itemId", e.itemId);
		payload.addProperty("count", e.count);
		payload.addProperty("bounty", e.bounty);
		com.akira.redtale.economy.journal.TxnJournal.Txn txn =
				com.akira.redtale.economy.journal.TxnJournal.begin(
						com.akira.redtale.economy.journal.TxnJournal.BOARD_DELIVER,
						p.getUuid(), payload);

		String prevStatus = e.status;
		e.status = BoardStore.Status.DONE.name();
		if (!BoardStore.saveChecked()) {
			e.status = prevStatus;
			txn.dismiss();
			src.sendError(Text.literal(Lang.msg("market.err.saveFail")));
			return 0;
		}

		// ★R132：扣了多少必须可回读★ 前置 countOf 只保证「扣之前够」，扣不动时原来照样发满额赏金。
		int removed = removeFrom(p, item, e.count);
		if (removed != e.count) {
			// 少扣 ⇒ 把已扣的还回去、条目退回 ACCEPTED（可原样重试），不发赏金
			if (removed > 0) giveBack(p, item, removed);
			e.status = prevStatus;
			BoardStore.saveChecked();
			txn.dismiss();
			RedtaleEconomy.LOGGER.error("[board] 交付扣物不足，已回滚：id={} need={} got={}",
					e.id, e.count, removed);
			src.sendError(Text.literal(Lang.msg("market.err.saveFail")));
			return 0;
		}
		txn.state("taken");
		try {
			// 赏金在发布时已扣，这里只发给接单者；物品直接销毁（不返还发布者，防自买自卖刷钱）
			BalanceService.add(p.getUuid(), e.bounty, Ledger.TYPE_BOARD, "board:deliver:" + e.id);
		} catch (Throwable t) {
			// 赏金没发出去 ⇒ 物品还给交付者、条目退回 ACCEPTED，可原样重试
			giveBack(p, item, e.count);
			e.status = prevStatus;
			BoardStore.saveChecked();
			txn.dismiss();
			RedtaleEconomy.LOGGER.error("[board] 交付发赏金失败，已退还物品：{}", t.toString(), t);
			src.sendError(Text.literal(Lang.msg("market.err.saveFail")));
			return 0;
		}
		txn.state("paid");
		txn.commit();
		src.sendFeedback(() -> Text.literal(Lang.msg("board.delivered", e.id,
				BalanceService.format(e.bounty))), false);
		return 1;
	}

	private static int cancel(CommandContext<ServerCommandSource> ctx) {
		ServerCommandSource src = ctx.getSource();
		ServerPlayerEntity p = src.getPlayer();
		if (p == null) return onlyPlayer(src);
		if (!BoardModule.enabled()) {
			src.sendError(Text.literal(Lang.msg("board.disabled")));
			return 0;
		}
		BoardStore.Entry e = BoardStore.byId(StringArgumentType.getString(ctx, "id"));
		if (e == null || !BoardStore.Status.OPEN.name().equals(e.status)) {
			src.sendError(Text.literal(Lang.msg("board.cannotCancel")));
			return 0;
		}
		if (!e.poster.equals(p.getUuidAsString())) {
			src.sendError(Text.literal(Lang.msg("board.notYours")));
			return 0;
		}
		// ★R129：状态没落盘成功就不退款★ 否则内存里 CANCELLED、磁盘上还是 OPEN，
		// 重启后条目复活，可以再撤一次 = 每重启一次领一次赏金。
		String prevStatus = e.status;
		e.status = BoardStore.Status.CANCELLED.name();
		if (!BoardStore.saveChecked()) {
			e.status = prevStatus;
			src.sendError(Text.literal(Lang.msg("market.err.saveFail")));
			return 0;
		}
		try {
			BalanceService.add(p.getUuid(), e.bounty, Ledger.TYPE_BOARD, "board:cancel:" + e.id);
		} catch (Throwable t) {
			// 退款失败 ⇒ 条目退回 OPEN，避免「单子没了、钱也没了」
			e.status = prevStatus;
			BoardStore.saveChecked();
			RedtaleEconomy.LOGGER.error("[board] 撤销退款失败，已恢复挂单：{}", t.toString(), t);
			src.sendError(Text.literal(Lang.msg("market.err.saveFail")));
			return 0;
		}
		src.sendFeedback(() -> Text.literal(Lang.msg("board.cancelled", e.id,
				BalanceService.format(e.bounty), BalanceService.format(e.fee))), false);
		return 1;
	}

	// ---------------------------------------------------------------- 工具

	private static int countOf(ServerPlayerEntity p, Item item) {
		int n = 0;
		for (int i = 0; i < p.getInventory().size(); i++) {
			ItemStack st = p.getInventory().getStack(i);
			if (!st.isEmpty() && st.getItem() == item) n += st.getCount();
		}
		return n;
	}

	/** 交付失败回填：先塞背包，塞不下就丢在脚下（不允许凭空消失）。 */
	private static void giveBack(ServerPlayerEntity p, Item item, int count) {
		if (item == null || count <= 0) return;
		ItemStack back = new ItemStack(item, count);
		p.getInventory().insertStack(back);
		if (!back.isEmpty()) p.dropItem(back, false);
		p.getInventory().markDirty();
	}

	/** 从背包扣物，<b>返回实际扣掉的数量</b>（R132：调用方必须核对差额，别再靠前置 countOf 兜）。 */
	private static int removeFrom(ServerPlayerEntity p, Item item, int count) {
		if (item == null || count <= 0) return 0;
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
		return count - left;
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
		Item it = item(itemId);
		return it == null ? itemId : new ItemStack(it).getName().getString();
	}

	private static int onlyPlayer(ServerCommandSource src) {
		src.sendError(Text.literal(Lang.t("cmd.onlyPlayer")));
		return 0;
	}

	/** 按 uuid 找在线玩家（发提示用）。 */
	private static final class RedtaleEconomyServerPlayer {
		static ServerPlayerEntity of(UUID uuid) {
			var server = com.akira.redtale.economy.RedtaleEconomy.server();
			return server == null ? null : server.getPlayerManager().getPlayer(uuid);
		}
	}
}
