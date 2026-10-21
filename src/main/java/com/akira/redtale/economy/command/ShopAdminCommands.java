package com.akira.redtale.economy.command;

import com.akira.redtale.economy.perm.Permissions;
import com.akira.redtale.economy.config.CoreConfig;
import com.akira.redtale.economy.lang.Lang;
import com.akira.redtale.economy.net.ShopNetworking;
import com.akira.redtale.economy.shop.ShopCatalog;
import com.akira.redtale.economy.shop.ShopStock;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.command.CommandSource;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.util.List;

/**
 * {@code /shop ...}（玩家 GUI 入口 + 管理命令）。
 *
 * <pre>
 * /shop                       打开第一个商店（有多个则列出）
 * /shop open &#60;shopId&#62;         打开指定商店
 * /shop list                  列出已加载商店与商品数
 * /shop reload                重读 shop/shops 下全部文件
 * /shop admin price  &#60;shop&#62; &#60;item&#62; &#60;buy&#62; &#60;sell&#62;
 * /shop admin toggle &#60;shop&#62; &#60;item&#62;
 * /shop admin stock  &#60;shop&#62; &#60;item&#62; &#60;n&#62;   （-1=恢复无限）
 * /shop admin resetstock      清空全部库存覆盖值，回到文件初值
 * </pre>
 *
 * 权限：玩家侧只要求能进服；管理命令走 {@code permissions.admin}（默认 OP 2）。
 */
public final class ShopAdminCommands {

	private ShopAdminCommands() {
	}

	public static void register(CommandDispatcher<ServerCommandSource> d) {
		d.register(CommandManager.literal("shop").requires(src -> Permissions.has(src, Permissions.NODE_USE))
				.executes(ShopAdminCommands::openDefault)
				.then(CommandManager.literal("open")
						.then(CommandManager.argument("shopId", StringArgumentType.word())
								.suggests(shops())
								.executes(ShopAdminCommands::open)))
				.then(CommandManager.literal("list")
						.executes(ShopAdminCommands::list))
				.then(CommandManager.literal("reload")
						.requires(ShopAdminCommands::isAdmin)
						.executes(ShopAdminCommands::reload))
				.then(CommandManager.literal("admin")
						.requires(ShopAdminCommands::isAdmin)
						.then(CommandManager.literal("price")
								.then(CommandManager.argument("shopId", StringArgumentType.word())
										.suggests(shops())
										.then(CommandManager.argument("itemId", ItemIdArgument.itemId())
												.suggests(items())
												.then(CommandManager.argument("buy", LongArgumentType.longArg(0))
														.then(CommandManager.argument("sell",
																LongArgumentType.longArg(0))
																.executes(ShopAdminCommands::price))))))
						.then(CommandManager.literal("toggle")
								.then(CommandManager.argument("shopId", StringArgumentType.word())
										.suggests(shops())
										.then(CommandManager.argument("itemId", ItemIdArgument.itemId())
												.suggests(items())
												.executes(ShopAdminCommands::toggle))))
						.then(CommandManager.literal("stock")
								.then(CommandManager.argument("shopId", StringArgumentType.word())
										.suggests(shops())
										.then(CommandManager.argument("itemId", ItemIdArgument.itemId())
												.suggests(items())
												.then(CommandManager.argument("count",
														IntegerArgumentType.integer(-1))
														.executes(ShopAdminCommands::stock)))))
						.then(CommandManager.literal("addshop")
								.then(CommandManager.argument("shopId", StringArgumentType.word())
										.executes(ctx -> addShop(ctx, null))
										.then(CommandManager.argument("displayName",
														StringArgumentType.greedyString())
												.executes(ctx -> addShop(ctx,
														StringArgumentType.getString(ctx, "displayName"))))))
						.then(CommandManager.literal("add")
								.then(CommandManager.argument("shopId", StringArgumentType.word())
										.suggests(shops())
										.then(CommandManager.argument("itemId", ItemIdArgument.itemId())
												.then(CommandManager.argument("buy", LongArgumentType.longArg(0))
														.then(CommandManager.argument("sell",
																		LongArgumentType.longArg(0))
																.executes(ShopAdminCommands::addItem))))))
						.then(CommandManager.literal("resetstock")
								.executes(ShopAdminCommands::resetStock))
						// E1：查看/切换价格护栏（卖价必须低于买价）
						.then(CommandManager.literal("priceguard")
								.executes(ShopAdminCommands::priceGuard)
								.then(CommandManager.literal("on")
										.executes(ctx -> setGuard(ctx, true)))
								.then(CommandManager.literal("off")
										.executes(ctx -> setGuard(ctx, false))))
						// U11：搜不到时看索引（id/中文/拼音首字母/全拼四段）
						.then(CommandManager.literal("search")
								.then(CommandManager.argument("shopId", StringArgumentType.word())
										.suggests(shops())
										.then(CommandManager.argument("itemId", StringArgumentType.word())
												.suggests(items())
												.executes(ShopAdminCommands::searchIndex))))
						.then(CommandManager.literal("remove")
								.then(CommandManager.argument("shopId", StringArgumentType.word())
										.suggests(shops())
										.then(CommandManager.argument("itemId", StringArgumentType.word())
												.suggests(items())
												.executes(ShopAdminCommands::removeItem))))
						.then(CommandManager.literal("removeshop")
								.then(CommandManager.argument("shopId", StringArgumentType.word())
										.suggests(shops())
										.executes(ShopAdminCommands::removeShop)))
						.then(CommandManager.literal("edit")
								.executes(ctx -> edit(ctx, null))
								.then(CommandManager.argument("shopId", StringArgumentType.word())
										.suggests(shops())
										.executes(ctx -> edit(ctx, StringArgumentType.getString(ctx, "shopId")))))));
	}

