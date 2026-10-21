package com.akira.redtale.economy.market;

import com.akira.redtale.economy.RedtaleEconomy;
import com.akira.redtale.economy.config.MarketConfig;
import com.akira.redtale.economy.economy.BalanceService;
import com.akira.redtale.economy.lang.Lang;
import com.akira.redtale.economy.mail.MailService;
import com.akira.redtale.economy.module.ModuleManager;
import com.akira.redtale.economy.module.impl.WarehouseModule;
import com.akira.redtale.economy.storage.Ledger;
import com.akira.redtale.economy.storage.TxnRunner;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * 玩家市场业务（R40）：挂单托管 → 成交发货 → 卖家收款。
 *
 * <p>每一步的失败都能还原到「操作前」：
 * <pre>
 * 挂单：校验 → 扣挂单费 → 取走手上的物品 → 写表
 *       写表失败 ⇒ 退费 + 退物品
 * 成交：校验 → 买家付款 → 标记 SOLD 落盘 → 发货 → 清托管 → 打款给卖家
 *       发货异常 ⇒ 恢复 OPEN + 退买家钱（宁可失败也不吞钱吞物）
 * 撤单/过期：标记终态 → 退物品（在线发背包，离线进邮件）
 * </pre>
 *
 * <p>所有写操作都要求服务端主线程调用（命令与网络回调已经 {@code server.execute}）。
 */
public final class MarketService {

	/** 结果码：客户端/命令层按 code 分支，文案只走 lang。 */
	public enum Code {
		OK("ok"),
		DISABLED("disabled"),
		EMPTY_HAND("emptyHand"),
		BAD_COUNT("badCount"),
		BAD_PRICE("badPrice"),
		BLOCKED("blocked"),
		TOO_MANY("tooMany"),
		NO_MONEY("noMoney"),
		NO_OFFER("noOffer"),
		OWN_OFFER("ownOffer"),
		NOT_YOURS("notYours"),
		NOT_OPEN("notOpen"),
		EXPIRED("expired"),
		SAVE_FAIL("saveFail"),
		// R41 拍卖
		NOT_AUCTION("notAuction"),
		AUCTION_OFF("auctionOff"),
		BID_TOO_LOW("bidTooLow"),
		AUCTION_HAS_BID("auctionHasBid");

		public final String key;

		Code(String key) {
			this.key = key;
		}

		public String langKey() {
			return "market.err." + key;
		}
	}

	/** 操作结果：{@code message} 是已经本地化好的整句，{@code args} 留给命令层拼细节。 */
	public record Result(Code code, String message, Object... args) {
		public static Result err(Code c, Object... a) {
			return new Result(c, Lang.msg(c.langKey(), a), a);
		}

		public static Result ok(String msg, Object... a) {
			return new Result(Code.OK, msg, a);
		}

		public boolean ok() {
			return code == Code.OK;
		}
	}

	private MarketService() {
	}

	public static boolean enabled() {
		return ModuleManager.get().isEnabled("market") && MarketConfig.enabled();
	}

	// ---------------------------------------------------------------- 挂单

