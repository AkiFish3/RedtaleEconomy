package com.akira.redtale.economy.shop;

import com.akira.redtale.economy.RedtaleEconomy;
import com.akira.redtale.economy.config.ShopConfig;
import com.akira.redtale.economy.config.StorageConfig;
import com.akira.redtale.economy.economy.BalanceService;
import com.akira.redtale.economy.module.ModuleManager;
import com.akira.redtale.economy.module.impl.GuildModule;
import com.akira.redtale.economy.module.impl.PremiumModule;
import com.akira.redtale.economy.net.ShopNetworking;
import com.akira.redtale.economy.net.ShopProtocol;
import com.akira.redtale.economy.storage.Ledger;
import com.akira.redtale.economy.util.DataRoot;
import com.akira.redtale.economy.util.PathResolver;
import com.akira.redtale.economy.util.TimeUtil;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 买卖结算（spec v0.2 #8：支持出售但必须限时限量）。
 *
 * <p>全部校验在服务端：库存、每日限购、余额、背包实际数量。
 * 客户端传来的 count 只作为「意图」，超出限制会被拒绝而不是裁剪。
 * 限购键使用 {@code shopId|itemId} —— 同一物品出现在两个商店时互不影响。
 */
public final class ShopTransactions implements ShopNetworking.TransactionHandler {

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final java.time.format.DateTimeFormatter TS =
			java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
	private static final Map<UUID, Limit> LIMITS = new HashMap<>();
	private static Path limitDir;
	private static String limitDate = "";

	private static final class Limit {
		String date = "";
		Map<String, Integer> bought = new HashMap<>();
		Map<String, Integer> sold = new HashMap<>();
		long soldTotal;
	}

	public ShopTransactions() {
	}

	public static void init() {
		PathResolver.Resolved r = DataRoot.resolve();
		try {
			Path d = r.path().resolve(ShopConfig.limitsDir());
			Files.createDirectories(d);
			limitDir = d;
		} catch (Exception e) {
			limitDir = null;
			RedtaleEconomy.LOGGER.error("[shop] 限购目录不可用：{}", e.getMessage());
		}
		CompensationQueue.init(r.path());
		limitDate = businessDay();
		// R38b：跨天时清掉限购缓存（定时器 + 登录兜底都会触发，同一天只跑一次）
		com.akira.redtale.economy.util.DayRollover.addListener((srv, day) -> onDayRollover());
		LIMITS.clear();
		ShopStock.init();
	}

	/** 商品目录重载后调用：库存覆盖值重新读盘（改价/上下架不影响已有库存）。 */
	public static void reloadState() {
		ShopStock.init();
		LIMITS.clear();
		limitDate = businessDay();
	}

