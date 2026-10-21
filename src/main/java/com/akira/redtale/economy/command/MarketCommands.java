package com.akira.redtale.economy.command;

import com.akira.redtale.economy.config.MarketConfig;
import com.akira.redtale.economy.economy.BalanceService;
import com.akira.redtale.economy.lang.Lang;
import com.akira.redtale.economy.market.MarketRules;
import com.akira.redtale.economy.market.MarketService;
import com.akira.redtale.economy.market.MarketStore;
import com.akira.redtale.economy.module.impl.MarketModule;
import com.akira.redtale.economy.perm.Permissions;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.item.ItemStack;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.util.List;

/**
 * {@code /market} —— 玩家市场（R40）。主入口是 Hub 内的「市场」页（{@code /rt market}），
 * 这里的子命令是给「不想开界面」和运维排查用的等价通道。
 *
 * <p>反馈口径（§38）：成功/失败都走 actionbar（Hub 内命令不刷聊天）；
 * 只有列表这类「需要回头翻」的多行结果才进聊天。
 */
public final class MarketCommands {

	private MarketCommands() {
	}

	public static void register(CommandDispatcher<ServerCommandSource> d) {
		CommandTable.declare(d, "market", "market");
		d.register(CommandManager.literal("market")
				.requires(src -> Permissions.has(src, Permissions.NODE_USE))
				.executes(ctx -> {
					ServerPlayerEntity p = ctx.getSource().getPlayer();
					if (p == null) return onlyPlayer(ctx);
					PageCommands.hintLegacy(p, "market", "market");
					return PageCommands.open(p, "market");
				})
				.then(CommandManager.literal("sell")
						.then(CommandManager.argument("price", LongArgumentType.longArg(0, 1_000_000_000_000L))
								.executes(MarketCommands::sell)))
				.then(CommandManager.literal("buy")
						.then(CommandManager.argument("id", StringArgumentType.word())
								.executes(MarketCommands::buy)))
				.then(CommandManager.literal("cancel")
						.then(CommandManager.argument("id", StringArgumentType.word())
								.executes(MarketCommands::cancel)))
				.then(CommandManager.literal("mine").executes(MarketCommands::mine))
				.then(CommandManager.literal("list")
						.executes(ctx -> list(ctx, 0))
						.then(CommandManager.argument("page", IntegerArgumentType.integer(1, 1000))
								.executes(ctx -> list(ctx, IntegerArgumentType.getInteger(ctx, "page") - 1))))
				.then(CommandManager.literal("auction")
						.executes(MarketCommands::auctionHelp)
						.then(CommandManager.literal("start")
								.then(CommandManager.argument("startPrice",
												LongArgumentType.longArg(0, 1_000_000_000_000L))
										.executes(ctx -> auctionStart(ctx, MarketConfig.auctionHours()))
										.then(CommandManager.argument("hours", IntegerArgumentType.integer(1, 336))
												.executes(ctx -> auctionStart(ctx,
														IntegerArgumentType.getInteger(ctx, "hours"))))))
						.then(CommandManager.literal("list").executes(MarketCommands::auctionList))
						.then(CommandManager.literal("bid")
								.then(CommandManager.argument("id", StringArgumentType.word())
										.then(CommandManager.argument("amount",
														LongArgumentType.longArg(1, 1_000_000_000_000L))
												.executes(MarketCommands::auctionBid)))))
				.then(CommandManager.literal("expire")
						.requires(src -> Permissions.has(src, Permissions.NODE_ADMIN))
						.executes(ctx -> expire(ctx, null))));
	}

	// ---------------------------------------------------------------- 子命令

	private static int sell(CommandContext<ServerCommandSource> ctx) {
		ServerCommandSource src = ctx.getSource();
		ServerPlayerEntity p = src.getPlayer();
		if (p == null) return onlyPlayer(ctx);
		long price = LongArgumentType.getLong(ctx, "price");
		MarketService.Result r = MarketService.list(p, price);
		if (r.ok()) {
			Feedback.success(src, Text.literal(r.message()));
		} else {
			Feedback.success(src, Text.literal("§c" + r.message()));
		}
		return r.ok() ? 1 : 0;
	}

	private static int buy(CommandContext<ServerCommandSource> ctx) {
		ServerCommandSource src = ctx.getSource();
		ServerPlayerEntity p = src.getPlayer();
		if (p == null) return onlyPlayer(ctx);
		MarketService.Result r = MarketService.buy(p, StringArgumentType.getString(ctx, "id"));
		if (r.ok()) {
			Feedback.success(src, Text.literal(r.message()));
		} else {
			Feedback.error(src, Text.literal(r.message()));
		}
		return r.ok() ? 1 : 0;
	}

	private static int cancel(CommandContext<ServerCommandSource> ctx) {
		ServerCommandSource src = ctx.getSource();
		ServerPlayerEntity p = src.getPlayer();
		if (p == null) return onlyPlayer(ctx);
		MarketService.Result r = MarketService.cancel(p, StringArgumentType.getString(ctx, "id"));
		if (r.ok()) {
			Feedback.success(src, Text.literal(r.message()));
		} else {
			Feedback.error(src, Text.literal(r.message()));
		}
		return r.ok() ? 1 : 0;
	}