	/** 挂出手上的整叠物品。 */
	public static synchronized Result list(ServerPlayerEntity player, long price) {
		if (player == null) return Result.err(Code.DISABLED);
		if (!enabled()) return Result.err(Code.DISABLED);
		ItemStack hand = player.getStackInHand(Hand.MAIN_HAND);
		if (hand.isEmpty()) return Result.err(Code.EMPTY_HAND);
		if (hand.getCount() > MarketConfig.maxCount()) {
			return Result.err(Code.BAD_COUNT, MarketConfig.maxCount());
		}
		if (!MarketRules.priceInRange(price, MarketConfig.minPrice(), MarketConfig.maxPrice())) {
			return Result.err(Code.BAD_PRICE, BalanceService.format(MarketConfig.minPrice()),
					BalanceService.format(MarketConfig.maxPrice()));
		}
		String itemId = Registries.ITEM.getId(hand.getItem()).toString();
		if (MarketConfig.blockedItems().contains(itemId)) {
			return Result.err(Code.BLOCKED, hand.getName().getString());
		}
		if (MarketStore.openCountOf(player.getUuid()) >= MarketConfig.maxOpenPerPlayer()) {
			return Result.err(Code.TOO_MANY, MarketConfig.maxOpenPerPlayer());
		}

		long fee = MarketRules.listingFee(price, MarketConfig.feePercent());
		if (fee > 0 && !BalanceService.take(player.getUuid(), fee, Ledger.TYPE_MARKET_FEE, "market:list")) {
			return Result.err(Code.NO_MONEY, BalanceService.format(fee));
		}

		// 托管：先复制再清空，保证「表里的 SNBT」和「背包扣掉的东西」是同一份
		ItemStack escrow = hand.copy();
		String snbt = MarketStore.encode(escrow);
		if (snbt.isEmpty()) {
			// T34：正常回退走 revert（不进补偿队列），失败也会留 ERROR
			TxnRunner.revert(player.getUuid(), fee, Ledger.TYPE_MARKET_FEE, "market:list:refund");
			return Result.err(Code.SAVE_FAIL);
		}
		player.setStackInHand(Hand.MAIN_HAND, ItemStack.EMPTY);
		player.getInventory().markDirty();
		player.currentScreenHandler.sendContentUpdates();

		MarketStore.Entry e = new MarketStore.Entry();
		e.id = newId();
		e.type = "sell";
		e.seller = player.getUuidAsString();
		e.sellerName = player.getGameProfile().getName();
		e.itemId = itemId;
		e.count = escrow.getCount();
		e.price = price;
		e.fee = fee;
		e.createdAt = System.currentTimeMillis();
		e.expireAt = MarketConfig.expireHours() <= 0 ? 0
				: e.createdAt + MarketConfig.expireHours() * 3600_000L;
		e.status = MarketStore.Status.OPEN.name();
		e.escrow = snbt;

		// add() 内部落盘；写不进去就把东西和钱都还回去，绝不留「物品已扣、单子不存在」
		if (!MarketStore.add(e)) {
			MarketStore.removeEntry(e);
			player.getInventory().offerOrDrop(escrow.copy());
			TxnRunner.revert(player.getUuid(), fee, Ledger.TYPE_MARKET_FEE, "market:list:refund");
			return Result.err(Code.SAVE_FAIL);
		}
		return Result.ok(Lang.msg("market.ok.list", e.id, escrow.getName().getString(), e.count,
				BalanceService.format(price), BalanceService.format(fee)),
				e.id, escrow.getName().getString(), e.count);
	}

	/** 生成 {@code of_yyyyMMdd_HHmmss_xxx} 形状的 id（既单调又不会撞）。 */
	public static String newId() {
		long now = System.currentTimeMillis();
		return "of_" + Long.toString(now, 36) + "_" + Integer.toString((int) (Math.random() * 1296), 36);
	}

	// ---------------------------------------------------------------- 成交

