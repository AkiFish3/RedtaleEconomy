package com.akira.redtale.economy.client.tasks;

import com.akira.redtale.economy.economy.Currency;
import com.akira.redtale.economy.net.TaskProtocol;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

/**
 * 每日任务面板（U2）。
 *
 * <p>数据来自服务端一次性快照（{@link TaskProtocol.Snapshot}），面板只读。
 * 「缴纳 / 领取」按钮发的是 {@code /tasks submit|claim <slot>} 命令——
 * 扣物品、校验数量、发奖励全在服务端命令里，面板绕不过去。
 */
@Environment(EnvType.CLIENT)
public final class TaskScreen extends Screen {

	private static final int CARD_H = 46;
	private static final int PANEL_W = 340;
	private static final int PAD = 8;

	private final TaskProtocol.Snapshot snap;
	private int left;
	private int top;

	public TaskScreen(TaskProtocol.Snapshot snap) {
		super(Text.translatable("gui.redtale_economy.tasks.title"));
		this.snap = snap == null ? TaskProtocol.Snapshot.empty() : snap;
	}

	private int visible() {
		return Math.min(snap.tasks().size(), 6);
	}

	private int panelH() {
		return 46 + Math.max(1, visible()) * CARD_H + 34;
	}

	@Override
	protected void init() {
		left = (width - PANEL_W) / 2;
		top = (height - panelH()) / 2;

		int rowY = top + 52;
		int shown = 0;
		for (TaskProtocol.Task t : snap.tasks()) {
			if (shown >= visible()) break;
			final int slot = t.slot();
			final String mode = t.mode();
			int by = rowY + shown * CARD_H;
			ButtonWidget b = ButtonWidget.builder(
					Text.translatable(t.claimed() ? "gui.redtale_economy.tasks.done"
							: ("detect".equals(mode) ? "gui.redtale_economy.tasks.claim"
									: "gui.redtale_economy.tasks.submit")),
					btn -> fire(t))
					.dimensions(left + PANEL_W - 92, by + 11, 80, 18)
					.build();
			b.active = !t.claimed() && enough(t);
			addDrawableChild(b);
			shown++;
		}

		addDrawableChild(ButtonWidget.builder(
				Text.translatable("gui.redtale_economy.tasks.refresh"),
				b -> cmd("tasks refresh", true))
				.dimensions(left + PANEL_W - 92, top + panelH() - 26, 80, 20)
				.build());

		addDrawableChild(ButtonWidget.builder(
				Text.translatable("gui.redtale_economy.tasks.close"),
				b -> close())
				.dimensions(left + PAD, top + panelH() - 26, 70, 20)
				.build());
	}

	/** 服务端已经算好 have，这里只做「够不够」的显示判断。 */
	private static boolean enough(TaskProtocol.Task t) {
		for (TaskProtocol.Item i : t.items()) {
			if (i.have() < i.need()) return false;
		}
		return true;
	}

	private void fire(TaskProtocol.Task t) {
		String sub = "detect".equals(t.mode()) ? "claim" : "submit";
		cmd("tasks " + sub + " " + t.slot(), true);
	}

	private void cmd(String command, boolean closeAfter) {
		MinecraftClient c = MinecraftClient.getInstance();
		if (c.getNetworkHandler() == null) return;
		c.getNetworkHandler().sendChatCommand(command);
		if (closeAfter) close();
	}

	@Override
	public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
		// ★顺序★：先画面板与卡片，最后 super.render 画原版按钮 ——
		// 反过来会让面板盖住按钮底图，只剩一行文字（U6 截图里就是这样）。
		com.akira.redtale.economy.client.ui.UiStyle.panel(ctx, left, top, PANEL_W, panelH());

		ctx.drawTextWithShadow(textRenderer, title, left + PAD + 2, top + 10, com.akira.redtale.economy.client.ui.UiStyle.TEXT);
		ctx.drawTextWithShadow(textRenderer,
				Text.literal(snap.date() + "   " + Text.translatable("gui.redtale_economy.tasks.refreshes",
						snap.refreshesLeft()).getString()),
				left + PAD, top + 26, com.akira.redtale.economy.client.ui.UiStyle.TEXT_DIM);
		com.akira.redtale.economy.client.ui.UiStyle.divider(ctx, left + PAD, top + 42, PANEL_W - PAD * 2);

		List<TaskProtocol.Task> tasks = snap.tasks();
		if (tasks.isEmpty()) {
			ctx.drawCenteredTextWithShadow(textRenderer,
					Text.translatable("gui.redtale_economy.tasks.empty"),
					left + PANEL_W / 2, top + 66, com.akira.redtale.economy.client.ui.UiStyle.TEXT_DIM);
			ctx.drawCenteredTextWithShadow(textRenderer,
					Text.literal("每天 0 点（core.resetHour）自动换一批 · 剩 " + snap.refreshesLeft() + " 次手动刷新"),
					left + PANEL_W / 2, top + 82, com.akira.redtale.economy.client.ui.UiStyle.TEXT_FAINT);
		}

