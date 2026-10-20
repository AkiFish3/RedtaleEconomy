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
	/** H04：点过签到的时间戳，用于防连点（0 = 没点过）。 */
	private long claimClickAt;
	/** H04：点天数格子只做选中，绝不触发签到。 */
	private int selectedDay;

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

		ButtonWidget doBtn = ButtonWidget.builder(
				Text.translatable("gui.redtale_economy.checkin.do"),
				b -> claim())
				.dimensions(left + PAD, top + panelH() - 26, 80, 20).build();
		doBtn.active = !CheckinGate.alreadySigned(snap.points());
		addDrawableChild(doBtn);

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

	/**
	 * 签到点击（H04）：先过闸门再发包，发完立刻关界面。
	 * 服务端同日幂等仍在（权威），这里只是不让玩家看到「点两次 = 两条提示」。
	 */
	private void claim() {
		if (!CheckinGate.canClaim(CheckinGate.alreadySigned(snap.points()), claimClickAt,
				System.currentTimeMillis())) {
			return;
		}
		claimClickAt = System.currentTimeMillis();
		cmd("checkin", true);
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
		com.akira.redtale.economy.client.ui.UiStyle.panel(ctx, left, top, panelW(), panelH());

		ctx.drawCenteredTextWithShadow(textRenderer, title, left + panelW() / 2, top + 8, com.akira.redtale.economy.client.ui.UiStyle.TEXT);
		ctx.drawTextWithShadow(textRenderer,
				Text.translatable("gui.redtale_economy.checkin.summary", snap.streak(), snap.totalDays()),
				left + PAD, top + 24, com.akira.redtale.economy.client.ui.UiStyle.TEXT_DIM);

		int x = left + PAD;
		for (CheckinProtocol.Point p : snap.points()) {
			int y = top + 38;
			int bg = p.current() ? 0x33E9C46A : 0x22FFFFFF;
			if (p.day() == selectedDay) bg = 0x44FFFFFF;
			com.akira.redtale.economy.client.ui.UiStyle.card(ctx, x, y, CELL_W, CELL_H, p.current());
			ctx.fill(x, y, x + CELL_W, y + CELL_H, bg);
			if (p.done()) {
				ctx.fill(x, y, x + CELL_W, y + CELL_H, 0x3300C000);
			}
			ctx.drawCenteredTextWithShadow(textRenderer, Text.literal("D" + p.day()),
					x + CELL_W / 2, y + 6, p.done() ? 0xFF7BC67E : com.akira.redtale.economy.client.ui.UiStyle.TEXT);
			ctx.drawCenteredTextWithShadow(textRenderer, Text.literal(fmtShort(p.money())),
					x + CELL_W / 2, y + 22, com.akira.redtale.economy.client.ui.UiStyle.ACCENT);
			if (p.done()) {
				ctx.drawCenteredTextWithShadow(textRenderer, Text.literal("✔"),
						x + CELL_W / 2, y + 34, 0xFF7BC67E);
			}
			x += CELL_W + 6;
		}

		ctx.drawTextWithShadow(textRenderer,
				Text.translatable("gui.redtale_economy.checkin.makeupLeft", snap.makeupLeft()),
				left + PAD + 196, top + panelH() - 20, com.akira.redtale.economy.client.ui.UiStyle.TEXT_FAINT);
	}

	private static String fmtShort(long raw) {
		try {
			String s = Currency.format(raw);
			return s.length() <= 7 ? s : s.substring(0, 7);
		} catch (Throwable e) {
			return Long.toString(raw);
		}
	}

	/** 点天数格子 = 只选中查看，不发任何命令（H04 的直接诱因就是这里会重复签到）。 */
	@Override
	public boolean mouseClicked(double mx, double my, int button) {
		if (button == 0) {
			int x = left + PAD;
			for (CheckinProtocol.Point p : snap.points()) {
				int y = top + 38;
				if (mx >= x && mx < x + CELL_W && my >= y && my < y + CELL_H) {
					selectedDay = p.day();
					return true;
				}
				x += CELL_W + 6;
			}
		}
		return super.mouseClicked(mx, my, button);
	}

	@Override
	public boolean shouldPause() {
		return false;
	}
}