	/** 整单买下。目前不支持拆单（拆单要处理部分 NBT 拆分，留到 R41 一起做）。 */
	public static synchronized Result buy(ServerPlayerEntity buyer, String offerId) {
		if (buyer == null) return Result.err(Code.DISABLED);
		if (!enabled()) return Result.err(Code.DISABLED);
		MarketStore.Entry e = MarketStore.byId(offerId);
		if (e == null) return Result.err(Code.NO_OFFER);
		if (!e.isOpen()) return Result.err(Code.NOT_OPEN);
		if (MarketRules.expired(e.expireAt, System.currentTimeMillis())) {
			expireOne(e, RedtaleEconomy.server());
			return Result.err(Code.EXPIRED);
		}
		if (e.seller.equals(buyer.getUuidAsString())) return Result.err(Code.OWN_OFFER);

		ItemStack stack = e.escrowStack();
		if (stack.isEmpty()) {
			// 托管数据坏了：单子作废，不能让下一个人继续踩
			e.status = MarketStore.Status.EXPIRED.name();
			e.soldAt = System.currentTimeMillis();
			e.escrow = "";
			MarketStore.save();
			RedtaleEconomy.LOGGER.error("[market] 挂单 {} 的托管物品无法还原，已作废", e.id);
			return Result.err(Code.NOT_OPEN);
		}

		if (!BalanceService.take(buyer.getUuid(), e.price, Ledger.TYPE_MARKET_BUY, "market:buy:" + e.id)) {
			return Result.err(Code.NO_MONEY, BalanceService.format(e.price));
		}

		// 先落盘「已售」，避免并发/重连把同一单卖两次
		e.status = MarketStore.Status.SOLD.name();
		e.soldTo = buyer.getUuidAsString();
		e.soldToName = buyer.getGameProfile().getName();
		e.soldAt = System.currentTimeMillis();
		if (!MarketStore.save()) {
			e.status = MarketStore.Status.OPEN.name();
			e.soldTo = "";
			e.soldToName = "";
			e.soldAt = 0;
			TxnRunner.refund(buyer.getUuid(), e.price, Ledger.TYPE_MARKET_BUY, "market:buy:refund:" + e.id,
					new TxnRunner.Note(buyer.getUuid(), "market", e.id, e.count, e.price,
							"buy rolled back"));
			return Result.err(Code.SAVE_FAIL);
		}

		// 发货：仓储优先（spec §43.5 预留 source=market），其次邮件队列
		try {
			deliver(buyer.getUuid(), stack, "market:" + e.id, RedtaleEconomy.server());
		} catch (Exception ex) {
			RedtaleEconomy.LOGGER.error("[market] 发货失败，回滚交易 {}：{}", e.id, ex.getMessage());
			e.status = MarketStore.Status.OPEN.name();
			e.soldTo = "";
			e.soldToName = "";
			e.soldAt = 0;
			MarketStore.save();
			TxnRunner.refund(buyer.getUuid(), e.price, Ledger.TYPE_MARKET_BUY, "market:buy:refund:" + e.id,
					new TxnRunner.Note(buyer.getUuid(), "market", e.id, e.count, e.price,
							"buy rolled back"));
			return Result.err(Code.SAVE_FAIL);
		}

		long proceeds = MarketRules.sellerProceeds(e.price, MarketConfig.commissionPercent());
		e.escrow = "";
		MarketStore.save();
		BalanceService.add(UUID.fromString(e.seller), proceeds, Ledger.TYPE_MARKET_SELL, "market:sell:" + e.id);

		if (MarketConfig.notifyOnSell()) {
			notifyOnline(e.seller, Lang.msg("market.sell.done", buyer.getGameProfile().getName(),
					stack.getName().getString(), e.count, BalanceService.format(proceeds)));
		}
		return Result.ok(Lang.msg("market.ok.buy", e.id, stack.getName().getString(), e.count,
				BalanceService.format(e.price)), e.id, stack.getName().getString(), e.count);
	}

	// ---------------------------------------------------------------- 撤单 / 过期

	public static synchronized Result cancel(ServerPlayerEntity player, String offerId) {
		if (player == null) return Result.err(Code.DISABLED);
		if (!enabled()) return Result.err(Code.DISABLED);
		MarketStore.Entry e = MarketStore.byId(offerId);
		if (e == null) return Result.err(Code.NO_OFFER);
		if (!e.seller.equals(player.getUuidAsString())) return Result.err(Code.NOT_YOURS);
		if (!e.isOpen()) return Result.err(Code.NOT_OPEN);
		// 拍卖一旦有人出价就不能撤：撤了要先退所有出价，等于给卖家一个「毁拍」按钮
		if (e.isAuction() && e.bid > 0) return Result.err(Code.AUCTION_HAS_BID);

		ItemStack stack = e.escrowStack();
		if (!stack.isEmpty()
				&& !deliver(player.getUuid(), stack, "market:cancel:" + e.id, RedtaleEconomy.server())) {
			return Result.err(Code.SAVE_FAIL);
		}
		e.status = MarketStore.Status.CANCELLED.name();
		e.soldAt = System.currentTimeMillis();
		e.escrow = "";
		if (!MarketStore.save()) {
			RedtaleEconomy.LOGGER.error("[market] 撤单 {} 落盘失败，物品已退回但单子仍未结清", e.id);
			return Result.err(Code.SAVE_FAIL);
		}
		return Result.ok(Lang.msg("market.ok.cancel", e.id,
				stack.isEmpty() ? e.itemId : stack.getName().getString(), e.count),
				e.id, stack.isEmpty() ? e.itemId : stack.getName().getString(), e.count);
	}

