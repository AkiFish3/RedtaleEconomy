package com.akira.redtale.economy.legacy.dev;

import com.akira.redtale.economy.legacy.LegacyInit;
import com.akira.redtale.economy.legacy.bound.BoundItemHandler;
import com.akira.redtale.economy.legacy.init.REItems;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.item.TooltipContext;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 三档 tooltip 同场渲染探针（**开发期**，不是玩法代码）。
 *
 * <p>做法：进世界后构造一个带 {@code BoundOwnerName} 的绑定包裹，调真实
 * {@code ItemStack#getTooltip}（这样 Obscure-API / extraspellattributes / trinkets 的
 * mixin 全部参与），把**最终**列表逐行落盘 {@code run/b16-tooltip.txt}；
 * 再用 {@code DrawContext#drawTooltip} 把它画在 HUD 上并截图，
 * 证明「行存在」之外还「能画出来」（列表里有非法 Component 时会在渲染阶段才炸）。
 */
@Environment(EnvType.CLIENT)
final class TooltipShotClient {
	private static final String OUT_TXT = "b16-tooltip.txt";
	private static final int SETTLE = 20;
	private static final int FRAMES = 3;
	private static final int FRAME_GAP = 5;

	private static List<Text> lines = List.of();
	private static int ticks = 0;
	private static int settle = 0;
	private static int frame = 0;
	private static int timer = 0;
	private static boolean done = false;

	private TooltipShotClient() {
	}

	static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(TooltipShotClient::tick);
		HudRenderCallback.EVENT.register((DrawContext ctx, float tickDelta) -> {
			if (lines.isEmpty()) {
				return;
			}
			ctx.drawTooltip(MinecraftClient.getInstance().textRenderer, lines, 8, 24);
		});
	}

	private static void tick(MinecraftClient mc) {
		if (done) {
			return;
		}
		ticks++;
		if (ticks > 20 * 90) { // 90s 硬超时
			mc.scheduleStop();
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
		if (lines.isEmpty()) {
			collect(mc);
			timer = 0;
			return;
		}
		if (frame < FRAMES) {
			if (++timer >= FRAME_GAP) {
				timer = 0;
				ScreenshotRecorder.saveScreenshot(mc.runDirectory, "b16-ttip-f" + frame + ".png",
						mc.getFramebuffer(), text -> {
						});
				frame++;
			}
		} else {
			done = true;
			mc.scheduleStop();
		}
	}

	private static void collect(MinecraftClient mc) {
		ItemStack stack = new ItemStack(REItems.NORMAL_PACKAGE);
		// 直接写 NBT：bindToPlayer 会先查 bound_items 白名单，夹具不该依赖玩家配置
		stack.getOrCreateNbt().putString("BoundOwnerName", mc.player.getName().getString());
		StringBuilder sb = new StringBuilder();
		sb.append("# item=").append(stack.getItem()).append(" owner=")
				.append(BoundItemHandler.getOwnerName(stack)).append(" hasOwner=")
				.append(BoundItemHandler.hasOwner(stack)).append('\n');
		try {
			List<Text> tips = stack.getTooltip(mc.player, TooltipContext.Default.BASIC);
			lines = List.copyOf(tips);
			sb.append("# lines=").append(tips.size()).append('\n');
			for (int i = 0; i < tips.size(); i++) {
				sb.append(i).append(": ").append(tips.get(i).getString()).append('\n');
			}
		} catch (Throwable t) {
			sb.append("# EXCEPTION ").append(t.getClass().getName()).append(": ").append(t.getMessage())
					.append('\n');
			lines = List.of(Text.literal("EXCEPTION " + t.getClass().getSimpleName()));
		}
		try {
			Path out = mc.runDirectory.toPath().resolve(OUT_TXT);
			Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
		} catch (Exception e) {
			LegacyInit.LOGGER.warn("[b16] write failed", e);
		}
		LegacyInit.LOGGER.info("[b16] tooltip dumped, {} lines", lines.size());
	}
}
