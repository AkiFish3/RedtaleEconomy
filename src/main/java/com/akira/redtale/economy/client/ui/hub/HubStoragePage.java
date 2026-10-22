package com.akira.redtale.economy.client.ui.hub;

import com.akira.redtale.economy.client.warehouse.WarehouseClientNetworking;
import com.akira.redtale.economy.net.WarehouseProtocol;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;

import java.util.List;
import java.util.Locale;

/**
 * Hub 内的仓储页（完整版）：仓储网格 + 玩家背包 + 搜索/分类/整理/升级。
 *
 * <p>点击规则：仓储格左键取 1、Shift 取整叠；背包格左键存 1、Shift 存整叠。
 */
public final class HubStoragePage {

	public static final String[] CATS = {"全部", "方块", "工具/武器", "食物", "其他"};

	/** 原版箱子贴图，用它的槽位底纹让自绘格子看起来原生。 */
	private static final net.minecraft.util.Identifier SLOT_TEX =
			new net.minecraft.util.Identifier("minecraft", "textures/gui/container/generic_54.png");

	private static final int CELL = 18;
	private static final int GAP = 2;
	private static final int INV_COLS = 9;

	private HubStoragePage() {
	}

	/**
	 * 列数：宽屏 18 列（铺满内容区），窄屏 9 列。
	 *
	 * <p>固定成 9 的倍数，这样「一行」= 一个扩容档位（服务端扩容按 18 格/行递增）。
	 */
	private static int cols(int w) {
		int fit = (w + GAP) / (CELL + GAP);
		return fit >= 18 ? 18 : 9;
	}