	/** 到期扫描：由模块定时器（每 60 秒）+ 每次打开列表时调用。 */
	public static synchronized int sweep(MinecraftServer server) {
		if (!enabled()) return 0;
		long now = System.currentTimeMillis();
		int n = 0;
		for (MarketStore.Entry e : MarketStore.open()) {
			if (MarketRules.expired(e.expireAt, now)) {
				if (e.isAuction()) settleAuction(e, server);
				else expireOne(e, server);
				n++;
			}
		}
		if (n > 0) MarketStore.save();
		return n;
	}

	private static void expireOne(MarketStore.Entry e, MinecraftServer server) {
		ItemStack stack = e.escrowStack();
		// 先交付再清托管：交付不掉（离线且邮件不可用）时保留挂单，下次 sweep 再试
		if (!stack.isEmpty() && !deliver(UUID.fromString(e.seller), stack, "market:expire:" + e.id, server)) {
			RedtaleEconomy.LOGGER.warn("[market] {} 到期但物品交不出去，保留挂单等下次重试", e.id);
			return;
		}
		e.status = MarketStore.Status.EXPIRED.name();
		e.soldAt = System.currentTimeMillis();
		e.escrow = "";
		MarketStore.save();
		if (MarketConfig.notifyOnExpire()) {
			notifyOnline(e.seller, Lang.msg("market.expired", stack.isEmpty() ? e.itemId
					: stack.getName().getString(), e.count));
		}
	}

	/** 管理员立即过期某人/全部（{@code seller == null} = 全部）。 */
	public static synchronized int expireNow(MinecraftServer server, UUID seller) {
		long now = System.currentTimeMillis();
		int n = 0;
		for (MarketStore.Entry e : MarketStore.open()) {
			if (seller != null && !seller.toString().equals(e.seller)) continue;
			e.expireAt = now;
			expireOne(e, server);
			n++;
		}
		return n;
	}

	// ---------------------------------------------------------------- R41 拍卖