	@Override
	public ShopProtocol.ActionResult buy(ServerPlayerEntity player, String shopId, String itemId, int count) {
		if (!ShopConfig.buyEnabled()) {
			return fail(player, "shop.buyDisabled", 0);
		}
		long balance = BalanceService.get(player.getUuid());
		ShopProtocol.Entry e = find(shopId, itemId);
		if (e == null || !e.canBuy()) {
			return new ShopProtocol.ActionResult(false, "shop.notFound", balance, 0);
		}
		ShopCatalog.ShopDef def = ShopCatalog.shop(shopId);
		boolean premium = def != null && def.requirePremium();

		long price = e.buyPrice() * count;
		// R32：协会等级商店折扣（只影响普通货币，赞助点商品不打折）
		if (!premium) price = GuildModule.applyShopDiscount(player.getUuid(), price);
		if (price <= 0) {
			return new ShopProtocol.ActionResult(false, "shop.badRequest", balance, 0);
		}
		long stock = stockOf(shopId, itemId, e);
		if (stock == 0) {
			return new ShopProtocol.ActionResult(false, "shop.outOfStock", balance, 0);
		}
		if (stock > 0 && count > stock) {
			return new ShopProtocol.ActionResult(false, "shop.notEnoughStock", balance, stock);
		}
		if (!checkDailyLimit(player.getUuid(), shopId, itemId, count)) {
			return new ShopProtocol.ActionResult(false, "shop.dailyLimit", balance, stock);
		}
		if (premium) {
			if (!ModuleManager.get().isEnabled("premium")) {
				return new ShopProtocol.ActionResult(false, "shop.premiumOnly", balance, stock);
			}
			if (PremiumModule.get(player.getUuid()) < price) {
				return new ShopProtocol.ActionResult(false, "shop.premiumInsufficient", balance, stock);
			}
		} else if (balance < price) {
			return new ShopProtocol.ActionResult(false, "shop.notEnoughMoney", balance, stock);
		}

		Item item = item(itemId);
		if (item == null) {
			return new ShopProtocol.ActionResult(false, "shop.notFound", balance, 0);
		}

		// E2：钱先扣、货后发 —— 写前日志保证「扣了钱没发货」在崩溃后可退
		com.google.gson.JsonObject payload = new com.google.gson.JsonObject();
		payload.addProperty("shopId", shopId);
		payload.addProperty("itemId", itemId);
		payload.addProperty("count", count);
		payload.addProperty("price", price);
		com.akira.redtale.economy.journal.TxnJournal.Txn txn =
				com.akira.redtale.economy.journal.TxnJournal.begin(
						com.akira.redtale.economy.journal.TxnJournal.SHOP_BUY, player.getUuid(), payload);

		// 1) 预占余额/高级币
		final String ref = shopId + "/" + itemId + "x" + count;
		if (premium) {
			if (!PremiumModule.take(player.getUuid(), price)) {
				txn.dismiss();
				return new ShopProtocol.ActionResult(false, "shop.premiumInsufficient", balance, stock);
			}
		} else if (!BalanceService.take(player.getUuid(), price, Ledger.TYPE_SHOP_BUY, ref)) {
			txn.dismiss();
			return new ShopProtocol.ActionResult(false, "shop.notEnoughMoney", balance, stock);
		}
		txn.state("charged");
		// R20：商店消费换战令经验（按实际扣掉的铜币算，赞助点消费不计）
		if (!premium) {
			try {
				com.akira.redtale.economy.module.impl.BattlePassModule.addSpendXp(player.getUuid(), price);
			} catch (Exception ex) {
				RedtaleEconomy.LOGGER.warn("[pass] 消费经验失败：{}", ex.getMessage());
			}
		}

		// 2) 发货 + 3) 提交库存/限购：任一步失败都要退款并留补偿记录（Q2）
		try {
			ItemStack stack = new ItemStack(item, count);
			player.getInventory().insertStack(stack);
			if (!stack.isEmpty()) {
				boolean dropped = player.dropItem(stack, false) != null;
				if (!dropped) {
					throw new IllegalStateException("背包与地面都放不下 " + count + " 个 " + itemId);
				}
			}
			addDailyLimit(player.getUuid(), key(shopId, itemId), count);
			long left = stock;
			if (stock > 0) {
				left = stock - count;
				ShopStock.set(shopId, itemId, left);
			}
			txn.state("delivered");
			txn.commit();

			long after = BalanceService.get(player.getUuid());
			return new ShopProtocol.ActionResult(true, "shop.buyOk", after, left);
		} catch (Throwable t) {
			refundBuy(player, premium, price, ref, t);
			txn.dismiss();
			return new ShopProtocol.ActionResult(false, "shop.giveFailed",
					BalanceService.get(player.getUuid()), stock);
		}
	}