	/** 出票据并提示确认（R25）。 */
	private static int requireConfirm(CommandContext<ServerCommandSource> ctx, String action, String payload) {
		ServerCommandSource src = ctx.getSource();
		ServerPlayerEntity p = src.getPlayer();
		if (p == null) {
			src.sendError(Text.literal("该命令只能由玩家执行（票据要绑玩家）"));
			return 0;
		}
		ConfirmTickets.Ticket t = ConfirmTickets.issue(p.getUuid(), action, payload);
		src.sendFeedback(() -> Text.literal("§e请确认：§f/redtale confirm " + t.code()
				+ " §7（" + ConfirmTickets.ttlSeconds() + " 秒内有效；改主意用 §f/redtale cancel "
				+ t.code() + "§7）"), false);
		return 1;
	}

	// ---------------------------------------------------------------- 删除 → 回收站（R16b）

	private static int removeItem(CommandContext<ServerCommandSource> ctx) {
		ServerCommandSource src = ctx.getSource();
		String shopId = StringArgumentType.getString(ctx, "shopId");
		String itemId = StringArgumentType.getString(ctx, "itemId");
		if (ConfirmTickets.required()) {
			return requireConfirm(ctx, "shop.remove", shopId + "|" + itemId);
		}
		if (!ShopCatalog.removeItem(shopId, itemId)) {
			src.sendError(Text.literal("§c删除失败：商品不存在或文件写不进去（回收站未写入时不删）"));
			return 0;
		}
		src.sendFeedback(() -> Text.literal("§a已删除 " + shopId + "/" + itemId
				+ " §7（定义在 config/redtale/" + ShopCatalog.TRASH_DIR + "/，手工还原）"), true);
		return 1;
	}

	private static int removeShop(CommandContext<ServerCommandSource> ctx) {
		ServerCommandSource src = ctx.getSource();
		String shopId = StringArgumentType.getString(ctx, "shopId");
		if (ConfirmTickets.required()) {
			return requireConfirm(ctx, "shop.removeshop", shopId);
		}
		if (!ShopCatalog.removeShop(shopId)) {
			src.sendError(Text.literal("§c删除商店失败：" + shopId + " 不存在或文件不可写"));
			return 0;
		}
		src.sendFeedback(() -> Text.literal("§a已删除商店 " + shopId
				+ " §7（定义在 config/redtale/" + ShopCatalog.TRASH_DIR + "/）"), true);
		return 1;
	}