	public static void render(DrawContext dc, TextRenderer tr, int x, int y, int w, int h,
			String query, int cat, int scrollRows, double mouseX, double mouseY) {
		WarehouseProtocol.Snapshot s = WarehouseClientNetworking.LAST;
		int slots = WarehouseClientNetworking.SLOT_COUNT;

		dc.drawText(tr, Text.literal("仓储"), x, y, HubTheme.TITLE, false);
		String info = slots + " 格 · 已购 " + s.purchased();
		dc.drawText(tr, Text.literal(info), x + 36, y, HubTheme.MUTED, false);

		int bx = x + w - 132;
		String up = WarehouseClientNetworking.NEXT_SLOTS <= 0
				? "已满"
				: ("升级 +" + Math.max(0, WarehouseClientNetworking.NEXT_SLOTS - slots));
		button(dc, tr, bx, y - 1, 66, 14, up, HubWidgets.Variant.PRIMARY, mouseX, mouseY);
		button(dc, tr, bx + 70, y - 1, 52, 14, "整理", HubWidgets.Variant.SECONDARY, mouseX, mouseY);

		int sy = y + 16;
		dc.fill(x, sy, x + w - 92, sy + 16, HubTheme.CARD);
		dc.fill(x, sy + 15, x + w - 92, sy + 16, HubTheme.LINE);
		dc.drawText(tr, Text.literal(query.isEmpty() ? "搜索名称…" : query), x + 4, sy + 4,
				query.isEmpty() ? HubTheme.MUTED : HubTheme.TEXT, false);
		button(dc, tr, x + w - 88, sy, 88, 16, CATS[cat] + " ▾", HubWidgets.Variant.OUTLINE, mouseX, mouseY);
		catOpenNow = catOpen;

		int cols = cols(w);
		int gy = sy + 22;
		int gridBottom = y + h - 92;
		int visibleRows = Math.max(1, (gridBottom - gy) / (CELL + GAP));
		int totalRows = (slots + cols - 1) / cols;
		scrollRows = Math.max(0, Math.min(scrollRows, Math.max(0, totalRows - visibleRows)));		List<ItemStack> stacks = WarehouseClientNetworking.SLOTS;
		dc.enableScissor(x, gy, x + w, Math.max(gy + 1, gridBottom));
		for (int i = 0; i < slots; i++) {
			int cx = x + (i % cols) * (CELL + GAP);
			int cy = gy + (i / cols - scrollRows) * (CELL + GAP);
			if (cy + CELL > gridBottom) break;
			if (cy < gy) continue;
			ItemStack st = i < stacks.size() ? stacks.get(i) : ItemStack.EMPTY;
			cell(dc, tr, cx, cy, st, !match(st, query, cat), mouseX, mouseY);
		}
		dc.disableScissor();
		if (slots == 0) {
			String msg = WarehouseClientNetworking.LOADED
					? "还没有仓储空间，点右上角「升级」购买"
					: "正在读取仓储…";
			dc.drawText(tr, Text.literal(msg), x + 2, gy + 4, HubTheme.MUTED, false);
		} else if (totalRows > visibleRows) {
			// 右侧细滚动条
			int barH = Math.max(10, (gridBottom - gy) * visibleRows / totalRows);
			int barY = gy + (gridBottom - gy - barH) * scrollRows / Math.max(1, totalRows - visibleRows);
			dc.fill(x + w - 2, gy, x + w - 1, gridBottom, HubTheme.LINE);
			dc.fill(x + w - 2, barY, x + w - 1, barY + barH, HubTheme.MUTED);
		}

		hovered = ItemStack.EMPTY;
		int invLabelY = y + h - 92;
		dc.drawText(tr, Text.literal("背包"), x, invLabelY, HubTheme.MUTED, false);
		var mc = MinecraftClient.getInstance();
		var inv = mc.player == null ? null : mc.player.getInventory();
		for (int i = 0; i < 36 && inv != null; i++) {
			int cx = x + (i % INV_COLS) * (CELL + GAP);
			int cy = invLabelY + 12 + (i / INV_COLS) * (CELL + GAP);
			if (cy + CELL > y + h) break;
			ItemStack st = inv.getStack(displayToSlot(i));
			cell(dc, tr, cx, cy, st, !match(st, query, cat), mouseX, mouseY);
		}
		if (hovered != null && !hovered.isEmpty()) {
			dc.drawTooltip(tr, java.util.List.of(hovered.getName()), (int) mouseX, (int) mouseY);
		}

		// 二次确认（价格来自 warehouse.json 的 upgrade.steps）
		if (confirmUpgrade) {
			long price = WarehouseClientNetworking.NEXT_PRICE;
			long prem = WarehouseClientNetworking.NEXT_PREMIUM;
			// T55/T67：金额统一走 HubMoney（硬币字形优先，字体缺失回退纯文字）；赞助点仍是纯文字
			net.minecraft.text.MutableText cost;
			if (price > 0) cost = Text.literal("花 ").append(HubMoney.asText(price, false));
			else if (prem > 0) cost = Text.literal("花 " + prem + " 赞助点");
			else cost = Text.literal("免费");
			Text q = Text.literal("确认扩容到 " + WarehouseClientNetworking.NEXT_SLOTS + " 格 · ")
					.append(cost).append("？");
			final int cw2 = Math.min(w, 300);
			dc.fill(x, y + 16, x + cw2, y + 34, HubTheme.CARD);
			dc.fill(x, y + 16, x + cw2, y + 17, HubTheme.LINE);
			dc.drawText(tr, q, x + 5, y + 22, HubTheme.TEXT, false);
			button(dc, tr, x + cw2 - 74, y + 18, 34, 14, "确认", HubWidgets.Variant.PRIMARY, mouseX, mouseY);
			button(dc, tr, x + cw2 - 38, y + 18, 34, 14, "取消", HubWidgets.Variant.OUTLINE, mouseX, mouseY);
		}

	}

	private static ItemStack hovered;

	private static void cell(DrawContext dc, TextRenderer tr, int cx, int cy, ItemStack st,
			boolean dim, double mouseX, double mouseY) {
		boolean hover = mouseX >= cx && mouseX < cx + CELL && mouseY >= cy && mouseY < cy + CELL;
		// 原版槽位底纹（generic_54.png 里 18×18 的凹槽）
		dc.drawTexture(SLOT_TEX, cx, cy, 7, 17, CELL, CELL, 256, 256);
		if (st == null || st.isEmpty()) return;
		dc.drawItem(st, cx + 1, cy + 1);
		// ★用原版渲染数量/耐久条★，位置与原版容器完全一致
		dc.drawItemInSlot(tr, st, cx + 1, cy + 1);
		// 高亮要在物品**之上**（原版鼠标悬停是白色覆盖层）
		// ★高亮在物品之上、光标之下★：半透明白罩 + 1px 白框
		if (hover) {
			dc.fill(cx + 1, cy + 1, cx + CELL - 1, cy + CELL - 1, HubTheme.SLOT_HILITE);
			dc.fill(cx, cy, cx + CELL, cy + 1, HubTheme.SLOT_EDGE);
			dc.fill(cx, cy + CELL - 1, cx + CELL, cy + CELL, HubTheme.SLOT_EDGE);
			dc.fill(cx, cy, cx + 1, cy + CELL, HubTheme.SLOT_EDGE);
			dc.fill(cx + CELL - 1, cy, cx + CELL, cy + CELL, HubTheme.SLOT_EDGE);
		}
		if (dim) dc.fill(cx + 1, cy + 1, cx + CELL - 1, cy + CELL - 1, HubTheme.SLOT_DIM);
		if (hover) hovered = st.copy();
	}

