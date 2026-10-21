package com.akira.redtale.economy.legacy.dev;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.ScreenshotRecorder;

/**
 * 配套 {@link PackageShotHarness} 的客户端状态机：三档各连拍 8 帧，拍完退出。
 * 输出 {@code run/screenshots/b14-pkg-<tier>-f<N>.png}。
 *
 * <p>★为什么连拍 8 帧而不是 2 帧★：{@code package.animation.json} 的 idle 长度 3s，
 * 但最后一根关键帧在 1.4583s ⇒ 1.46~3.0s 是**静止段**。第 13 轮前用「a/b 相隔 12 tick」
 * 恰好两帧都落在静止段，误判成「动画不播」。连拍覆盖 0.4~2.5s 整个活跃段即可分辨。
 */
@Environment(EnvType.CLIENT)
final class PackageShotClient {
	/** "Loading terrain" 消失后再等这么久（否则拍到的是加载画面）。 */
	private static final int SETTLE = 20;
	/** 实体生成到渲染器第一次画出来。 */
	private static final int AFTER_SPAWN = 8;
	/** 连拍间隔（tick）。 */
	private static final int FRAME_GAP = 5;
	/** 14 帧 × 5 tick ≈ 0.5s~3.75s，**超过 idle 的 3s 循环长度** ⇒ 不管动画相位从哪开始，
	 *  一定能覆盖活跃段（第 14 轮实测：8 帧/6 tick 时 legendary 那一档全程落在 1.46~3.0s 静止段）。 */
	private static final int FRAMES = 14;

	private static int phase = 0;
	private static int timer = 0;
	private static int tier = 0;
	private static int frame = 0;
	private static int ticks = 0;

	private PackageShotClient() {
	}

	static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(PackageShotClient::tick);
	}

	private static void tick(MinecraftClient mc) {
		ticks++;
		if (ticks > 20 * 120) { // 120s 硬超时
			PackageShotHarness.spawnRequest = -1;
			mc.scheduleStop();
			return;
		}
		if (mc.world == null || mc.player == null) {
			return;
		}
		if (mc.options != null) {
			mc.options.hudHidden = true;
		}
		// ★地狱坑★ 进服后服务端会自动推 Hub/商店界面（[shop] snapshot / [checkin] useHub=true），
		// 它会盖住整屏 ⇒ 拍到的是深色面板而不是世界。每 tick 强制关掉。
		if (mc.currentScreen != null) {
			mc.setScreen(null);
		}

		switch (phase) {
			case 0 -> {
				// ★地狱坑★ quickPlay 进世界后头 ~2s 是 "Loading terrain" 遮罩，整屏发黑；
				// 直接按 tick 数等会拍到加载画面（第 14 轮首版就栽在这），必须判 overlay。
				if (mc.getOverlay() != null) {
					timer = 0;
				} else if (++timer >= SETTLE) {
					timer = 0;
					phase = 1;
					PackageShotHarness.spawnRequest = 0;
				}
			}
			case 1 -> {
				int at = 10 + FRAME_GAP * frame;
				if (timer >= at) {
					if (frame < FRAMES) {
						save(mc, PackageShotHarness.TIERS[tier] + "-f" + frame);
						frame++;
					} else {
						frame = 0;
						timer = 0;
						tier++;
						if (tier < PackageShotHarness.TIERS.length) {
							PackageShotHarness.spawnRequest = tier;
						} else {
							phase = 3;
						}
					}
				} else {
					timer++;
				}
			}
			default -> {
				if (timer == 0) {
					PackageShotHarness.spawnRequest = -1;
				}
				if (++timer >= 20) {
					mc.scheduleStop();
				}
			}
		}
	}

	private static void save(MinecraftClient mc, String name) {
		ScreenshotRecorder.saveScreenshot(mc.runDirectory, "b14-pkg-" + name + ".png",
				mc.getFramebuffer(), text -> {
				});
	}
}