	private static boolean isAdmin(ServerCommandSource src) {
		return Permissions.has(src, Permissions.NODE_ADMIN);
	}

	// ---------------------------------------------------------------- 玩家侧

	private static int openDefault(CommandContext<ServerCommandSource> ctx) {
		List<String> ids = ShopCatalog.shopIds();
		ServerPlayerEntity p = ctx.getSource().getPlayer();
		if (p == null) {
			ctx.getSource().sendFeedback(() -> Text.literal("§7商店：" + String.join(", ", ids)), false);
			return 1;
		}
		if (ids.isEmpty()) {
			// 玩家侧失败也走 actionbar：每次 /shop 都会命中，进聊天就是刷屏（2026-10-21 专用服实测）。
			p.sendMessage(Text.literal(Lang.msg("shop.empty")), true);
			return 0;
		}
		if (ids.size() > 1) {
			ctx.getSource().sendFeedback(() -> Text.literal(
					"§7可用商店：§f" + String.join("§7, §f", ids) + " §8（/shop open <id>）"), false);
			return 1;
		}
		return openShop(ctx, ids.get(0));
	}

	/**
	 * {@code /shop admin edit [shopId]}：给管理员下发可视化编辑界面（R16a）。
	 *
	 * <p>界面本身不做校验，所有写入仍走 price/toggle/stock/add/remove 这些命令。
	 */
	private static int edit(CommandContext<ServerCommandSource> ctx, String shopId) {
		ServerCommandSource src = ctx.getSource();
		ServerPlayerEntity p = src.getPlayer();
		if (p == null) {
			src.sendError(Text.literal("该命令只能由玩家执行"));
			return 0;
		}
		String id = shopId;
		if (id == null) {
			List<String> ids = ShopCatalog.shopIds();
			if (ids.isEmpty()) {
				src.sendFeedback(() -> Text.literal(Lang.msg("shop.empty")), false);
				return 0;
			}
			if (ids.size() > 1) {
				src.sendFeedback(() -> Text.literal(
						"§7可用商店：§f" + String.join("§7, §f", ids) + " §8（/shop admin edit <id>）"), false);
				return 1;
			}
			id = ids.get(0);
		}
		com.akira.redtale.economy.net.ShopNetworking.sendOpenEdit(p, id);
		final String shown = id;
		src.sendFeedback(() -> Text.literal("§a已打开编辑界面：§f" + shown), true);
		return 1;
	}

	private static int open(CommandContext<ServerCommandSource> ctx) {
		return openShop(ctx, StringArgumentType.getString(ctx, "shopId"));
	}

	private static int openShop(CommandContext<ServerCommandSource> ctx, String shopId) {
		ServerPlayerEntity p = ctx.getSource().getPlayer();
		if (p == null) {
			ctx.getSource().sendError(Text.literal(Lang.msg("cmd.onlyPlayer")));
			return 0;
		}
		if (!ShopCatalog.shopIds().contains(shopId)) {
			ctx.getSource().sendError(Text.literal(Lang.msg("shop.notFoundShop", shopId)));
			return 0;
		}
		ShopNetworking.sendOpen(p, shopId);
		return 1;
	}

	private static int list(CommandContext<ServerCommandSource> ctx) {
		List<String> ids = ShopCatalog.shopIds();
		if (ids.isEmpty()) {
			ctx.getSource().sendFeedback(() -> Text.literal(Lang.msg("shop.empty")), false);
			return 0;
		}
		StringBuilder sb = new StringBuilder("§7商店（" + ids.size() + "）：\n");
		for (String id : ids) {
			ShopCatalog.ShopDef def = ShopCatalog.shop(id);
			sb.append("§8- §f").append(id).append(" §7(")
					.append(def == null ? 0 : def.items().size()).append(" 件)")
					.append(def != null && def.requirePremium() ? " §d[premium]" : "")
					.append('\n');
		}
		String out = sb.toString();
		ctx.getSource().sendFeedback(() -> Text.literal(out), false);
		return 1;
	}

