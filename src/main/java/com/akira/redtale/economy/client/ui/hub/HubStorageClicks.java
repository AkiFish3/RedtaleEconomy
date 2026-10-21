package com.akira.redtale.economy.client.ui.hub;

import net.minecraft.client.MinecraftClient;

/** T32 批次2：仓储页点击 + 拖拽状态机入口（原 HubScreen.handleClick 内 STORAGE 段）。 */
final class HubStorageClicks {
	private HubStorageClicks() {
	}

	static HubClicks.R handle(HubScreen host, double mouseX, double mouseY, int button) {
		if ((button == 0 || button == 1) && host.page == HubPage.STORAGE) {
			int cx = host.frame.contentX();
			int cy = host.frame.contentY();
			int cw = host.frame.contentW();
			int ch = host.frame.contentH();
			var r = HubStoragePage.click(mouseX, mouseY, cx, cy, cw, ch, button, host.storageScroll);
			boolean shift = host.shiftDown();
			var wnet = com.akira.redtale.economy.client.warehouse.WarehouseClientNetworking.class;
			if (r.hit() == HubStoragePage.Hit.UPGRADE) {
				if (button == 0) {
					if (com.akira.redtale.economy.client.warehouse.WarehouseClientNetworking.NEXT_SLOTS <= 0) {
						host.setStatus("仓储已是最大格数");
						return HubClicks.R.HANDLED;
					}
					HubStoragePage.confirmUpgrade = true;
				}
				return HubClicks.R.HANDLED;
			}
			if (r.hit() == HubStoragePage.Hit.CONFIRM_YES) {
				HubStoragePage.confirmUpgrade = false;
				com.akira.redtale.economy.client.warehouse.WarehouseClientNetworking.upgrade();
				host.setStatus("正在扩容…");
				return HubClicks.R.HANDLED;
			}
			if (r.hit() == HubStoragePage.Hit.CONFIRM_NO) {
				HubStoragePage.confirmUpgrade = false;
				return HubClicks.R.HANDLED;
			}
			if (r.hit() == HubStoragePage.Hit.SORT) {
				if (button == 0) {
					com.akira.redtale.economy.client.warehouse.WarehouseClientNetworking.localSort();
					com.akira.redtale.economy.client.warehouse.WarehouseClientNetworking.sort();
					host.setStatus("正在整理…");
				}
				return HubClicks.R.HANDLED;
			}
			if (r.hit() == HubStoragePage.Hit.CAT) {
				if (button == 0) {
					int hit = HubStoragePage.hitCat(mouseX, mouseY, cx, cy, cw);
					if (hit == -2) {
						HubStoragePage.catOpen = !HubStoragePage.catOpen;
						ClientUiConfig.click();
					} else if (hit >= 0) {
						host.storageCat = hit;
						HubStoragePage.catOpen = false;
						ClientUiConfig.click();
					}
				}
				return HubClicks.R.HANDLED;
			}
			if (r.hit() == HubStoragePage.Hit.STORAGE || r.hit() == HubStoragePage.Hit.INV) {
				int idx = r.index();
				int key = (r.hit() == HubStoragePage.Hit.STORAGE ? 1000 : 0) + idx;
				long now = System.currentTimeMillis();
				boolean dbl = now - host.lastSlotAt < 400 && !host.dragPlacing;
				host.lastSlotKey = key;
				host.lastSlotAt = now;

				// ★必须用镜像 CURSOR★：LAST.cursor() 要等服务端回包，会滞后一帧导致“抓起来又当成空手”
				var cur = com.akira.redtale.economy.client.warehouse.WarehouseClientNetworking.CURSOR;
				boolean holding = cur != null && !cur.isEmpty();

				if (dbl && holding) {
					com.akira.redtale.economy.client.warehouse.WarehouseClientNetworking.localCollect();
					com.akira.redtale.economy.client.warehouse.WarehouseClientNetworking.collect();
					return HubClicks.R.HANDLED;
				}
				if (r.hit() == HubStoragePage.Hit.STORAGE) {
					var snap = com.akira.redtale.economy.client.warehouse.WarehouseClientNetworking.LAST;
					if (idx >= 0 && idx < snap.stacks().size()) {
						var st = snap.stacks().get(idx);
						if (st != null && !st.isEmpty()
								&& !HubStoragePage.visible(st, host.storageQuery.toString(), host.storageCat)) {
							return HubClicks.R.HANDLED;
						}
					}
					if (shift) {
						com.akira.redtale.economy.client.warehouse.WarehouseClientNetworking.localQuickOut(idx);
						com.akira.redtale.economy.client.warehouse.WarehouseClientNetworking.quick(idx);
						host.dragPlacing = true;
						host.dragActive = true;
						host.dragButton = button;
						host.lastDragSlot = (r.hit() == HubStoragePage.Hit.STORAGE ? 1000 : 0) + idx;
					} else if (holding) {
						// ★按下不放下★：先进入拖拽态，松手时若没拖动才整叠放下
						host.dragActive = true;
						host.dragButton = button;
						host.pendingIdx = idx;
						host.pendingInv = false;
						host.pendingAll = button == 0;
						host.pressKey = 1000 + idx;
						host.lastDragSlot = host.pressKey;
						host.dragSlots.clear();
						host.dragSlots.add(idx);
					} else {
						com.akira.redtale.economy.client.warehouse.WarehouseClientNetworking.localPick(
								idx, button == 1);
						com.akira.redtale.economy.client.warehouse.WarehouseClientNetworking.pick(
								idx, button == 1);
						host.dragPlacing = true;
						host.dragActive = true;
						host.dragButton = button;
						host.lastDragSlot = (r.hit() == HubStoragePage.Hit.STORAGE ? 1000 : 0) + idx;
					}
				} else {
					final int realSlot = HubStoragePage.displayToSlot(idx);
					var mc2 = MinecraftClient.getInstance();
					if (mc2.player != null) {
						var st2 = mc2.player.getInventory().getStack(realSlot);
						if (!st2.isEmpty()
								&& !HubStoragePage.visible(st2, host.storageQuery.toString(), host.storageCat)) {
							return HubClicks.R.HANDLED;
						}
					}
					if (shift) {
						com.akira.redtale.economy.client.warehouse.WarehouseClientNetworking.localQuickIn(realSlot);
						com.akira.redtale.economy.client.warehouse.WarehouseClientNetworking.quickInv(realSlot);
						host.dragPlacing = true;
						host.dragActive = true;
						host.dragButton = button;
						host.lastDragSlot = (r.hit() == HubStoragePage.Hit.STORAGE ? 1000 : 0) + idx;
					} else if (!holding) {
						com.akira.redtale.economy.client.warehouse.WarehouseClientNetworking.localInvPick(
								realSlot, button == 1);
						com.akira.redtale.economy.client.warehouse.WarehouseClientNetworking.invPick(
								realSlot, button == 1);
						host.dragPlacing = true;
						host.dragActive = true;
						host.dragButton = button;
						host.lastDragSlot = (r.hit() == HubStoragePage.Hit.STORAGE ? 1000 : 0) + idx;
					} else {
						host.dragActive = true;
						host.dragButton = button;
						host.pendingIdx = realSlot;
						host.pendingInv = true;
						host.pendingAll = button == 0;
						host.pressKey = realSlot;
						host.lastDragSlot = realSlot;
					}
				}
				return HubClicks.R.HANDLED;
			}
			return HubClicks.R.HANDLED;
		}
		return HubClicks.R.PASS;
	}

}