	/** 挂拍卖：起拍价 + 时长，其余校验与市场一致（同一张表、同一套托管）。 */
	public static synchronized Result startAuction(ServerPlayerEntity player, long startPrice, int hours) {
		if (player == null) return Result.err(Code.DISABLED);
		if (!enabled()) return Result.err(Code.DISABLED);
		if (!MarketConfig.auctionEnabled()) return Result.err(Code.AUCTION_OFF);
		ItemStack hand = player.getStackInHand(Hand.MAIN_HAND);
		if (hand.isEmpty()) return Result.err(Code.EMPTY_HAND);
		if (hand.getCount() > MarketConfig.maxCount()) return Result.err(Code.BAD_COUNT, MarketConfig.maxCount());
		if (!MarketRules.priceInRange(startPrice, MarketConfig.minPrice(), MarketConfig.maxPrice())) {
			return Result.err(Code.BAD_PRICE, BalanceService.format(MarketConfig.minPrice()),
					BalanceService.format(MarketConfig.maxPrice()));
		}
		String itemId = Registries.ITEM.getId(hand.getItem()).toString();
		if (MarketConfig.blockedItems().contains(itemId)) return Result.err(Code.BLOCKED, hand.getName().getString());
		if (MarketStore.openCountOf(player.getUuid()) >= MarketConfig.maxOpenPerPlayer()) {
			return Result.err(Code.TOO_MANY, MarketConfig.maxOpenPerPlayer());
		}

		long fee = MarketRules.listingFee(startPrice, MarketConfig.feePercent());
		if (fee > 0 && !BalanceService.take(player.getUuid(), fee, Ledger.TYPE_MARKET_FEE, "auction:list")) {
			return Result.err(Code.NO_MONEY, BalanceService.format(fee));
		}
		ItemStack escrow = hand.copy();
		String snbt = MarketStore.encode(escrow);
		if (snbt.isEmpty()) {
			TxnRunner.revert(player.getUuid(), fee, Ledger.TYPE_MARKET_FEE, "auction:list:refund");
			return Result.err(Code.SAVE_FAIL);
		}
		player.setStackInHand(Hand.MAIN_HAND, ItemStack.EMPTY);
		player.getInventory().markDirty();
		player.currentScreenHandler.sendContentUpdates();

		int h = Math.max(1, Math.min(336, hours <= 0 ? MarketConfig.auctionHours() : hours));
		MarketStore.Entry e = new MarketStore.Entry();
		e.id = newId();
		e.type = "auction";
		e.seller = player.getUuidAsString();
		e.sellerName = player.getGameProfile().getName();
		e.itemId = itemId;
		e.count = escrow.getCount();
		e.price = startPrice;
		e.fee = fee;
		e.createdAt = System.currentTimeMillis();
		e.expireAt = e.createdAt + h * 3600_000L;
		e.status = MarketStore.Status.OPEN.name();
		e.escrow = snbt;
		if (!MarketStore.add(e)) {
			MarketStore.removeEntry(e);
			player.getInventory().offerOrDrop(escrow.copy());
			TxnRunner.revert(player.getUuid(), fee, Ledger.TYPE_MARKET_FEE, "auction:list:refund");
			return Result.err(Code.SAVE_FAIL);
		}
		return Result.ok(Lang.msg("market.ok.auction", e.id, escrow.getName().getString(), e.count,
				BalanceService.format(startPrice), h, BalanceService.format(fee)), e.id);
	}

	/**
	 * 出价：立刻扣款托管，被超越时退给上一位。
	 *
	 * <p>「先扣钱再记账」的顺序是刻意的 —— 结算时钱已经在系统手里，
	 * 不会出现「中标的人刚好把钱花光了，物品没处发、卖家收不到钱」。
	 */
	public static synchronized Result bid(ServerPlayerEntity bidder, String offerId, long amount) {
		if (bidder == null) return Result.err(Code.DISABLED);
		if (!enabled()) return Result.err(Code.DISABLED);
		MarketStore.Entry e = MarketStore.byId(offerId);
		if (e == null) return Result.err(Code.NO_OFFER);
		if (!e.isAuction()) return Result.err(Code.NOT_AUCTION);
		if (!e.isOpen()) return Result.err(Code.NOT_OPEN);
		long now = System.currentTimeMillis();
		if (MarketRules.expired(e.expireAt, now)) {
			settleAuction(e, RedtaleEconomy.server());
			return Result.err(Code.EXPIRED);
		}
		if (e.seller.equals(bidder.getUuidAsString())) return Result.err(Code.OWN_OFFER);
		if (e.bidder.equals(bidder.getUuidAsString())) return Result.err(Code.OWN_OFFER);

		long min = MarketRules.minNextBid(e.bid, e.price, MarketConfig.auctionMinIncrementPercent());
		if (amount < min) {
			return Result.err(Code.BID_TOO_LOW, BalanceService.format(min));
		}
		if (!BalanceService.take(bidder.getUuid(), amount, Ledger.TYPE_MARKET_AUCTION, "auction:bid:" + e.id)) {
			return Result.err(Code.NO_MONEY, BalanceService.format(amount));
		}

		String prevBidder = e.bidder;
		long prevBid = e.bid;
		int prevCount = e.bidCount;
		long prevExpire = e.expireAt;

		e.bid = amount;
		e.bidder = bidder.getUuidAsString();
		e.bidderName = bidder.getGameProfile().getName();
		e.bidCount = prevCount + 1;
		long window = MarketConfig.auctionExtendWindowMinutes() * 60_000L;
		long extend = MarketConfig.auctionExtendMinutes() * 60_000L;
		if (MarketRules.shouldExtend(now, e.expireAt, window)) {
			e.expireAt = MarketRules.extendedExpireAt(now, e.expireAt, extend);
		}
		if (!MarketStore.save()) {
			e.bid = prevBid;
			e.bidder = prevBidder == null ? "" : prevBidder;
			e.bidderName = "";
			e.bidCount = prevCount;
			e.expireAt = prevExpire;
			TxnRunner.refund(bidder.getUuid(), amount, Ledger.TYPE_MARKET_AUCTION, "auction:bid:refund:" + e.id,
					new TxnRunner.Note(bidder.getUuid(), "market", e.id, 0, amount, "bid save failed"));
			return Result.err(Code.SAVE_FAIL);
		}
		// 上一手的钱退还（他已经被超越，钱不能再押着）
		if (!prevBidder.isEmpty() && prevBid > 0) {
			TxnRunner.revert(UUID.fromString(prevBidder), prevBid, Ledger.TYPE_MARKET_AUCTION,
					"auction:outbid:" + e.id);
			notifyOnline(prevBidder, Lang.msg("market.auction.outbid", e.id,
					BalanceService.format(prevBid)));
		}
		return Result.ok(Lang.msg("market.ok.bid", e.id, BalanceService.format(amount)), e.id);
	}

