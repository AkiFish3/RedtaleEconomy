package com.akira.redtale.economy.legacy.dev;

import com.akira.redtale.economy.client.ui.hub.HubMoney;
import com.akira.redtale.economy.legacy.LegacyInit;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 配套 {@link FontShotHarness} 的客户端状态机（**开发期**，不进发行 jar）。
 *
 * <p>进世界后等 overlay 消失（否则拍到 "Loading terrain"），关掉服务端推过来的界面，
 * 在左上角铺一块纯黑底，用**生产 API** {@code HubMoney.asText()} 画 4 行：
 * <ol>
 *   <li>三段全显（余额口径）</li>
 *   <li>零值段隐藏（单价口径）</li>
 *   <li>三个字形裸排（U+E000/E001/E002，判「字形真的有像素」）</li>
 *   <li>纯数字参照行（判基线与字宽有没有被字形带偏）</li>
 * </ol>
 * 之后连拍 2 帧到 {@code run/screenshots/b32-font-f{0,1}.png}，写 {@code run/b32-font.txt}，然后退出。
 */
@Environment(EnvType.CLIENT)
final class FontShotClient {
	private static final String OUT_TXT = "b32-font.txt";
	/** 让服务端把 Hub 界面推完再关（推一半关会被下一 tick 重开）。 */
	private static final int SETTLE = 30;
	/** 画多久再拍（首帧字体/图集还没绑好）。 */
	private static final int DRAW_SETTLE = 20;
	private static final int FRAMES = 2;
	private static final int FRAME_GAP = 6;
	/** 纯黑底区域（分析脚本按这个范围裁，别改尺寸除非同步改脚本）。 */
	static final int BOX_W = 360;
	static final int BOX_H = 104;

	private static final Identifier COIN_FONT = Identifier.tryParse("redtale_economy:coins");
	private static final Text GLYPHS = Text.empty()
			.append(Text.literal("\uE000").setStyle(Style.EMPTY.withFont(COIN_FONT)))
			.append(Text.literal(" "))
			.append(Text.literal("\uE001").setStyle(Style.EMPTY.withFont(COIN_FONT)))
			.append(Text.literal(" "))
			.append(Text.literal("\uE002").setStyle(Style.EMPTY.withFont(COIN_FONT)));

	private static int ticks = 0;
	private static int settle = 0;
	private static int drawSettle = 0;
	private static int frame = 0;
	private static int timer = 0;
	private static boolean drawing = false;
	private static boolean done = false;
	private static boolean dumped = false;
	private static String probe = "";

	/**
	 * ★不能写成 static final★：类初始化时 MinecraftClient 还没建好，{@code HubMoney.fontReady()}
	 * 必然 false ⇒ asText 直接回落成「0金37银23铜」的纯文字，看着像字体没生效（实测踩过）。
	 * 每帧现算才能测到生产路径。
	 */
	private static Text line1() {
		return Text.literal("1) ").append(HubMoney.asText(3723L, true));
	}

	private static Text line2() {
		return Text.literal("2) ").append(HubMoney.asText(3723L, false));
	}

	private static final Text L3 = Text.literal("3) ").append(GLYPHS);
	private static final Text L4 = Text.literal("4) 3723 3723");

	private FontShotClient() {
	}

	static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(FontShotClient::tick);
		HudRenderCallback.EVENT.register((DrawContext ctx, float tickDelta) -> {
			if (!drawing) {
				return;
			}
			MinecraftClient mc = MinecraftClient.getInstance();
			ctx.fill(0, 0, BOX_W, BOX_H, 0xFF101010);
			ctx.drawText(mc.textRenderer, line1(), 6, 6, 0xFFFFFFFF, false);
			ctx.drawText(mc.textRenderer, line2(), 6, 24, 0xFFFFFFFF, false);
			ctx.drawText(mc.textRenderer, L3, 6, 42, 0xFFFFFFFF, false);
			ctx.drawText(mc.textRenderer, L4, 6, 60, 0xFFFFFFFF, false);
		});
	}

	private static void tick(MinecraftClient mc) {
		if (done) {
			return;
		}
		if (++ticks > 20 * 120) { // 120s 硬超时
			finish(mc, "timeout");
			return;
		}
		if (mc.world == null || mc.player == null) {
			return;
		}
		if (mc.currentScreen != null) {
			mc.setScreen(null); // 进服后服务端会推 Hub 界面，会盖住整屏
		}
		if (settle < SETTLE) {
			if (mc.getOverlay() != null) {
				settle = 0;
			} else {
				settle++;
			}
			return;
		}
		if (!dumped) {
			dumped = true;
			probe = dump(mc);
		}
		drawing = true;
		if (++drawSettle < DRAW_SETTLE) {
			return;
		}
		if (++timer >= FRAME_GAP) {
			timer = 0;
			ScreenshotRecorder.saveScreenshot(mc.runDirectory, "b32-font-f" + frame + ".png",
					mc.getFramebuffer(), text -> {
					});
			if (++frame >= FRAMES) {
				finish(mc, "ok");
			}
		}
	}

	private static void finish(MinecraftClient mc, String why) {
		drawing = false;
		done = true;
		try {
			Path out = mc.runDirectory.toPath().resolve(OUT_TXT);
			Files.writeString(out, probe + "result=" + why + " frames=" + frame + "\n",
					StandardCharsets.UTF_8);
		} catch (Exception e) {
			LegacyInit.LOGGER.warn("[b32] write failed", e);
		}
		LegacyInit.LOGGER.info("[b32] font shot done ({}, {} frames)", why, frame);
		mc.scheduleStop();
	}

	/**
	 * 落盘可判定的数值：fontReady 是「json 在不在」，字宽才是「字形有没有被加载」——
	 * 缺字形时 {@code getWidth} 会退化成缺字方块的宽度（≠ 9px）。
	 */
	private static String dump(MinecraftClient mc) {
		StringBuilder sb = new StringBuilder();
		try {
			sb.append("fontReady=").append(HubMoney.fontReady()).append('\n');
			sb.append("texProbe=").append(HubMoney.texProbe()).append('\n');
			sb.append("widthGlyphs=").append(mc.textRenderer.getWidth(GLYPHS)).append('\n');
			sb.append("widthGoldOnly=")
					.append(mc.textRenderer.getWidth(
							Text.literal("\uE000").setStyle(Style.EMPTY.withFont(COIN_FONT))))
					.append('\n');
			sb.append("widthDigits=").append(mc.textRenderer.getWidth(Text.literal("3723"))).append('\n');
			sb.append("line1=").append(line1().getString()).append('\n');
			sb.append("line2=").append(line2().getString()).append('\n');
			// 字形路径必须让 getString() 里出现 U+E000 系（回落时是「金/银/铜」三个汉字）
			sb.append("line1HasGlyph=").append(line1().getString().indexOf('\uE000') >= 0).append('\n');
		} catch (Throwable t) {
			sb.append("EXCEPTION ").append(t).append('\n');
		}
		LegacyInit.LOGGER.info("[b32] {}", sb.toString().replace('\n', '|'));
		return sb.toString();
	}
}
