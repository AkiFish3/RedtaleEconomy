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
					cycleFilter(host, 0);
					host.requestShopPage();
				}
				case FILTER_CAT -> {
					cycleFilter(host, 1);
					host.requestShopPage();
				}
				case FILTER_PRICE -> {
					cycleFilter(host, 2);
					host.requestShopPage();
				}
				case FILTER_STOCK -> {
					cycleFilter(host, 3);
					host.requestShopPage();
				}
				// T69：密度档循环（只改客户端偏好，立刻 save，不重新请求）
				case DENSITY -> {
					int[][] presets = {{0, 0}, {6, 4}, {8, 5}, {10, 6}};
					int cur = 0;
					for (int i = 0; i < presets.length; i++) {
						if (presets[i][0] == ClientUiConfig.shopCols
								&& presets[i][1] == ClientUiConfig.shopRows) {
							cur = i;
							break;
						}
					}
					int[] next = presets[(cur + 1) % presets.length];
					ClientUiConfig.shopCols = next[0];
					ClientUiConfig.shopRows = next[1];
					ClientUiConfig.save();
					host.shopSelected = -1;
					host.setStatus("商店密度：" + (next[0] == 0 ? "自动" : next[0] + "×" + next[1]));
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

	/**
	 * T32 批次6：点筛选 chip → 循环下一个取值（""/"全部" 也算一档，循环回得去）。
	 *
	 * <p>原在 {@link HubScreen}，与商店页其余点击逻辑放在一起更内聚。
	 */
	private static void cycleFilter(HubScreen host, int slot) {
		var f = ShopClientState.filter();
		var page = ShopClientState.page();
		switch (slot) {
			case 0 -> {
				var mods = page.mods();
				int i = mods.indexOf(f.modOrEmpty());
				String next = (i < 0 || i + 1 >= mods.size()) ? "" : mods.get(i + 1);
				ShopClientState.setFilter(new com.akira.redtale.economy.net.ShopFilter(
						next, f.categoryOrEmpty(), f.minPrice(), f.maxPrice(), f.inStockOnly()));
			}
			case 1 -> {
				var cats = page.categories();
				int i = cats.indexOf(f.categoryOrEmpty());
				String next = (i < 0 || i + 1 >= cats.size()) ? "" : cats.get(i + 1);
				ShopClientState.setFilter(new com.akira.redtale.economy.net.ShopFilter(
						f.modOrEmpty(), next, f.minPrice(), f.maxPrice(), f.inStockOnly()));
			}
			case 2 -> {
				long[] steps = {0, 10, 100, 1_000, 10_000, 100_000};
				int i = 0;
				for (int k = 0; k < steps.length; k++) {
					if (steps[k] == f.maxPrice()) {
						i = k;
						break;
					}
				}
				long next = steps[(i + 1) % steps.length];
				ShopClientState.setFilter(new com.akira.redtale.economy.net.ShopFilter(
						f.modOrEmpty(), f.categoryOrEmpty(), 0, next, f.inStockOnly()));
			}
			default -> ShopClientState.setFilter(new com.akira.redtale.economy.net.ShopFilter(
					f.modOrEmpty(), f.categoryOrEmpty(), f.minPrice(), f.maxPrice(), !f.inStockOnly()));
		}
		host.shopSelected = -1;
	}

}