	/** 结算：有人出价 → 发物品给买家、打款给卖家；没人出价 → 物归原主。 */
	private static void settleAuction(MarketStore.Entry e, MinecraftServer server) {
		ItemStack stack = e.escrowStack();
		long now = System.currentTimeMillis();
		if (e.bid > 0 && !e.bidder.isEmpty()) {
			e.status = MarketStore.Status.SOLD.name();
			e.soldTo = e.bidder;
			e.soldToName = e.bidderName;
			e.soldAt = now;
			e.escrow = "";
			MarketStore.save();
			if (!stack.isEmpty()) {
				deliver(UUID.fromString(e.bidder), stack, "auction:win:" + e.id, server);
			}
			long proceeds = MarketRules.sellerProceeds(e.bid, MarketConfig.commissionPercent());
			BalanceService.add(UUID.fromString(e.seller), proceeds, Ledger.TYPE_MARKET_SELL,
					"auction:sell:" + e.id);
			notifyOnline(e.bidder, Lang.msg("market.auction.won", e.id,
					stack.isEmpty() ? e.itemId : stack.getName().getString(), e.count,
					BalanceService.format(e.bid)));
			notifyOnline(e.seller, Lang.msg("market.auction.sold", e.id,
					stack.isEmpty() ? e.itemId : stack.getName().getString(), e.count,
					BalanceService.format(proceeds)));
			return;
		}
		// 流拍
		e.status = MarketStore.Status.EXPIRED.name();
		e.soldAt = now;
		e.escrow = "";
		MarketStore.save();
		if (!stack.isEmpty()) deliver(UUID.fromString(e.seller), stack, "auction:unsold:" + e.id, server);
		if (MarketConfig.notifyOnExpire()) {
			notifyOnline(e.seller, Lang.msg("market.auction.unsold", e.id,
					stack.isEmpty() ? e.itemId : stack.getName().getString(), e.count));
		}
	}

	// ---------------------------------------------------------------- 查询

	public enum Filter { ALL, MINE }

	/** 列表查询：服务端过滤 + 排序（价格升序，同价按时间）。 */
	public static synchronized List<MarketStore.Entry> query(ServerPlayerEntity viewer, Filter filter, String query) {
		if (!enabled()) return List.of();
		sweep(RedtaleEconomy.server());
		String q = query == null ? "" : query.trim().toLowerCase(java.util.Locale.ROOT);
		List<MarketStore.Entry> out = new ArrayList<>();
		for (MarketStore.Entry e : MarketStore.open()) {
			// 市场页只列直售单：拍卖的「买」按钮语义不同（要出价），
			// 混在一起会让玩家点「买」拿到 NOT_AUCTION。拍卖用 /market auction list。
			if (e.isAuction()) continue;
			if (filter == Filter.MINE) {
				if (viewer == null || !e.seller.equals(viewer.getUuidAsString())) continue;
			} else if (viewer != null && !q.isEmpty() && !matches(e, q)) {
				continue;
			}
			out.add(e);
		}
		out.sort(Comparator.comparingLong((MarketStore.Entry e) -> e.price).thenComparingLong(e -> e.createdAt));
		return out;
	}