	@Override
	public ShopProtocol.ActionResult sell(ServerPlayerEntity player, String shopId, String itemId, int count) {
		if (!ShopConfig.sellEnabled()) {
			return fail(player, "shop.sellDisabled", 0);
		}
		long balance = BalanceService.get(player.getUuid());
		ShopProtocol.Entry e = find(shopId, itemId);
		if (e == null || !e.canSell() || e.sellPrice() <= 0) {
			return new ShopProtocol.ActionResult(false, "shop.notSellable", balance, 0);
		}
		ShopCatalog.ShopDef def = ShopCatalog.shop(shopId);
		if (def != null && def.requirePremium()) {
			return new ShopProtocol.ActionResult(false, "shop.notSellable", balance, 0);
		}
		Item item = item(itemId);
		if (item == null) {
			return new ShopProtocol.ActionResult(false, "shop.notFound", balance, 0);
		}
		if (countOf(player, item) < count) {
			return new ShopProtocol.ActionResult(false, "shop.notEnoughItems", balance, 0);
		}

		String k = key(shopId, itemId);
		long income = e.sellPrice() * count;
		String limitError = checkSellLimit(player.getUuid(), k, count, income);
		if (limitError != null) {
			return new ShopProtocol.ActionResult(false, limitError, balance, 0);
		}

		// 先扣物品，再入账；入账失败要把物品还回去（Q2）
		// E2：写前日志 —— 崩溃后能按 taken 状态把物品退回来
		com.google.gson.JsonObject sellPayload = new com.google.gson.JsonObject();
		sellPayload.addProperty("shopId", shopId);
		sellPayload.addProperty("itemId", itemId);
		sellPayload.addProperty("count", count);
		sellPayload.addProperty("income", income);
		com.akira.redtale.economy.journal.TxnJournal.Txn txn =
				com.akira.redtale.economy.journal.TxnJournal.begin(
						com.akira.redtale.economy.journal.TxnJournal.SHOP_SELL, player.getUuid(), sellPayload);

		remove(player, item, count);
		txn.state("taken");
		try {
			BalanceService.add(player.getUuid(), income, Ledger.TYPE_SHOP_SELL,
					shopId + "/" + itemId + "x" + count);
			Limit l = limit(player.getUuid());
			l.sold.merge(k, count, Integer::sum);
			l.soldTotal += income;
			save(player.getUuid(), l);
			txn.state("paid");
			txn.commit();

			long after = BalanceService.get(player.getUuid());
			return new ShopProtocol.ActionResult(true, "shop.sellOk", after, -1);
		} catch (Throwable t) {
			ItemStack back = new ItemStack(item, count);
			player.getInventory().insertStack(back);
			if (!back.isEmpty()) player.dropItem(back, false);
			txn.dismiss();
			CompensationQueue.enqueue(new CompensationQueue.Entry(
					java.time.LocalDateTime.now().format(TS), player.getUuid().toString(),
					shopId, itemId, count, income, "sell-add-failed: " + t.getMessage()));
			RedtaleEconomy.LOGGER.error("[shop] 出售入账失败，已退还物品：{}", t.getMessage(), t);
			return new ShopProtocol.ActionResult(false, "shop.sellFailed",
					BalanceService.get(player.getUuid()), -1);
		}
	}

	/** 退款 + 补偿记录；退款本身失败只记 ERROR，钱仍需人工补。 */
	private static void refundBuy(ServerPlayerEntity player, boolean premium, long price,
			String ref, Throwable cause) {
		boolean refunded = false;
		try {
			if (premium) {
				PremiumModule.add(player.getUuid(), price);
			} else {
				BalanceService.add(player.getUuid(), price, Ledger.TYPE_ADMIN, "refund:" + ref);
			}
			refunded = true;
		} catch (Throwable t) {
			RedtaleEconomy.LOGGER.error("[shop] 退款失败，需人工补 {} 给 {}（{}）：{}",
					price, player.getUuid(), ref, t.getMessage());
		}
		CompensationQueue.enqueue(new CompensationQueue.Entry(
				java.time.LocalDateTime.now().format(TS), player.getUuid().toString(),
				ref, "", 0, price,
				(refunded ? "refunded: " : "REFUND-FAILED: ") + cause.getMessage()));
		RedtaleEconomy.LOGGER.error("[shop] 购买发货失败（{}），已{} {}：{}",
				ref, refunded ? "退款" : "未能退款", price, cause.getMessage());
	}

	/** R38b：新业务日 → 清空限购缓存（下一次访问按新日期重新读盘）。 */
	public static void onDayRollover() {
		LIMITS.clear();
		limitDate = businessDay();
	}

