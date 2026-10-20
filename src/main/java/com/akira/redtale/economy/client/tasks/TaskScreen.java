package com.akira.redtale.economy.client.tasks;

import com.akira.redtale.economy.economy.Currency;
import com.akira.redtale.economy.net.TaskProtocol;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
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
					.dimensions(left + PANEL_W - 92, by + 12, 80, 20)
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
		super.render(ctx, mouseX, mouseY, delta);
		com.akira.redtale.economy.client.ui.UiStyle.panel(ctx, left, top, PANEL_W, panelH());

		ctx.drawCenteredTextWithShadow(textRenderer, title, left + PANEL_W / 2, top + 10, com.akira.redtale.economy.client.ui.UiStyle.TEXT);
		ctx.drawTextWithShadow(textRenderer,
				Text.literal(snap.date() + "   " + Text.translatable("gui.redtale_economy.tasks.refreshes",
						snap.refreshesLeft()).getString()),
				left + PAD, top + 26, com.akira.redtale.economy.client.ui.UiStyle.TEXT_DIM);

		List<TaskProtocol.Task> tasks = snap.tasks();
		if (tasks.isEmpty()) {
			ctx.drawCenteredTextWithShadow(textRenderer,
					Text.translatable("gui.redtale_economy.tasks.empty"), left + PANEL_W / 2, top + 70, com.akira.redtale.economy.client.ui.UiStyle.TEXT_DIM);
		}

		int shown = 0;
		for (TaskProtocol.Task t : tasks) {
			if (shown >= visible()) break;
			int y = top + 52 + shown * CARD_H;
			ctx.fill(left + PAD, y, left + PANEL_W - PAD, y + CARD_H - 6, com.akira.redtale.economy.client.ui.UiStyle.CARD);
			ctx.drawTextWithShadow(textRenderer,
					Text.literal("#" + t.slot() + "  " + (t.claimed() ? "§8✔" : "§a→ ") + format(t.reward())),
					left + PAD + 4, y + 5, com.akira.redtale.economy.client.ui.UiStyle.ACCENT);
			ctx.drawTextWithShadow(textRenderer, itemLine(t), left + PAD + 4, y + 20, com.akira.redtale.economy.client.ui.UiStyle.TEXT);
			shown++;
		}
	}

	private Text itemLine(TaskProtocol.Task t) {
		List<String> parts = new ArrayList<>();
		for (TaskProtocol.Item i : t.items()) {
			String mark = i.have() >= i.need() ? "§a" : "§c";
			parts.add(mark + i.id() + "§7 " + i.have() + "/" + i.need());
		}
		return Text.literal(String.join("§7, ", parts));
	}

	private static String format(long raw) {
		try {
			return Currency.format(raw);
		} catch (Throwable e) {
			return Long.toString(raw);
		}
	}

	@Override
	public boolean shouldPause() {
		return false;
	}
}
