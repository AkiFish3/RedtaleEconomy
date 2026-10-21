package com.akira.redtale.economy.client.ui.hub;

import com.akira.redtale.economy.client.ShopClientState;
import com.akira.redtale.economy.client.ShopClientNetworking;

/** T32 批次2：商店页点击（筛选循环仍留在 HubScreen，改为包私有）。 */
final class HubShopClicks {
	private HubShopClicks() {
	}

	static HubClicks.R handle(HubScreen host, double mouseX, double mouseY, int button) {
		if (button == 0 && host.page == HubPage.SHOP) {
			int cx = host.frame.contentX();
			int cy = host.frame.contentY();
			int cw = host.frame.contentW();
			int ch = host.frame.contentH();
			int[] idx = {host.shopSelected};
			String[] outCat = {""};
			HubShopPage.Action a = HubShopPage.click(host.text(), mouseX, mouseY, cx, cy, cw, ch,
					host.shopSelected, idx, outCat);
			if (a == HubShopPage.Action.NONE && idx[0] != host.shopSelected) {
				host.shopSelected = idx[0];
				return HubClicks.R.HANDLED;
			}
			var st = ShopClientState.page();
			switch (a) {
				case SYNC -> {
					host.requestShopPage();
					host.setStatus("正在同步商品…");
				}
				case PREV -> {
					ShopClientNetworking.requestPage(st.shopId(), Math.max(0, st.page() - 1), host.shopQuery.toString());
					host.shopSelected = -1;
				}
				case NEXT -> {
					ShopClientNetworking.requestPage(st.shopId(), st.page() + 1, host.shopQuery.toString());
					host.shopSelected = -1;
				}
				case BUY -> {
					if (host.shopSelected >= 0 && host.shopSelected < st.entries().size()) {
						var e = st.entries().get(host.shopSelected);
						// T01：记下这一笔的金额，结算回包后才能在状态行补「含税 x 铜」
						host.lastTradeItem = e.itemId();
						host.lastTradeAmount = e.buyPrice();
						host.lastTradeBuy = true;
						ShopClientNetworking.buy(st.shopId(), e.itemId(), 1);
						host.setStatus("正在购买 " + e.itemId());
					}
				}
				case SELL -> {
					if (host.shopSelected >= 0 && host.shopSelected < st.entries().size()) {
						var e = st.entries().get(host.shopSelected);
						host.lastTradeItem = e.itemId();
						host.lastTradeAmount = e.sellPrice();
						host.lastTradeBuy = false;
						ShopClientNetworking.sell(st.shopId(), e.itemId(), 1);
						host.setStatus("正在出售 " + e.itemId());
					}
				}
				// U11b：筛选变化一律回到第 1 页，条件随请求发服务端
				case FILTER_MOD -> {
					host.cycleFilter(0);
					host.requestShopPage();
				}
				case FILTER_CAT -> {
					host.cycleFilter(1);
					host.requestShopPage();
				}
				case FILTER_PRICE -> {
					host.cycleFilter(2);
					host.requestShopPage();
				}
				case FILTER_STOCK -> {
					host.cycleFilter(3);
					host.requestShopPage();
				}
				// U11：目录树下钻 / 回上一级 —— 树位置即筛选条件，一次点击同时完成两件事
				case TREE_NODE, TREE_UP -> {
					var f = ShopClientState.filter();
					ShopClientState.setFilter(new com.akira.redtale.economy.net.ShopFilter(
							f.modOrEmpty(), outCat[0], f.minPrice(), f.maxPrice(), f.inStockOnly()));
					host.shopSelected = -1;
					host.requestShopPage();
					host.setStatus(outCat[0].isEmpty() ? "已回到全部分类"
							: "分类：" + String.join(" › ",
									com.akira.redtale.economy.shop.ShopTree.crumbs(outCat[0])));
				}
				default -> {
				}
			}
			if (a != HubShopPage.Action.NONE) return HubClicks.R.HANDLED;
		}
		return HubClicks.R.PASS;
	}

}