	/** 拍卖列表（同样只读 OPEN，按结束时间升序 —— 快结束的排前面）。 */
	public static synchronized List<MarketStore.Entry> auctions() {
		if (!enabled()) return List.of();
		sweep(RedtaleEconomy.server());
		List<MarketStore.Entry> out = new ArrayList<>();
		for (MarketStore.Entry e : MarketStore.open()) {
			if (e.isAuction()) out.add(e);
		}
		out.sort(Comparator.comparingLong((MarketStore.Entry e) -> e.expireAt));
		return out;
	}

	private static boolean matches(MarketStore.Entry e, String q) {
		if (e.itemId.toLowerCase(java.util.Locale.ROOT).contains(q)) return true;
		if (e.sellerName.toLowerCase(java.util.Locale.ROOT).contains(q)) return true;
		try {
			ItemStack st = e.escrowStack();
			if (!st.isEmpty() && st.getName().getString().toLowerCase(java.util.Locale.ROOT).contains(q)) return true;
		} catch (Exception ignored) {
			// 名称取不到就只按 id / 卖家名匹配
		}
		return false;
	}

	// ---------------------------------------------------------------- 发货 / 提示

	/**
	 * 把物品交给玩家：仓储 → 在线背包 → 邮件队列。
	 *
	 * <p>三步都失败时最后落成掉落物，宁可在世界里也别凭空消失（玩家能捡回来）。
	 */
	public static boolean deliver(UUID uuid, ItemStack stack, String reason, MinecraftServer server) {
		if (uuid == null || stack == null || stack.isEmpty()) return true;
		ItemStack rest = stack.copy();
		try {
			rest = WarehouseModule.give(uuid, rest);
			if (rest.isEmpty()) return true;
		} catch (Exception e) {
			RedtaleEconomy.LOGGER.warn("[market] 仓储发货失败，改走背包/邮件：{}", e.getMessage());
		}
		ServerPlayerEntity online = server == null ? null : server.getPlayerManager().getPlayer(uuid);
		if (online != null) {
			int before = countMatching(online, rest);
			online.getInventory().insertStack(rest.copy());
			online.currentScreenHandler.sendContentUpdates();
			int moved = countMatching(online, rest) - before;
			int left = rest.getCount() - Math.max(0, moved);
			if (left <= 0) return true;
			rest.setCount(left);
		}
		try {
			if (server != null && MailService.sendItemOrQueue(server, uuid, rest.copy(), reason)) return true;
		} catch (Exception e) {
			RedtaleEconomy.LOGGER.warn("[market] 邮件发货失败：{}", e.getMessage());
		}
		if (online != null) {
			online.getInventory().offerOrDrop(rest.copy());
			return true;
		}
		RedtaleEconomy.LOGGER.error("[market] 玩家 {} 不在线且邮件不可用，物品暂存失败：{}",
				uuid, Registries.ITEM.getId(stack.getItem()));
		return false;
	}

	/** 背包里与给定栈「同物品 + 同 NBT」的总数（用来算 insertStack 到底放进去几个）。 */
	private static int countMatching(ServerPlayerEntity player, ItemStack sample) {
		int n = 0;
		for (int i = 0; i < player.getInventory().size(); i++) {
			ItemStack st = player.getInventory().getStack(i);
			if (st.isEmpty() || !ItemStack.canCombine(st, sample)) continue;
			if (st.getItem() != sample.getItem()) continue;
			n += st.getCount();
		}
		return n;
	}

	private static void notifyOnline(String uuid, String text) {
		try {
			MinecraftServer server = RedtaleEconomy.server();
			if (server == null) return;
			ServerPlayerEntity p = server.getPlayerManager().getPlayer(UUID.fromString(uuid));
			if (p != null) p.sendMessage(Text.literal(text), true);
		} catch (Exception ignored) {
			// 提示失败不影响交易
		}
	}
}