		int shown = 0;
		for (TaskProtocol.Task t : tasks) {
			if (shown >= visible()) break;
			int y = top + 52 + shown * CARD_H;
			int ch = CARD_H - 6;
			boolean hover = mouseX >= left + PAD && mouseX < left + PANEL_W - PAD
					&& mouseY >= y && mouseY < y + ch;
			com.akira.redtale.economy.client.ui.UiStyle.card(ctx, left + PAD, y, PANEL_W - PAD * 2, ch, hover);
			// 左侧强调条：已领灰、可缴纳金、未完成暗
			int bar = t.claimed() ? com.akira.redtale.economy.client.ui.UiStyle.TEXT_FAINT
					: (enough(t) ? com.akira.redtale.economy.client.ui.UiStyle.ACCENT
							: com.akira.redtale.economy.client.ui.UiStyle.BORDER);
			ctx.fill(left + PAD, y, left + PAD + 2, y + ch, bar);
			// 标题：池的 displayName；没有就退回 #slot（旧存档 / 池没写 displayName）
			String title = t.label() == null || t.label().isBlank() ? ("#" + t.slot()) : t.label();
			String head = title + (t.claimed() ? "   已完成" : "");
			ctx.drawTextWithShadow(textRenderer,
					Text.literal(trim(head, 118)),
					left + PAD + 8, y + 5,
					t.claimed() ? com.akira.redtale.economy.client.ui.UiStyle.TEXT_FAINT
							: com.akira.redtale.economy.client.ui.UiStyle.TEXT);
			String reward = format(t.reward());
			// 右对齐到「缴纳按钮左边缘 - 8」，否则会被按钮盖住
			ctx.drawTextWithShadow(textRenderer, Text.literal(reward),
					left + PANEL_W - 100 - textRenderer.getWidth(reward), y + 5,
					t.claimed() ? com.akira.redtale.economy.client.ui.UiStyle.TEXT_FAINT
							: com.akira.redtale.economy.client.ui.UiStyle.ACCENT);
			drawItems(ctx, t, left + PAD + 8, y + 24);
			shown++;
		}

		// ★按钮最后画★
		super.render(ctx, mouseX, mouseY, delta);
	}

	/** 标题按像素宽截断，避免压到右侧奖励数字。 */
	private String trim(String s, int maxW) {
		if (textRenderer.getWidth(s) <= maxW) return s;
		String out = s;
		while (out.length() > 1 && textRenderer.getWidth(out + "…") > maxW) {
			out = out.substring(0, out.length() - 1);
		}
		return out + "…";
	}

	/** 物品行：图标 + 「数量 持有/需要」，图标从注册表取，取不到就只画文字。 */
	private void drawItems(DrawContext ctx, TaskProtocol.Task t, int x, int y) {
		int cx = x;
		for (TaskProtocol.Item i : t.items()) {
			boolean ok = i.have() >= i.need();
			ItemStack stack = ItemStack.EMPTY;
			try {
				var item = net.minecraft.registry.Registries.ITEM.get(new net.minecraft.util.Identifier(i.id()));
				stack = new ItemStack(item);
			} catch (Throwable ignored) {
				// 未知 id 只画文字
			}
			if (!stack.isEmpty()) {
				ctx.drawItem(stack, cx, y - 4);
				cx += 18;
			}
			String label = i.have() + "/" + i.need();
			ctx.drawTextWithShadow(textRenderer, Text.literal(label), cx, y,
					ok ? com.akira.redtale.economy.client.ui.UiStyle.GOOD
							: com.akira.redtale.economy.client.ui.UiStyle.BAD);
			cx += textRenderer.getWidth(label) + 10;
		}
	}


	/**
	 * 金额：跳过 0 单位（§37.2 数字右对齐、金额金色）。
	 * {@code Currency.format} 会输出「0 金 4 银 20 铜」，卡片 118px 宽放不下也没意义。
	 */
	private static String format(long raw) {
		try {
			long gc = Math.max(1, Currency.goldInCopper());
			long sc = Math.max(1, Currency.silverInCopper());
			long v = Math.max(0, raw);
			long gold = v / gc;
			long rest = v % gc;
			long silver = rest / sc;
			long copper = rest % sc;
			StringBuilder sb = new StringBuilder();
			if (gold > 0) sb.append(gold).append(" 金 ");
			if (silver > 0) sb.append(silver).append(" 银 ");
			if (copper > 0 || sb.isEmpty()) sb.append(copper).append(" 铜");
			return sb.toString().trim();
		} catch (Throwable e) {
			return Long.toString(raw);
		}
	}

	@Override
	public boolean shouldPause() {
		return false;
	}
}