	private static int reload(CommandContext<ServerCommandSource> ctx) {
		ShopCatalog.reload();
		ShopStock.init();
		ctx.getSource().sendFeedback(() -> Text.literal(
				"§a商店已重载：" + ShopCatalog.shopIds().size() + " 个 / 库存覆盖 "
						+ ShopStock.size() + " 条"), true);
		return 1;
	}

	// ---------------------------------------------------------------- 管理

	private static int price(CommandContext<ServerCommandSource> ctx) {
		String shopId = StringArgumentType.getString(ctx, "shopId");
		String itemId = StringArgumentType.getString(ctx, "itemId");
		long buy = LongArgumentType.getLong(ctx, "buy");
		long sell = LongArgumentType.getLong(ctx, "sell");
		if (ShopCatalog.item(shopId, itemId) == null) {
			ctx.getSource().sendError(Text.literal(Lang.msg("shop.notFoundItem", itemId)));
			return 0;
		}
		String bad = ShopCatalog.updateItem(shopId, itemId, buy, sell, null, null);
		if (bad != null) {
			// E1：价格护栏拒绝时把「为什么」直接说清楚，不要只说写入失败
			ctx.getSource().sendError(Text.literal("§c已拒绝：" + bad));
			return 0;
		}
		ctx.getSource().sendFeedback(() -> Text.literal("§a已改价：§f" + shopId + "/" + itemId
				+ " §7买 " + buy + " / 卖 " + sell), true);
		return 1;
	}

	private static int toggle(CommandContext<ServerCommandSource> ctx) {
		String shopId = StringArgumentType.getString(ctx, "shopId");
		String itemId = StringArgumentType.getString(ctx, "itemId");
		ShopCatalog.ItemDef it = ShopCatalog.item(shopId, itemId);
		if (it == null) {
			ctx.getSource().sendError(Text.literal(Lang.msg("shop.notFoundItem", itemId)));
			return 0;
		}
		boolean next = !it.enabled();
		String bad = ShopCatalog.updateItem(shopId, itemId, null, null, null, next);
		if (bad != null) {
			ctx.getSource().sendError(Text.literal("§c已拒绝：" + bad));
			return 0;
		}
		ctx.getSource().sendFeedback(() -> Text.literal("§a" + itemId + " → "
				+ (next ? "§a上架" : "§c下架")), true);
		return 1;
	}

	private static int stock(CommandContext<ServerCommandSource> ctx) {
		String shopId = StringArgumentType.getString(ctx, "shopId");
		String itemId = StringArgumentType.getString(ctx, "itemId");
		int count = IntegerArgumentType.getInteger(ctx, "count");
		ShopCatalog.ItemDef it = ShopCatalog.item(shopId, itemId);
		if (it == null) {
			ctx.getSource().sendError(Text.literal(Lang.msg("shop.notFoundItem", itemId)));
			return 0;
		}
		String bad = ShopCatalog.updateItem(shopId, itemId, null, null, (long) count, null);
		if (bad != null) {
			ctx.getSource().sendError(Text.literal("§c已拒绝：" + bad));
			return 0;
		}
		ctx.getSource().sendFeedback(() -> Text.literal("§a库存已设为 " + (count < 0 ? "无限" : String.valueOf(count))
				+ "：§f" + shopId + "/" + itemId), true);
		return 1;
	}

	private static int addShop(CommandContext<ServerCommandSource> ctx, String displayName) {
		String shopId = StringArgumentType.getString(ctx, "shopId");
		if (!ShopCatalog.createShop(shopId, displayName)) {
			ctx.getSource().sendError(Text.literal("§c商店已存在或 id 非法：§f" + shopId));
			return 0;
		}
		ctx.getSource().sendFeedback(() -> Text.literal("§a已创建空商店 §f" + shopId
				+ "§7（文件 shop/shops/" + shopId + ".json，用 /shop admin add 加商品）"), true);
		return 1;
	}

