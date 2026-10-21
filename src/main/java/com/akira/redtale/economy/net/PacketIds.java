package com.akira.redtale.economy.net;

import com.akira.redtale.economy.RedtaleEconomy;
import net.minecraft.util.Identifier;

/** 网络通道名。改这里等于改协议版本，客户端/服务端必须同版本。 */
public final class PacketIds {

	public static final Identifier C2S_REQUEST_PAGE = RedtaleEconomy.id("shop/req_page");
	public static final Identifier C2S_BUY = RedtaleEconomy.id("shop/buy");
	public static final Identifier C2S_SELL = RedtaleEconomy.id("shop/sell");

	public static final Identifier S2C_PAGE = RedtaleEconomy.id("shop/page");
	public static final Identifier S2C_OPEN = RedtaleEconomy.id("shop/open");
	public static final Identifier S2C_OPEN_EDIT = RedtaleEconomy.id("shop/open_edit");

	public static final Identifier C2S_CONFIG_SAVE = RedtaleEconomy.id("config/save");
	public static final Identifier S2C_CONFIG_OPEN = RedtaleEconomy.id("config/open");
	public static final Identifier S2C_CONFIG_RESULT = RedtaleEconomy.id("config/result");
	public static final Identifier S2C_ACTION = RedtaleEconomy.id("shop/action");
	/** T01：税率信息（打开商店时按玩家下发一次） */
	public static final Identifier S2C_SHOP_TAX = RedtaleEconomy.id("shop/tax");

	public static final Identifier S2C_BOUNTY = RedtaleEconomy.id("bounty/snapshot");

	public static final Identifier S2C_TASKS = RedtaleEconomy.id("tasks/snapshot");
	public static final Identifier S2C_CHECKIN = RedtaleEconomy.id("checkin/snapshot");
	public static final Identifier S2C_PASS = RedtaleEconomy.id("pass/snapshot");
	public static final Identifier S2C_GUILD = RedtaleEconomy.id("guild/snapshot");
	public static final Identifier S2C_BALANCE = RedtaleEconomy.id("balance/snapshot");
	public static final Identifier S2C_TITLES = RedtaleEconomy.id("title/snapshot");
	public static final Identifier C2S_TITLE_REQ = RedtaleEconomy.id("title/request");
	public static final Identifier C2S_TITLE_SELECT = RedtaleEconomy.id("title/select");
	public static final Identifier C2S_TITLE_REMOVE = RedtaleEconomy.id("title/remove");
	public static final Identifier C2S_TITLE_CLEAR = RedtaleEconomy.id("title/clear");
	public static final Identifier C2S_PAY = RedtaleEconomy.id("pay");
	public static final Identifier S2C_BOARD = RedtaleEconomy.id("board/snapshot");
	public static final Identifier C2S_BOARD_REQ = RedtaleEconomy.id("board/request");
	/** T16：流水查看器（请求一页 / 下发一页） */
	public static final Identifier C2S_LEDGER_REQ = RedtaleEconomy.id("ledger/request");
	public static final Identifier S2C_LEDGER = RedtaleEconomy.id("ledger/snapshot");
	// U8b：/rt <page> 直达；U9b：排行榜快照
	public static final Identifier S2C_HUB_OPEN = RedtaleEconomy.id("hub/open");
	public static final Identifier S2C_RANK = RedtaleEconomy.id("rank/snapshot");	public static final Identifier S2C_WAREHOUSE = RedtaleEconomy.id("warehouse/snapshot");
	public static final Identifier C2S_WAREHOUSE_REQ = RedtaleEconomy.id("warehouse/request");
	public static final Identifier C2S_WAREHOUSE_SORT = RedtaleEconomy.id("warehouse/sort");
	public static final Identifier C2S_WAREHOUSE_UPGRADE = RedtaleEconomy.id("warehouse/upgrade");
	public static final Identifier C2S_WAREHOUSE_PICK = RedtaleEconomy.id("warehouse/pick");
	public static final Identifier C2S_WAREHOUSE_PLACE = RedtaleEconomy.id("warehouse/place");
	public static final Identifier C2S_WAREHOUSE_QUICK = RedtaleEconomy.id("warehouse/quick");
	public static final Identifier C2S_WAREHOUSE_QUICK_INV = RedtaleEconomy.id("warehouse/quickinv");
	public static final Identifier C2S_WAREHOUSE_HOTBAR = RedtaleEconomy.id("warehouse/hotbar");
	public static final Identifier C2S_WAREHOUSE_PLACE_INV = RedtaleEconomy.id("warehouse/placeinv");
	public static final Identifier C2S_WAREHOUSE_SPREAD = RedtaleEconomy.id("warehouse/spread");
	public static final Identifier C2S_WAREHOUSE_COLLECT = RedtaleEconomy.id("warehouse/collect");
	public static final Identifier C2S_WAREHOUSE_INV_PICK = RedtaleEconomy.id("warehouse/invpick");
	public static final Identifier C2S_WAREHOUSE_DROP = RedtaleEconomy.id("warehouse/drop");
	public static final Identifier C2S_BALANCE_REQ = RedtaleEconomy.id("balance/request");
	/** U9：「只看活跃」开关随请求一起发（服务端按开关选缓存榜，不在请求里算）。 */
	public static final Identifier C2S_RANK_REQ = RedtaleEconomy.id("rank/request");

	// R40 玩家市场
	public static final Identifier C2S_MARKET_REQ = RedtaleEconomy.id("market/request");
	public static final Identifier C2S_MARKET_BUY = RedtaleEconomy.id("market/buy");
	public static final Identifier C2S_MARKET_CANCEL = RedtaleEconomy.id("market/cancel");
	public static final Identifier C2S_MARKET_SELL = RedtaleEconomy.id("market/sell");
	public static final Identifier S2C_MARKET = RedtaleEconomy.id("market/snapshot");
	public static final Identifier S2C_MARKET_ACTION = RedtaleEconomy.id("market/action");

	private PacketIds() {
	}
}
