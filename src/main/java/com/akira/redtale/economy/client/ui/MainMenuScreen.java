package com.akira.redtale.economy.client.ui;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

import java.util.List;

/**
 * 主入口面板（U4）。
 *
 * <p>一页导航：余额 / 签到 / 任务 / 商店 / 邮件 / 战令。点击即执行对应的玩家命令，
 * 面板本身不持有任何可信数据——余额、库存、能不能领都以服务端返回为准。
 *
 * <p>未实现的模块（邮件 R21、战令 R20）按钮置灰并带 tooltip，避免点了弹「未知命令」。
 * 对应命令上线后把 {@link Entry#ready()} 打开即可，无需改布局。
 */
public final class MainMenuScreen extends Screen {

	private static final int PANEL_W = 320;
	private static final int BTN_W = 148;
	private static final int BTN_H = 20;
	private static final int GAP = 6;

	private static final List<MenuEntries.Entry> ENTRIES = MenuEntries.all();

	private int left;
	private int top;

	public MainMenuScreen() {
		super(Text.translatable("gui.redtale_economy.menu.title"));
	}

	private int cols() {
		return 2;
	}

	private int rows() {
		return (ENTRIES.size() + cols() - 1) / cols();
	}

	private int panelH() {
		return 46 + rows() * BTN_H + (rows() - 1) * GAP + 34;
	}

	@Override
	protected void init() {
		left = (width - PANEL_W) / 2;
		top = (height - panelH()) / 2;

		for (int i = 0; i < ENTRIES.size(); i++) {
			MenuEntries.Entry e = ENTRIES.get(i);
			int x = left + 12 + (i % cols()) * (BTN_W + GAP);
			int y = top + 44 + (i / cols()) * (BTN_H + GAP);
			ButtonWidget b = ButtonWidget.builder(
					Text.translatable("gui.redtale_economy.menu." + e.key()),
					btn -> fire(e))
					.dimensions(x, y, BTN_W, BTN_H)
					.build();
			b.active = e.ready();
			if (!e.ready()) {
				b.setTooltip(Tooltip.of(Text.translatable("gui.redtale_economy.menu.soon")));
			}
			addDrawableChild(b);
		}

		addDrawableChild(ButtonWidget.builder(
				Text.translatable("gui.redtale_economy.menu.close"),
				b -> close())
				.dimensions(left + PANEL_W / 2 - 40, top + panelH() - 28, 80, 20)
				.build());
	}

	/** 点击入口：发命令后关面板，命令的结果由服务端回显。 */
	private void fire(MenuEntries.Entry e) {
		if (!e.ready()) return;
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.getNetworkHandler() == null || client.player == null) return;
		client.getNetworkHandler().sendChatCommand(e.command());
		close();
	}

	@Override
	public void renderBackground(DrawContext ctx) {
		super.renderBackground(ctx);
		// 面板底：外描边 + 内填充
		UiStyle.panel(ctx, left, top, PANEL_W, panelH());
		UiStyle.divider(ctx, left + 8, top + 22, PANEL_W - 16);
	}

	@Override
	public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
		super.render(ctx, mouseX, mouseY, delta);
		ctx.drawCenteredTextWithShadow(textRenderer, title, width / 2, top + 14, UiStyle.TEXT);
		ctx.drawCenteredTextWithShadow(textRenderer,
				Text.translatable("gui.redtale_economy.menu.hint"),
				width / 2, top + 28, UiStyle.TEXT_DIM);
		ctx.drawCenteredTextWithShadow(textRenderer,
				Text.translatable("gui.redtale_economy.menu.footer"),
				width / 2, top + panelH() - 40, UiStyle.TEXT_FAINT);
	}

	@Override
	public boolean shouldPause() {
		return false;
	}
}