	private static int mine(CommandContext<ServerCommandSource> ctx) {
		ServerCommandSource src = ctx.getSource();
		ServerPlayerEntity p = src.getPlayer();
		if (p == null) return onlyPlayer(ctx);
		List<MarketStore.Entry> list = MarketService.query(p, MarketService.Filter.MINE, "");
		Feedback.list(src, Text.literal(Lang.msg("market.mine.header", list.size(),
				MarketConfig.maxOpenPerPlayer())));
		for (MarketStore.Entry e : list) {
			Feedback.list(src, Text.literal(line(e, true)));
		}
		if (list.isEmpty()) Feedback.list(src, Text.literal(Lang.t("market.empty")));
		return 1;
	}

	private static int list(CommandContext<ServerCommandSource> ctx, int page) {
		ServerCommandSource src = ctx.getSource();
		ServerPlayerEntity p = src.getPlayer();
		List<MarketStore.Entry> all = MarketService.query(p, MarketService.Filter.ALL, "");
		int size = Math.max(1, MarketConfig.pageSize());
		int pages = MarketRules.totalPages(all.size(), size);
		int pg = MarketRules.clampPage(page, all.size(), size);
		Feedback.list(src, Text.literal(Lang.msg("market.header", pg + 1, Math.max(1, pages), all.size())));
		for (int i = pg * size; i < Math.min(all.size(), (pg + 1) * size); i++) {
			Feedback.list(src, Text.literal(line(all.get(i), p != null
					&& all.get(i).seller.equals(p.getUuidAsString()))));
		}
		if (all.isEmpty()) Feedback.list(src, Text.literal(Lang.t("market.empty")));
		Feedback.list(src, Text.literal(Lang.msg("market.hint",
				BalanceService.format(MarketConfig.minPrice()),
				BalanceService.format(MarketConfig.maxPrice()))));
		return 1;
	}

	private static int auctionHelp(CommandContext<ServerCommandSource> ctx) {
		Feedback.list(ctx.getSource(), Text.literal(Lang.t("market.auction.help")));
		return 1;
	}

	private static int auctionList(CommandContext<ServerCommandSource> ctx) {
		ServerCommandSource src = ctx.getSource();
		ServerPlayerEntity p = src.getPlayer();
		List<MarketStore.Entry> list = MarketService.auctions();
		Feedback.list(src, Text.literal(Lang.msg("market.auction.list.header", list.size())));
		for (MarketStore.Entry e : list) {
			Feedback.list(src, Text.literal(line(e, p != null && e.seller.equals(p.getUuidAsString()))));
		}
		if (list.isEmpty()) Feedback.list(src, Text.literal(Lang.t("market.auction.list.empty")));
		Feedback.list(src, Text.literal(Lang.t("market.auction.help")));
		return 1;
	}

	private static int auctionStart(CommandContext<ServerCommandSource> ctx, int hours) {
		ServerCommandSource src = ctx.getSource();
		ServerPlayerEntity p = src.getPlayer();
		if (p == null) return onlyPlayer(ctx);
		long start = LongArgumentType.getLong(ctx, "startPrice");
		MarketService.Result r = MarketService.startAuction(p, start, hours);
		if (r.ok()) Feedback.success(src, Text.literal(r.message()));
		else Feedback.error(src, Text.literal(r.message()));
		return r.ok() ? 1 : 0;
	}

	private static int auctionBid(CommandContext<ServerCommandSource> ctx) {
		ServerCommandSource src = ctx.getSource();
		ServerPlayerEntity p = src.getPlayer();
		if (p == null) return onlyPlayer(ctx);
		MarketService.Result r = MarketService.bid(p, StringArgumentType.getString(ctx, "id"),
				LongArgumentType.getLong(ctx, "amount"));
		if (r.ok()) Feedback.success(src, Text.literal(r.message()));
		else Feedback.error(src, Text.literal(r.message()));
		return r.ok() ? 1 : 0;
	}

	private static int expire(CommandContext<ServerCommandSource> ctx, ServerPlayerEntity target) {
		ServerCommandSource src = ctx.getSource();
		int n = MarketService.expireNow(src.getServer(), null);
		Feedback.success(src, Text.literal(Lang.msg("market.expired.admin", n)));
		return n;
	}

	// ---------------------------------------------------------------- 工具

	/** 一行：@id 物品 ×数量 —— 价格（卖家，剩余时间）。 */
	private static String line(MarketStore.Entry e, boolean mine) {
		ItemStack st = e.escrowStack();
		String name = st.isEmpty() ? e.itemId : st.getName().getString();
		if (e.isAuction()) {
			String bid = e.bid <= 0 ? "无出价" : (BalanceService.format(e.bid) + " / " + e.bidderName);
			return Lang.msg("market.line.auction", e.id, name, e.count, BalanceService.format(e.price), bid,
					MarketRules.remainingText(MarketRules.remaining(e.expireAt, System.currentTimeMillis())))
					+ (mine ? " §7(我的)" : "");
		}
		return Lang.msg("market.line", e.id, name, e.count, BalanceService.format(e.price), e.sellerName,
				MarketRules.remainingText(MarketRules.remaining(e.expireAt, System.currentTimeMillis())))
				+ (mine ? " §7(我的)" : "");
	}

	private static int onlyPlayer(CommandContext<ServerCommandSource> ctx) {
		ctx.getSource().sendError(Text.literal(Lang.t("cmd.onlyPlayer")));
		return 0;
	}

	/** 供 /rt market 的开关判断复用。 */
	public static boolean enabled() {
		return MarketModule.enabled();
	}
}
