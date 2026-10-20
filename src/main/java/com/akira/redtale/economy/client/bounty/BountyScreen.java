package com.akira.redtale.economy.client.bounty;

import com.akira.redtale.economy.RedtaleEconomy;
import com.akira.redtale.economy.client.ui.UiStyle;
import com.akira.redtale.economy.net.BountyProtocol;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

/**
 * 系统悬赏面板（U7：列表类信息进 GUI）。
 *
 * <p>显示今天抽到的若干悬赏、进度与奖励；点「领取」只是发 {@code /bounty claim <序号>}，
 * 金额、是否达成、是否已领都由服务端再判定一次。
 */
@Environment(EnvType.CLIENT)
public final class BountyScreen extends Screen {

	private static final int ROW_H = 26;
	private static final int W = 300;
	private static final int PAD = 10;

	private final BountyProtocol.Snapshot snap;
	private int left;
	private int top;

	public BountyScreen(BountyProtocol.Snapshot snap) {
		super(Text.translatable("gui.redtale_economy.bounty.title"));
		this.snap = snap == null ? BountyProtocol.Snapshot.empty() : snap;
	}

	private int rows() {
		return Math.max(1, snap.entries().size());
	}

	private int panelH() {
		return 34 + rows() * ROW_H + 30;
	}

	@Override
	protected void init() {
		left = (width - W) / 2;
		top = (height - panelH()) / 2;
		int y = top + 30;
		for (BountyProtocol.Entry e : snap.entries()) {
			BountyProtocol.Entry entry = e;
			ButtonWidget b = ButtonWidget.builder(Text.translatable("gui.redtale_economy.bounty.claim"),
							x -> claim(entry.index()))
					.dimensions(left + W - PAD - 54, y + 4, 54, 18).build();
			b.active = entry.done() && !entry.claimed();
			addDrawableChild(b);
			y += ROW_H;
		}
		addDrawableChild(ButtonWidget.builder(Text.translatable("gui.redtale_economy.bounty.close"),
						x -> close())
				.dimensions(left + W - PAD - 60, top + panelH() - 24, 60, 18).build());
		addDrawableChild(ButtonWidget.builder(Text.translatable("gui.redtale_economy.bounty.claimAll"),
						x -> cmd("bounty claimall"))
				.dimensions(left + PAD, top + panelH() - 24, 80, 18).build());
	}

	private void claim(int index) {
		cmd("bounty claim " + (index + 1));
	}

	private void cmd(String command) {
		MinecraftClient c = MinecraftClient.getInstance();
		if (c.getNetworkHandler() == null) return;
		c.getNetworkHandler().sendChatCommand(command);
		close();
	}

	@Override
	public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
		renderBackground(ctx);
		UiStyle.panel(ctx, left, top, W, panelH());
		ctx.drawTextWithShadow(textRenderer, Text.translatable("gui.redtale_economy.bounty.title"),
				left + PAD, top + 8, UiStyle.TEXT);
		UiStyle.divider(ctx, left + PAD, top + 22, W - PAD * 2);

		if (snap.entries().isEmpty()) {
			ctx.drawTextWithShadow(textRenderer, Text.translatable("gui.redtale_economy.bounty.empty"),
					left + PAD, top + 40, UiStyle.TEXT_DIM);
		}
		int y = top + 30;
		for (BountyProtocol.Entry e : snap.entries()) {
			UiStyle.card(ctx, left + PAD, y, W - PAD * 2, ROW_H - 4, false);
			ctx.drawTextWithShadow(textRenderer, Text.literal("§f" + e.id()),
					left + PAD + 6, y + 4, UiStyle.TEXT);
			String prog = e.progress() + "/" + e.need();
			ctx.drawTextWithShadow(textRenderer, Text.literal(prog),
					left + PAD + 150, y + 4, e.done() ? UiStyle.GOOD : UiStyle.TEXT_DIM);
			UiStyle.progress(ctx, left + PAD + 6, y + 16, 130, 4,
					e.need() <= 0 ? 0 : (double) e.progress() / e.need());
			String money = com.akira.redtale.economy.economy.Currency.format(e.reward());
			ctx.drawTextWithShadow(textRenderer, Text.literal(money),
					left + PAD + 150, y + 14, UiStyle.ACCENT);
			if (e.claimed()) {
				ctx.drawTextWithShadow(textRenderer, Text.translatable("gui.redtale_economy.bounty.claimed"),
						left + W - PAD - 100, y + 4, UiStyle.TEXT_FAINT);
			}
			y += ROW_H;
		}
		super.render(ctx, mouseX, mouseY, delta);
	}

	@Override
	public boolean shouldPause() {
		return false;
	}
}