	private static void button(DrawContext dc, TextRenderer tr, int x, int y, int w, int h,
			String label, HubWidgets.Variant variant, double mouseX, double mouseY) {
		boolean hover = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
		HubWidgets.button(dc, tr, x, y, w, h, label, variant, hover, false, true);
	}

	/** 显示顺序 → 真实背包槽位：前 27 格是主背包(9..35)，后 9 格是快捷栏(0..8)。 */
	public static int displayToSlot(int display) {
		return display < 27 ? display + 9 : display - 27;
	}

	public static boolean visible(ItemStack st, String query, int cat) {
		return match(st, query, cat);
	}

	private static boolean match(ItemStack st, String query, int cat) {
		if (st == null || st.isEmpty()) return true;
		if (!catOk(st, cat)) return false;
		if (query.isEmpty()) return true;
		String name = st.getName().getString().toLowerCase(Locale.ROOT);
		String id = net.minecraft.registry.Registries.ITEM.getId(st.getItem()).toString()
				.toLowerCase(Locale.ROOT);
		for (String kw : query.toLowerCase(Locale.ROOT).split("\\s+")) {
			if (kw.isEmpty()) continue;
			if (!name.contains(kw) && !id.contains(kw)) return false;
		}
		return true;
	}

	private static boolean catOk(ItemStack st, int cat) {
		return switch (cat) {
			case 1 -> st.getItem() instanceof BlockItem;
			case 2 -> st.isDamageable() || st.getItem() instanceof net.minecraft.item.ToolItem;
			case 3 -> st.getItem().getFoodComponent() != null;
			case 4 -> !(st.getItem() instanceof BlockItem) && !st.isDamageable()
					&& st.getItem().getFoodComponent() == null;
			default -> true;
		};
	}

	/** 光标上抓着的东西画在鼠标位置（原版手感）。 */
	public static void drawCursor(DrawContext dc, TextRenderer tr, double mouseX, double mouseY) {
		ItemStack cur = WarehouseClientNetworking.CURSOR;
		if (cur == null || cur.isEmpty()) return;
		dc.drawItem(cur, (int) mouseX - 8, (int) mouseY - 8);
		dc.drawItemInSlot(tr, cur, (int) mouseX - 8, (int) mouseY - 8);
	}

	/** 二次确认状态（打开/关闭 UI 时由 HubScreen 重置）。 */
	public static boolean confirmUpgrade;

	/** 分类下拉是否展开（HubScreen 在最后统一绘制列表）。 */
	public static boolean catOpen;
	private static boolean catOpenNow;

	public static boolean needsCatOverlay() {
		return catOpenNow;
	}

	/** 分类下拉列表：在底栏/内容之后画，保证在最上层。 */
	public static void drawCatDropdown(DrawContext dc, TextRenderer tr, int x, int y, int w,
			int cat, double mouseX, double mouseY) {
		int bw = 88;
		int bx = x + w - bw;
		int listH = CATS.length * 16;
		int by = (y + 16 + listH <= y + 240) ? y + 16 : y - listH;
		dc.fill(bx, by, bx + bw, by + listH, HubTheme.PANEL);
		HubWidgets.outline(dc, bx, by, bw, listH, 2, HubTheme.OUTLINE);
		for (int i = 0; i < CATS.length; i++) {
			boolean on = i == cat;
			boolean hv = mouseX >= bx && mouseX < bx + bw && mouseY >= by + i * 16 && mouseY < by + i * 16 + 16;
			if (on || hv) {
				HubWidgets.roundRect(dc, bx + 1, by + i * 16 + 1, bw - 2, 14, 2,
						on ? HubTheme.SELECT : HubTheme.HOVER);
			}
			dc.drawText(tr, Text.literal(CATS[i]), bx + 6, by + i * 16 + 4,
					on ? HubTheme.PRIMARY : HubTheme.TEXT, false);
		}
	}

