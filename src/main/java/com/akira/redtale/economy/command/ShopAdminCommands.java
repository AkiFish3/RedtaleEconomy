package com.akira.redtale.economy.command;

import com.akira.redtale.economy.perm.Permissions;
import com.akira.redtale.economy.config.CoreConfig;
import com.akira.redtale.economy.lang.Lang;
import com.akira.redtale.economy.net.ShopNetworking;
import com.akira.redtale.economy.shop.ShopBulk;
import com.akira.redtale.economy.shop.ShopCatalog;
import com.akira.redtale.economy.shop.ShopCrossSearch;
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

import java.util.ArrayList;
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
 * /shop admin export &#60;file&#62;            全部商店导出成一份批量文件（T10）
 * /shop admin import &#60;file&#62;            批量导入（合并；同 id 商品按字段覆盖）
 * /shop admin import dry &#60;file&#62;        只算计数不写盘
 * /shop admin import replace &#60;file&#62;    文件里没有的商品从该店移除（写前自动备份）
 * </pre>
 *
 * 权限：玩家侧只要求能进服；管理命令走 {@code permissions.admin}（默认 OP 2）。
 */
public final class ShopAdminCommands {

	private ShopAdminCommands() {
	}

	public static void register(CommandDispatcher<ServerCommandSource> d) {
		CommandTable.declare(d, "shop", "shop");
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
				// T25：跨店搜索（玩家侧功能，不需要管理权限）。greedyString 才能带空格和冒号
				.then(CommandManager.literal("find")
						.then(CommandManager.argument("query", StringArgumentType.greedyString())
								.executes(ShopAdminCommands::find)))
				// 命令面结算：与 GUI 共用 ShopNetworking.trade（同一套库存/限购/税），供无 GUI 场景验收
				.then(CommandManager.literal("buy")
						.then(tradeArgs(true)))
				.then(CommandManager.literal("sell")
						.then(tradeArgs(false)))
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
						// T10：批量导入/导出（搬 2803 件）。路径必须 greedyString：
						// string() 的不带引号段不接受 `/`，`shop/tmp.json` 会解析失败
						.then(CommandManager.literal("export")
								.then(CommandManager.argument("file",
												StringArgumentType.greedyString())
										.suggests(bulkFiles())
										.executes(ShopAdminCommands::exportShops)))
						.then(CommandManager.literal("import")
								.then(CommandManager.literal("dry")
										.then(CommandManager.argument("file",
														StringArgumentType.greedyString())
												.suggests(bulkFiles())
												.executes(ctx -> importShops(ctx, false, true))))
								.then(CommandManager.literal("replace")
										.then(CommandManager.argument("file",
														StringArgumentType.greedyString())
												.suggests(bulkFiles())
												.executes(ctx -> importShops(ctx, true, false)))
										.then(CommandManager.literal("dry")
												.then(CommandManager.argument("file",
																StringArgumentType.greedyString())
														.suggests(bulkFiles())
														.executes(ctx -> importShops(ctx, true, true)))))
								.then(CommandManager.argument("file",
												StringArgumentType.greedyString())
										.suggests(bulkFiles())
										.executes(ctx -> importShops(ctx, false, false))))
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
						// T11：批量上架/批量移除（表格编辑器用；一次读盘一次写盘，id 用逗号或空格分隔）
						.then(CommandManager.literal("batch")
								.then(CommandManager.literal("add")
										.then(CommandManager.argument("shopId", StringArgumentType.word())
												.suggests(shops())
												.then(CommandManager.argument("buy", LongArgumentType.longArg(0))
														.then(CommandManager.argument("sell",
																		LongArgumentType.longArg(0))
																.then(CommandManager.argument("ids",
																				StringArgumentType.greedyString())
																		.executes(ShopAdminCommands::batchAdd))))))
								.then(CommandManager.literal("remove")
										.then(CommandManager.argument("shopId", StringArgumentType.word())
												.suggests(shops())
												.then(CommandManager.argument("ids",
																StringArgumentType.greedyString())
														.executes(ShopAdminCommands::batchRemove)))))
						// T08：限时折扣表诊断 + 热重载
						.then(CommandManager.literal("discount")
								.executes(ShopAdminCommands::discountInfo)
								.then(CommandManager.literal("reload")
										.executes(ShopAdminCommands::discountReload)))
						// T09：今日轮换特价诊断（哪几件、什么时候换、文件名）
						.then(CommandManager.literal("rotation")
								.executes(ShopAdminCommands::rotationInfo)
								.then(CommandManager.argument("shopId", StringArgumentType.word())
										.suggests(shops())
										.executes(ctx -> rotationInfo(ctx, StringArgumentType.getString(ctx, "shopId")))))
						// T13：成交事件投递诊断（监听器数 + 已发布笔数 + 最近一笔）
						.then(CommandManager.literal("events")
								.executes(ShopAdminCommands::eventsInfo))
						.then(CommandManager.literal("edit")
								.executes(ctx -> edit(ctx, null))
								.then(CommandManager.argument("shopId", StringArgumentType.word())
										.suggests(shops())
										.executes(ctx -> edit(ctx, StringArgumentType.getString(ctx, "shopId")))))));
	}

	/** T08：折扣表状态（有几个窗口、现在生效的是哪些、文件在哪）。 */
	private static int discountInfo(CommandContext<ServerCommandSource> ctx) {
		ServerCommandSource src = ctx.getSource();
		try {
			long now = System.currentTimeMillis();
			List<String> active = com.akira.redtale.economy.shop.ShopDiscounts.activeIds(now);
			java.nio.file.Path f = com.akira.redtale.economy.shop.ShopDiscounts.path();
			src.sendFeedback(() -> Text.literal("§6[discount] §7窗口 §f"
					+ com.akira.redtale.economy.shop.ShopDiscounts.windowCount()
					+ " §7| 当前生效 §f" + (active.isEmpty() ? "无" : String.join(", ", active))), false);
			src.sendFeedback(() -> Text.literal("§7文件 §f" + f
					+ (java.nio.file.Files.isRegularFile(f) ? " §a✔" : " §c（不存在，等于无折扣）")), false);
			return 1;
		} catch (Exception e) {
			src.sendError(Text.literal("§c读取折扣表失败：" + e.getMessage()));
			return 0;
		}
	}

	/** T08：热重载折扣表（不动商店数据，避免为了改折扣把整棵 shop 树重扫一遍）。 */
	private static int discountReload(CommandContext<ServerCommandSource> ctx) {
		ServerCommandSource src = ctx.getSource();
		try {
			com.akira.redtale.economy.shop.ShopDiscounts.reload();
			List<String> active = com.akira.redtale.economy.shop.ShopDiscounts.activeIds(System.currentTimeMillis());
			src.sendFeedback(() -> Text.literal("§a[discount] 已重载 §f"
					+ com.akira.redtale.economy.shop.ShopDiscounts.windowCount() + " §a个窗口，当前生效 §f"
					+ (active.isEmpty() ? "无" : String.join(", ", active))), true);
			return 1;
		} catch (Exception e) {
			src.sendError(Text.literal("§c重载失败：" + e.getMessage()));
			return 0;
		}
	}

	/** T09：今日轮换特价诊断。不传 shopId 时打印所有参与店铺的件数。 */
	private static int rotationInfo(CommandContext<ServerCommandSource> ctx) {
		ServerCommandSource src = ctx.getSource();
		boolean on = com.akira.redtale.economy.shop.ShopRotation.enabled();
		src.sendFeedback(() -> Text.literal("§6[rotation] §7" + (on ? "§a已启用" : "§c未启用（shop/rotation.json → enabled）")
				+ " §7件数 §f" + com.akira.redtale.economy.shop.ShopRotation.count()
				+ " §7折扣 §f" + com.akira.redtale.economy.shop.ShopRotation.percent() + "%"
				+ " §7刷新 §f" + com.akira.redtale.economy.shop.ShopRotation.refreshHour() + ":00"), false);
		for (String shopId : ShopCatalog.shopIds()) {
			if (!com.akira.redtale.economy.shop.ShopRotation.coversShop(shopId)) continue;
			int n = rotationItems(shopId).size();
			src.sendFeedback(() -> Text.literal("§7- §f" + shopId + " §7今日特价 §f" + n + " §7件"), false);
		}
		return 1;
	}

	/** T13：成交事件投递诊断 —— 监听器数 / 已发布笔数 / 最近一笔。 */
	private static int eventsInfo(CommandContext<ServerCommandSource> ctx) {
		ServerCommandSource src = ctx.getSource();
		src.sendFeedback(() -> Text.literal("§6[shop.events] §7监听器 §f"
				+ com.akira.redtale.economy.shop.ShopEvents.listenerCount()
				+ " §7| 本次启动已发布 §f" + com.akira.redtale.economy.shop.ShopEvents.firedCount() + " §7笔"), false);
		com.akira.redtale.economy.shop.ShopEvents.Transaction last =
				com.akira.redtale.economy.shop.ShopEvents.last();
		if (last == null) {
			src.sendFeedback(() -> Text.literal("§7最近一笔：§8（无）"), false);
		} else {
			src.sendFeedback(() -> Text.literal("§7最近一笔：§f" + (last.buy() ? "买入" : "卖出")
					+ " §f" + last.itemId() + " §7x§f" + last.count()
					+ " §7@ §f" + last.shopId()
					+ " §7单价 §f" + last.unitPrice() + " §7合计 §f" + last.total()
					+ " §7余额 §f" + last.balanceAfter()), false);
		}
		return 1;
	}

	private static int rotationInfo(CommandContext<ServerCommandSource> ctx, String shopId) {
		ServerCommandSource src = ctx.getSource();
		List<String> ids = rotationItems(shopId);
		src.sendFeedback(() -> Text.literal("§6[rotation] §f" + shopId + " §7今日 §f" + ids.size() + " §7件"), false);
		String joined = String.join("§7, §f", ids.stream().limit(20).toList());
		src.sendFeedback(() -> Text.literal("§f" + joined), false);
		return 1;
	}

	/** 复算今日特价（与 ShopCatalog 用同一套入参，便于对账）。 */
	private static List<String> rotationItems(String shopId) {
		ShopCatalog.ShopDef def = ShopCatalog.shop(shopId);
		if (def == null) return List.of();
		List<String> pool = new ArrayList<>();
		for (ShopCatalog.ItemDef it : def.items()) {
			if (it.enabled() && it.buy() > 0) pool.add(it.id());
		}
		long day = com.akira.redtale.economy.shop.ShopRotation.dayIndex(
				System.currentTimeMillis(), com.akira.redtale.economy.shop.ShopRotation.refreshHour());
		return new ArrayList<>(com.akira.redtale.economy.shop.ShopRotation.pick(
				pool, day, com.akira.redtale.economy.shop.ShopRotation.count(),
				com.akira.redtale.economy.shop.ShopRotation.salt()));
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

	// ---------------------------------------------------------------- T11 批量上架/移除

	private static int batchAdd(CommandContext<ServerCommandSource> ctx) {
		ServerCommandSource src = ctx.getSource();
		String shopId = StringArgumentType.getString(ctx, "shopId");
		long buy = LongArgumentType.getLong(ctx, "buy");
		long sell = LongArgumentType.getLong(ctx, "sell");
		List<String> ids = com.akira.redtale.economy.shop.BatchIds
				.parse(StringArgumentType.getString(ctx, "ids"));
		if (ids.isEmpty()) {
			src.sendError(Text.literal("§c没有可上架的 id（逗号或空格分隔）"));
			return 0;
		}
		ShopCatalog.BatchAddReport rep = ShopCatalog.addItems(shopId, ids, buy, sell);
		if (rep.added() == 0 && !rep.rejected().isEmpty()) {
			src.sendError(Text.literal("§c整批被拒绝：" + String.join("；", rep.rejected())));
			return 0;
		}
		src.sendFeedback(() -> Text.literal("§a批量上架 §f" + rep.added() + " §a件 → §f" + shopId
				+ (rep.existing().isEmpty() ? "" : " §7（已存在跳过 " + rep.existing().size() + "）")
				+ (rep.rejected().isEmpty() ? "" : " §c（拒绝 " + rep.rejected().size() + "：" 
						+ String.join("；", rep.rejected()) + "）")), true);
		return rep.added() > 0 ? 1 : 0;
	}

	private static int batchRemove(CommandContext<ServerCommandSource> ctx) {
		ServerCommandSource src = ctx.getSource();
		String shopId = StringArgumentType.getString(ctx, "shopId");
		List<String> ids = com.akira.redtale.economy.shop.BatchIds
				.parse(StringArgumentType.getString(ctx, "ids"));
		if (ids.isEmpty()) {
			src.sendError(Text.literal("§c没有可移除的 id（逗号或空格分隔）"));
			return 0;
		}
		if (ConfirmTickets.required()) {
			return requireConfirm(ctx, "shop.remove.batch",
					shopId + "|" + com.akira.redtale.economy.shop.BatchIds.join(ids));
		}
		ShopCatalog.BatchRemoveReport rep = ShopCatalog.removeItems(shopId, ids);
		if (rep.removed() == 0) {
			src.sendError(Text.literal("§c一件都没删："
					+ (rep.missing().isEmpty() ? "文件不可写" : "商品不存在 " + rep.missing())));
			return 0;
		}
		src.sendFeedback(() -> Text.literal("§a已批量移除 §f" + rep.removed() + " §a件自 §f" + shopId
				+ (rep.missing().isEmpty() ? "" : " §7（未找到 " + rep.missing().size() + "）")
				+ " §7（定义在 config/redtale/" + ShopCatalog.TRASH_DIR + "/，整批可还原）"), true);
		return 1;
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

	/**
	 * T25 跨店搜索：{@code /shop find <关键词>}。
	 *
	 * <p>输出走聊天（不是 actionbar）：结果是要被逐条读的信息，一行放不下；
	 * 开界面那种「一次性提示」才走 actionbar。
	 */
	/** {@code /shop buy|sell <shopId> <itemId> [count]} 的参数链（两条命令只差结算方向）。 */
	private static com.mojang.brigadier.builder.RequiredArgumentBuilder<ServerCommandSource, String> tradeArgs(
			boolean buy) {
		return CommandManager.argument("shopId", StringArgumentType.word())
				.suggests(shops())
				.then(CommandManager.argument("itemId", ItemIdArgument.itemId())
						.then(CommandManager.argument("count", IntegerArgumentType.integer(1))
								.executes(c -> trade(c, buy, IntegerArgumentType.getInteger(c, "count"))))
						.executes(c -> trade(c, buy, 1)));
	}

	private static int trade(CommandContext<ServerCommandSource> ctx, boolean buy, int count) {
		ServerPlayerEntity player = ctx.getSource().getPlayer();
		if (player == null) {
			ctx.getSource().sendError(Text.literal("§c该命令只能由玩家执行（控制台没有背包）"));
			return 0;
		}
		String shopId = StringArgumentType.getString(ctx, "shopId");
		String itemId = StringArgumentType.getString(ctx, "itemId");
		com.akira.redtale.economy.net.ShopProtocol.ActionResult r =
				ShopNetworking.trade(player, shopId, itemId, count, buy);
		if (!r.ok()) {
			ctx.getSource().sendError(Text.literal("§c" + (buy ? "购买" : "出售") + "失败§8：§7"
					+ r.messageKey() + " §8(§7" + itemId + "§8) §7余额 §f" + r.balance()));
			return 0;
		}
		ctx.getSource().sendFeedback(() -> Text.literal("§a" + (buy ? "购买" : "出售") + "成功§8：§f"
				+ count + "× §7" + itemId + " §8| §7余额 §f" + r.balance()
				+ (r.stockRemaining() >= 0 ? " §8| §7剩余库存 §f" + r.stockRemaining() : "")), false);
		return count;
	}

	private static int find(CommandContext<ServerCommandSource> ctx) {
		String q = StringArgumentType.getString(ctx, "query");
		List<ShopCrossSearch.Hit> hits = ShopCatalog.searchAll(q, 8);
		if (hits.isEmpty()) {
			ctx.getSource().sendFeedback(() -> Text.literal("§c没找到§7：" + q
					+ " §8（试试更短的关键词，或 /shop list 看店）"), false);
			return 0;
		}
		StringBuilder sb = new StringBuilder("§8[§f跨店搜索§8] §7" + q.trim() + " §8→ §7" + hits.size() + " 条\n");
		for (ShopCrossSearch.Hit h : hits) {
			sb.append("§8· §f").append(h.displayName()).append(" §8<§7").append(h.itemId()).append("§8>");
			if (h.buy() > 0) {
				sb.append(" §a买 §f").append(h.buy());
				sb.append(h.stock() > 0 ? "§7（库存 " + h.stock() + "）" : " §c（缺货）");
			} else {
				sb.append(" §7仅收购");
			}
			if (h.sell() > 0) sb.append(" §8| §b卖 §f").append(h.sell());
			sb.append(" §8@ §e").append(h.shopName()).append('\n');
		}
		Text text = Text.literal(sb.toString().stripTrailing());
		ctx.getSource().sendFeedback(() -> text, false);
		return hits.size();
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

	// ---------------------------------------------------------------- T10 批量导入/导出

	/** {@code /shop admin export <file>}：全部商店导成一份批量文件（相对路径按数据根目录）。 */
	private static int exportShops(CommandContext<ServerCommandSource> ctx) {
		String arg = StringArgumentType.getString(ctx, "file");
		ShopBulk.ExportReport r;
		try {
			r = ShopBulk.exportTo(arg);
		} catch (Exception e) {
			ctx.getSource().sendError(Text.literal("§c导出失败：" + e));
			return 0;
		}
		if (r.shops() == 0) {
			ctx.getSource().sendError(Text.literal("§c没有可导出的商店（shop/shops 为空？）"));
			return 0;
		}
		ctx.getSource().sendFeedback(() -> Text.literal("§a导出完成：§f" + r.file()
				+ " §7（" + r.shops() + " 店 / " + r.items() + " 件）"), false);
		for (String err : r.errors()) {
			ctx.getSource().sendFeedback(() -> Text.literal("§e⚠ " + err), false);
		}
		return 1;
	}

	/**
	 * {@code /shop admin import <file> [replace] [dry]}。
	 *
	 * <p>{@code dry} = 只算计数不写盘；{@code replace} = 文件里没有的商品从该店移除（写前自动备份）。
	 */
	private static int importShops(CommandContext<ServerCommandSource> ctx, boolean replace,
			boolean dry) {
		String arg = StringArgumentType.getString(ctx, "file");
		ShopBulk.ImportReport r;
		try {
			r = ShopBulk.importFrom(arg, replace, dry);
		} catch (Exception e) {
			ctx.getSource().sendError(Text.literal("§c导入失败：" + e));
			return 0;
		}
		String head = (dry ? "§e预演（未写盘）" : "§a导入完成")
				+ "§7：新增 §f" + r.shopsNew() + " §7店 / 更新 §f" + r.shopsUpdated()
				+ " §7店；商品 新 §f" + r.itemsNew() + " §7/ 改 §f" + r.itemsUpdated()
				+ " §7/ 未变 §f" + r.itemsUnchanged() + " §7/ 移除 §f" + r.itemsRemoved();
		boolean any = (r.shopsNew() + r.shopsUpdated()) > 0;
		if (any) {
			ctx.getSource().sendFeedback(() -> Text.literal(head), false);
			if (!dry) {
				for (java.nio.file.Path p : r.files()) {
					ctx.getSource().sendFeedback(() -> Text.literal("§7→ " + p), false);
				}
			}
		} else {
			// 文件不存在 / 解析失败 / shops 为空 —— 不能画成绿色「导入完成」
			ctx.getSource().sendError(Text.literal("§c导入失败：没有可导入的商店（文件路径对不对？看下面原因）"));
		}
		int shown = 0;
		for (String err : r.errors()) {
			if (shown++ >= 5) {
				ctx.getSource().sendFeedback(() -> Text.literal("§7…还有 "
						+ (r.errors().size() - 5) + " 条，见日志"), false);
				break;
			}
			ctx.getSource().sendFeedback(() -> Text.literal("§e⚠ " + err), false);
		}
		return any ? 1 : 0;
	}

	// ---------------------------------------------------------------- 补全

	private static com.mojang.brigadier.suggestion.SuggestionProvider<ServerCommandSource> shops() {
		return (ctx, b) -> CommandSource.suggestMatching(ShopCatalog.shopIds(), b);
	}

	private static com.mojang.brigadier.suggestion.SuggestionProvider<ServerCommandSource> bulkFiles() {
		return (ctx, b) -> CommandSource.suggestMatching(ShopBulk.existingBulkFiles(), b);
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