	/** 业务日（含 core.resetHour，之前用裸 today() 导致 resetHour 对商店不生效）。 */
	private static String businessDay() {
		return TimeUtil.today(com.akira.redtale.economy.config.CoreConfig.resetHour());
	}

	// ---------------------------------------------------------------- 辅助

	private static ShopProtocol.ActionResult fail(ServerPlayerEntity player, String key, long stock) {
		return new ShopProtocol.ActionResult(false, key, BalanceService.get(player.getUuid()), stock);
	}

	private static ShopProtocol.Entry find(String shopId, String itemId) {
		return ShopCatalog.entry(shopId, itemId);
	}

	private static String key(String shopId, String itemId) {
		return shopId + "|" + itemId;
	}

	private static long stockOf(String shopId, String itemId, ShopProtocol.Entry e) {
		if (e.stock() < 0) return -1;
		return ShopStock.get(shopId, itemId, e.stock());
	}

	private static Item item(String id) {
		try {
			Identifier ident = Identifier.tryParse(id);
			return ident == null ? null : Registries.ITEM.get(ident);
		} catch (Exception e) {
			return null;
		}
	}

	private static int countOf(ServerPlayerEntity p, Item item) {
		int n = 0;
		for (int i = 0; i < p.getInventory().size(); i++) {
			ItemStack st = p.getInventory().getStack(i);
			if (!st.isEmpty() && st.getItem() == item) n += st.getCount();
		}
		return n;
	}

	private static void remove(ServerPlayerEntity p, Item item, int count) {
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

	// ---------------------------------------------------------------- 每日限购 / 限量

	private static boolean checkDailyLimit(UUID uuid, String shopId, String itemId, int count) {
		int limit = ShopCatalog.dailyLimitOf(shopId, itemId);
		if (limit < 0) return true;
		if (limit == 0) return false;
		Limit l = limit(uuid);
		int used = l.bought.getOrDefault(key(shopId, itemId), 0);
		return used + count <= limit;
	}

	private static void addDailyLimit(UUID uuid, String k, int count) {
		Limit l = limit(uuid);
		l.bought.merge(k, count, Integer::sum);
		save(uuid, l);
	}

	/** @return null=允许，否则返回拒绝用的语言 key */
	private static String checkSellLimit(UUID uuid, String k, int count, long income) {
		Limit l = limit(uuid);
		int perItem = ShopConfig.sellDailyLimitPerItem();
		if (perItem > 0 && l.sold.getOrDefault(k, 0) + count > perItem) {
			return "shop.sellDailyLimit";
		}
		long total = ShopConfig.sellDailyLimitTotal();
		if (total > 0 && l.soldTotal + income > total) {
			return "shop.sellDailyLimitTotal";
		}
		return null;
	}

	private static Limit limit(UUID uuid) {
		String today = businessDay();
		if (!today.equals(limitDate)) {
			LIMITS.clear();
			limitDate = today;
		}
		return LIMITS.computeIfAbsent(uuid, k -> load(uuid, today));
	}

	private static Limit load(UUID uuid, String today) {
		Limit l = new Limit();
		l.date = today;
		Path f = limitDir == null ? null : limitDir.resolve(uuid + ".json");
		if (f != null && Files.isRegularFile(f)) {
			try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
				Limit loaded = GSON.fromJson(r, Limit.class);
				if (loaded != null && today.equals(loaded.date)) return loaded;
			} catch (Exception e) {
				RedtaleEconomy.LOGGER.warn("[shop] 限购记录读取失败 {}：{}", uuid, e.getMessage());
			}
		}
		return l;
	}

	private static void save(UUID uuid, Limit l) {
		if (limitDir == null) return;
		Path f = limitDir.resolve(uuid + ".json");
		Path tmp = limitDir.resolve(uuid + ".json.tmp");
		try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
			GSON.toJson(l, w);
		} catch (Exception e) {
			RedtaleEconomy.LOGGER.error("[shop] 限购记录写入失败：{}", e.getMessage());
			return;
		}
		try {
			Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (Exception e) {
			try {
				Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
			} catch (Exception ignored) {
				// 上一份记录仍可用
			}
		}
	}
}
