package com.akira.redtale.economy.client.ui.hub;

/** T32 批次2：市场页点击（原 HubScreen.handleClick 内 R40 段，逻辑零改动）。 */
final class HubMarketClicks {
	private HubMarketClicks() {
	}

	static HubClicks.R handle(HubScreen host, double mouseX, double mouseY, int button) {
		// R40 市场：动作全部先改客户端视图状态，再发请求；服务端回包覆盖
		if (button == 0 && host.page == HubPage.MARKET) {
			int cx = host.frame.contentX();
			int cy = host.frame.contentY();
			int cw = host.frame.contentW();
			int ch = host.frame.contentH();
			int[] idx = {-1};
			HubMarketPage.Action a = HubMarketPage.click(host.text(), mouseX, mouseY, cx, cy, cw, ch,
					host.marketSelected, idx);
			var snap = com.akira.redtale.economy.client.market.MarketClientNetworking.LAST;
			switch (a) {
				case TOGGLE_MINE -> {
					com.akira.redtale.economy.client.market.MarketClientNetworking.request(0,
							!snap.mineOnly(), com.akira.redtale.economy.client.market.MarketClientNetworking.query);
					host.marketSelected = -1;
				}
				case SYNC -> {
					com.akira.redtale.economy.client.market.MarketClientNetworking.request();
					host.setStatus("正在刷新市场…");
				}
				case CYCLE_PRICE -> HubMarketPage.cyclePrice();
				case SELL -> {
					com.akira.redtale.economy.client.market.MarketClientNetworking.sell(HubMarketPage.price());
					host.setStatus("正在挂单…");
				}
				case PREV -> {
					com.akira.redtale.economy.client.market.MarketClientNetworking.request(
							Math.max(0, snap.page() - 1), snap.mineOnly(),
							com.akira.redtale.economy.client.market.MarketClientNetworking.query);
					host.marketSelected = -1;
				}
				case NEXT -> {
					com.akira.redtale.economy.client.market.MarketClientNetworking.request(
							snap.page() + 1, snap.mineOnly(),
							com.akira.redtale.economy.client.market.MarketClientNetworking.query);
					host.marketSelected = -1;
				}
				case BUY -> {
					if (host.marketSelected >= 0 && host.marketSelected < snap.offers().size()) {
						com.akira.redtale.economy.client.market.MarketClientNetworking.buy(
								snap.offers().get(host.marketSelected).id());
						host.setStatus("正在购买…");
					}
				}
				case CANCEL -> {
					if (host.marketSelected >= 0 && host.marketSelected < snap.offers().size()) {
						com.akira.redtale.economy.client.market.MarketClientNetworking.cancel(
								snap.offers().get(host.marketSelected).id());
						host.setStatus("正在撤单…");
					}
				}
				default -> {
					if (idx[0] >= 0) host.marketSelected = idx[0];
				}
			}
			ClientUiConfig.click();
			return HubClicks.R.HANDLED;
		}
		return HubClicks.R.PASS;
	}

}
