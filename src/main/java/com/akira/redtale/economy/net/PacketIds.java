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

	public static final Identifier C2S_CONFIG_SAVE = RedtaleEconomy.id("config/save");
	public static final Identifier S2C_CONFIG_OPEN = RedtaleEconomy.id("config/open");
	public static final Identifier S2C_CONFIG_RESULT = RedtaleEconomy.id("config/result");
	public static final Identifier S2C_ACTION = RedtaleEconomy.id("shop/action");

	private PacketIds() {
	}
}