	/** 分类下拉命中：-2=开/关，0..n-1=选中，-1=无。 */
	public static int hitCat(double mx, double my, int x, int y, int w) {
		int bw = 88;
		int bx = x + w - bw;
		int sy = y + 16;
		if (mx >= bx && mx < bx + bw && my >= sy && my < sy + 16) return -2;
		if (catOpen) {
			int listH = CATS.length * 16;
			int by = (sy + 16 + listH <= y + 240) ? sy + 16 : sy - listH;
			for (int i = 0; i < CATS.length; i++) {
				if (mx >= bx && mx < bx + bw && my >= by + i * 16 && my < by + i * 16 + 16) return i;
			}
		}
		return -1;
	}

	public enum Hit { NONE, STORAGE, INV, UPGRADE, SORT, CAT, CONFIRM_YES, CONFIRM_NO }

	public record Result(Hit hit, int index) {
	}

	/**
	 * 仓储网格的滚动上限（行数）。
	 *
	 * <p>★渲染原来自己 clamp、命中用未 clamp 的值★ ⇒ 滚轮下滑几次之后
	 * `host.storageScroll` 已经超过真实上限，渲染显示的是钳位后的窗口、点击却按
	 * 原始值算行 ⇒ 点到的格子与看到的错位（仓储是搬物品的地方，错位会搬错东西）。
	 * 现在滚轮、渲染、命中三处都走这一个函数。
	 */
	public static int maxScrollRows(int w, int h) {
		int cols = cols(w);
		int visibleRows = Math.max(1, ((h - 92) - (16 + 22)) / (CELL + GAP));
		int totalRows = (WarehouseClientNetworking.SLOT_COUNT + cols - 1) / cols;
		return Math.max(0, totalRows - visibleRows);
	}

	public static Result click(double mx, double my, int x, int y, int w, int h, int button,
			int scrollRows) {
		int bx = x + w - 132;
		scrollRows = Math.max(0, Math.min(scrollRows, maxScrollRows(w, h)));
		if (confirmUpgrade && my >= y + 18 && my < y + 32) {
			final int cw2 = Math.min(w, 300);
			if (mx >= x + cw2 - 74 && mx < x + cw2 - 40) return new Result(Hit.CONFIRM_YES, -1);
			if (mx >= x + cw2 - 38 && mx < x + cw2 - 4) return new Result(Hit.CONFIRM_NO, -1);
		}
		if (mx >= bx && mx < bx + 66 && my >= y - 1 && my < y + 13) return new Result(Hit.UPGRADE, -1);
		if (mx >= bx + 70 && mx < bx + 122 && my >= y - 1 && my < y + 13) return new Result(Hit.SORT, -1);

		int sy = y + 16;
		if (mx >= x + w - 88 && mx < x + w && my >= sy && my < sy + 16) return new Result(Hit.CAT, -1);

		int slotCount = WarehouseClientNetworking.SLOT_COUNT;
		int cols = cols(w);
		int gy = sy + 22;
		int gridBottom = y + h - 92;
		for (int i = 0; i < slotCount; i++) {
			int cx = x + (i % cols) * (CELL + GAP);
			int cy = gy + (i / cols - scrollRows) * (CELL + GAP);
			if (cy + CELL > gridBottom) break;
			if (cy < gy) continue;
			if (mx >= cx && mx < cx + CELL && my >= cy && my < cy + CELL) {
				return new Result(Hit.STORAGE, i);
			}
		}

		hovered = ItemStack.EMPTY;
		int invLabelY = y + h - 92;
		for (int i = 0; i < 36; i++) {
			int cx = x + (i % INV_COLS) * (CELL + GAP);
			int cy = invLabelY + 12 + (i / INV_COLS) * (CELL + GAP);
			if (cy + CELL > y + h) break;
			if (mx >= cx && mx < cx + CELL && my >= cy && my < cy + CELL) {
				return new Result(Hit.INV, i);
			}
		}
		return new Result(Hit.NONE, -1);
	}
}
