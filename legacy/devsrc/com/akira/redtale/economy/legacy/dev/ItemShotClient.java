package com.akira.redtale.economy.legacy.dev;

import com.akira.redtale.economy.legacy.LegacyInit;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

/**
 * 配套 {@link ItemShotHarness} 的客户端状态机（**开发期**，不进发行 jar）。
 *
 * <p>背景用 {@code fill} 铺纯黑而不是渲染世界：这样背景与云、时间、地形完全无关，
 * 无需空场参考帧（第 27 轮踩过「云位移污染掩码」的坑），也不会被进服自动推的 Hub 界面盖住
 * —— 每 tick 检查当前屏是不是我们的，被抢就换回来。
 */
@Environment(EnvType.CLIENT)
final class ItemShotClient {
	private static int phase = 0;
	private static int timer = 0;
	private static int shot = 0;
	private static int frame = 0;
	private static int ticks = 0;
	private static ShotScreen screen;

	private ItemShotClient() {
	}

	static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(ItemShotClient::tick);
	}

	private static void tick(MinecraftClient mc) {
		ticks++;
		if (ticks > 20 * 120) { // 120s 硬超时
			mc.setScreen(null);
			mc.scheduleStop();
			return;
		}
		if (mc.world == null || mc.player == null) {
			return;
		}
		if (mc.options != null) {
			mc.options.hudHidden = true;
		}

		switch (phase) {
			case 0 -> {
				// quickPlay 进世界后头 ~2s 是 "Loading terrain" 遮罩，必须判 overlay。
				if (mc.getOverlay() != null) {
					timer = 0;
				} else if (++timer >= 20) {
					timer = 0;
					shot = 0;
					frame = 0;
					openShot(mc, shot);
					phase = 1;
				}
			}
			case 1 -> {
				// 进服/别的模组可能抢屏，每 tick 抢回来。
				if (mc.currentScreen != screen) {
					mc.setScreen(screen);
				}
				if (timer < ItemShotHarness.AFTER_OPEN) {
					timer++;
					return;
				}
				if (timer >= ItemShotHarness.AFTER_OPEN
						+ ItemShotHarness.FRAME_GAP * frame) {
					save(mc, ItemShotHarness.SHOTS[shot] + "-f" + frame, frame);
					frame++;
					if (frame >= ItemShotHarness.FRAMES) {
						frame = 0;
						timer = 0;
						shot++;
						if (shot >= ItemShotHarness.SHOTS.length) {
							phase = 2;
						} else {
							openShot(mc, shot);
						}
					}
				} else {
					timer++;
				}
			}
			default -> {
				if (timer == 0) {
					mc.setScreen(null);
				}
				if (++timer >= 20) {
					mc.scheduleStop();
				}
			}
		}
	}

	private static void openShot(MinecraftClient mc, int idx) {
		String path = ItemShotHarness.SHOTS[idx];
		ItemStack stack = new ItemStack(Registries.ITEM.get(new Identifier(LegacyInit.MOD_ID, path)));
		screen = new ShotScreen(stack);
		mc.setScreen(screen);
		LegacyInit.LOGGER.info("[b36] shot={} item={} stackEmpty={} screen={}",
				path, stack.getItem(), stack.isEmpty(), screen != null);
	}

	private static void save(MinecraftClient mc, String name, int frame) {
		LegacyInit.LOGGER.info("[b36] capture shot={} frame={} worldTime={} clientTick={}",
				name, frame, mc.world == null ? -1 : mc.world.getTime(), ticks);
		ScreenshotRecorder.saveScreenshot(mc.runDirectory, "b36-item-" + name + ".png",
				mc.getFramebuffer(), text -> {
				});
	}

	/** 纯黑背景 + 居中放大画物品。自绘屏，不走 blur（blur 依赖世界画面 ⇒ 帧间会变）。 */
	private static final class ShotScreen extends Screen {
		private final ItemStack stack;

		private ShotScreen(ItemStack stack) {
			super(Text.literal("b36-item-shot"));
			this.stack = stack;
		}

		@Override
		protected void init() {
		}

		@Override
		public boolean shouldPause() {
			return false;
		}

		@Override
		public void render(DrawContext context, int mouseX, int mouseY, float delta) {
			// 故意不调 renderBackground：它会画世界背景（1.20.1 是 blur/变暗），逐帧会变，
			// 帧差就不再只反映物品本身。纯黑底 = 非黑像素只有物品。
			context.fill(0, 0, this.width, this.height, 0xFF000000);
			context.getMatrices().push();
			context.getMatrices().translate(this.width / 2.0, this.height / 2.0, 0.0);
			context.getMatrices().scale(ItemShotHarness.SCALE, ItemShotHarness.SCALE, 1.0F);
			context.drawItem(this.stack, -8, -8);
			context.getMatrices().pop();
		}
	}
}
