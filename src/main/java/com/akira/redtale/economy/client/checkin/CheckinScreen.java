package com.akira.redtale.economy.client.checkin;

import com.akira.redtale.economy.economy.Currency;
import com.akira.redtale.economy.net.CheckinProtocol;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

/**
 * 签到面板（U3）。
 *
 * <p>循环内每天画一个格子，已签的盖一层、今天高亮。数据来自服务端快照，
 * 每天能拿多少由服务端按 {@code checkin.json} 的 tiers 算好，客户端不存档位表。
 *
 * <p>「签到 / 补签」发的是命令，补签扣券与每月次数上限都在服务端判定。
 */
@Environment(EnvType.CLIENT)
public final class CheckinScreen extends Screen {

	private static final int CELL_W = 40;
	private static final int CELL_H = 48;
	private static final int PAD = 10;

	private final CheckinProtocol.Snapshot snap;
	private int left;
	private int top;

	public CheckinScreen(CheckinProtocol.Snapshot snap) {
		super(Text.translatable("gui.redtale_economy.checkin.title"));
		this.snap = snap == null ? CheckinProtocol.Snapshot.empty() : snap;
	}

	private int panelW() {
		return Math.max(240, snap.points().size() * (CELL_W + 6) + PAD * 2);
	}

	private int panelH() {
		return 96;
	}

	@Override
	protected void init() {
		left = (width - panelW()) / 2;
		top = (height - panelH()) / 2;

		addDrawableChild(ButtonWidget.builder(
				Text.translatable("gui.redtale_economy.checkin.do"),
				b -> cmd("checkin", true))
				.dimensions(left + PAD, top + panelH() - 26, 80, 20).build());

		ButtonWidget makeup = ButtonWidget.builder(
				Text.translatable("gui.redtale_economy.checkin.makeup"),
				b -> cmd("checkin make-up", true))
				.dimensions(left + PAD + 88, top + panelH() - 26, 100, 20).build();
		makeup.active = snap.makeupLeft() > 0;
		addDrawableChild(makeup);

		addDrawableChild(ButtonWidget.builder(
				Text.translatable("gui.redtale_economy.checkin.close"),
				b -> close())
				.dimensions(left + panelW() - PAD - 70, top + panelH() - 26, 70, 20).build());
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
		ctx.fill(left - 2, top - 2, left + panelW() + 2, top + panelH() + 2, 0xFF1B1B22);
		ctx.fill(left, top, left + panelW(), top + panelH(), 0xE6101014);

		ctx.drawCenteredTextWithShadow(textRenderer, title, left + panelW() / 2, top + 8, 0xFFFFFF);
		ctx.drawTextWithShadow(textRenderer,
				Text.translatable("gui.redtale_economy.checkin.summary", snap.streak(), snap.totalDays()),
				left + PAD, top + 24, 0x8A8A96);

		int x = left + PAD;
		for (CheckinProtocol.Point p : snap.points()) {
			int y = top + 38;
			int bg = p.current() ? 0x50E9C46A : 0x30FFFFFF;
			ctx.fill(x, y, x + CELL_W, y + CELL_H, bg);
			if (p.done()) {
				ctx.fill(x, y, x + CELL_W, y + CELL_H, 0x5000C000);
			}
			ctx.drawCenteredTextWithShadow(textRenderer, Text.literal("D" + p.day()),
					x + CELL_W / 2, y + 6, p.done() ? 0x9BE59B : 0xFFFFFF);
			ctx.drawCenteredTextWithShadow(textRenderer, Text.literal(fmtShort(p.money())),
					x + CELL_W / 2, y + 22, 0xFFE9A0);
			if (p.done()) {
				ctx.drawCenteredTextWithShadow(textRenderer, Text.literal("✔"),
						x + CELL_W / 2, y + 34, 0x9BE59B);
			}
			x += CELL_W + 6;
		}

		ctx.drawTextWithShadow(textRenderer,
				Text.translatable("gui.redtale_economy.checkin.makeupLeft", snap.makeupLeft()),
				left + PAD + 196, top + panelH() - 20, 0x6E6E7A);
	}

	private static String fmtShort(long raw) {
		try {
			String s = Currency.format(raw);
			return s.length() <= 7 ? s : s.substring(0, 7);
		} catch (Throwable e) {
			return Long.toString(raw);
		}
	}

	@Override
	public boolean shouldPause() {
		return false;
	}
}