	private static int addItem(CommandContext<ServerCommandSource> ctx) {
		String shopId = StringArgumentType.getString(ctx, "shopId");
		String itemId = StringArgumentType.getString(ctx, "itemId");
		long buy = LongArgumentType.getLong(ctx, "buy");
		long sell = LongArgumentType.getLong(ctx, "sell");
		String bad = ShopCatalog.addItem(shopId, itemId, buy, sell, -1);
		if (bad != null) {
			ctx.getSource().sendError(Text.literal("§c已拒绝：" + bad));
			return 0;
		}
		ctx.getSource().sendFeedback(() -> Text.literal("§a已添加 §f" + itemId + " §7→ §f" + shopId
				+ " §7(买 " + buy + " / 卖 " + sell + "，无限库存)"), true);
		return 1;
	}

	/** E1：打印护栏状态 + 当前 100 铜买价对应的最高卖价。 */
	/** U11：《/rt admin shop search <店> <物品>》把搜索索引打出来，排查「为什么搜不到」。 */
	private static int searchIndex(CommandContext<ServerCommandSource> ctx) {
		String shopId = StringArgumentType.getString(ctx, "shopId");
		String itemId = StringArgumentType.getString(ctx, "itemId");
		String desc = ShopCatalog.describeSearch(shopId, itemId);
		ctx.getSource().sendFeedback(() -> Text.literal("§7搜索索引（id | 中文 | 首字母 | 全拼）：§f" + desc), false);
		return 1;
	}

	private static int priceGuard(CommandContext<ServerCommandSource> ctx) {
		boolean on = com.akira.redtale.economy.shop.PricePolicy.enabled();
		int margin = com.akira.redtale.economy.shop.PricePolicy.marginPercent();
		ctx.getSource().sendFeedback(() -> Text.literal(on
				? Lang.t("shop.priceGuardOn", margin)
				: Lang.t("shop.priceGuardOff")), false);
		if (on) {
			long sample = com.akira.redtale.economy.shop.PricePolicy.maxSellFor(100);
			ctx.getSource().sendFeedback(() -> Text.literal(
					Lang.t("shop.priceGuardHint", sample)), false);
			// 文件里已经写错的（加载时被内存钳制）—— 运维要知道有几件、先改哪个
			int bad = ShopCatalog.priceViolations();
			String one = ShopCatalog.priceViolationSample();
			ctx.getSource().sendFeedback(() -> Text.literal(bad == 0
					? "§a本次加载无价格违规"
					: "§e本次加载钳制 §f" + bad + " §e件（例：§f" + one + "§e），建议直接改文件"), false);
		}
		return 1;
	}

	/** 切换护栏（写 shop/settings.json，立即生效）。 */
	private static int setGuard(CommandContext<ServerCommandSource> ctx, boolean on) {
		var st = com.akira.redtale.economy.config.ShopConfig.store();
		st.setPath("limits.enforceSellBelowBuy", on);
		st.save(true);
		ctx.getSource().sendFeedback(() -> Text.literal(on
				? Lang.t("shop.priceGuardOn", com.akira.redtale.economy.shop.PricePolicy.marginPercent())
				: Lang.t("shop.priceGuardOff")), true);
		return 1;
	}

	private static int resetStock(CommandContext<ServerCommandSource> ctx) {
		ShopStock.resetAll();
		ctx.getSource().sendFeedback(() -> Text.literal("§a库存覆盖已清空，全部回到文件初值"), true);
		return 1;
	}

	// ---------------------------------------------------------------- 补全

	private static com.mojang.brigadier.suggestion.SuggestionProvider<ServerCommandSource> shops() {
		return (ctx, b) -> CommandSource.suggestMatching(ShopCatalog.shopIds(), b);
	}

	private static com.mojang.brigadier.suggestion.SuggestionProvider<ServerCommandSource> items() {
		return (ctx, b) -> {
			try {
				String shopId = StringArgumentType.getString(ctx, "shopId");
				ShopCatalog.ShopDef def = ShopCatalog.shop(shopId);
				if (def == null) return b.buildFuture();
				return CommandSource.suggestMatching(
						def.items().stream().map(ShopCatalog.ItemDef::id).toList(), b);
			} catch (Exception e) {
				return b.buildFuture();
			}
		};
	}
}
