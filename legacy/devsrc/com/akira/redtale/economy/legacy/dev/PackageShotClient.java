package com.akira.redtale.economy.legacy.dev;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.ScreenshotRecorder;

/**
 * 配套 {@link PackageShotHarness} 的客户端状态机（**开发期**，不进发行 jar）。
 *
 * <p>每个 shot 先拍一张「空场」({@code -empty})，再拍 N 帧实体 ⇒ 分析脚本用
 * {@code |frame - empty|} 得到**精确剪影掩码**（背景地形/天空完全相消，不用猜背景色）。
 * 这是 T72「三系列剪影必须不同」唯一的客观判据。
 *
 * <p>旧版没有空场帧，只能拿「与天空色的距离」当掩码，结果把白色地形也算了进去
 * （2026-10-22 实测 chest/crate 的 bbox 直接顶满 240×134，IoU 0.831 假红）。
 *
 * <p>★为什么连拍多帧★：idle 动画带静止段，单帧对单帧可能都停在同一相位。
 */
@Environment(EnvType.CLIENT)
final class PackageShotClient {
	/** "Loading terrain" 消失后再等这么久（否则拍到的是加载画面）。 */
	private static final int SETTLE = 20;
	/** 清场后等待实体真正被 discard 的 tick 数。 */
	private static final int CLEAR_WAIT = 12;
	/** 生成后等渲染器第一次画出来的 tick 数。 */
	private static final int AFTER_SPAWN = 10;
	/** 连拍间隔（tick）。 */
	private static final int FRAME_GAP = 5;
	private static final int FRAMES = 10;

	private static int phase = 0;
	private static int timer = 0;
	private static int tier = 0;
	private static int frame = 0;
	private static int sub = 0;
	private static int ticks = 0;

	private PackageShotClient() {
	}

	static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(PackageShotClient::tick);
	}

	private static void tick(MinecraftClient mc) {
		ticks++;
		if (ticks > 20 * 180) { // 180s 硬超时
			PackageShotHarness.spawnRequest = -1;
			mc.scheduleStop();
			return;
		}
		if (mc.world == null || mc.player == null) {
			return;
		}
		if (mc.options != null) {
			mc.options.hudHidden = true;
			// ★云会飘★：空场帧与实体帧相隔 50 tick，云边缘位移会污染 |frame-empty| 掩码
			// （实测 mask 4796px / bbox 202×146，实体本身只有 ~1.5k px）⇒ 拍摄期直接关云。
			mc.options.getCloudRenderMode().setValue(net.minecraft.client.option.CloudRenderMode.OFF);
		}
		// ★地狱坑★ 进服后服务端会自动推 Hub/商店界面，会盖住整屏 ⇒ 每 tick 强制关掉。
		if (mc.currentScreen != null) {
			mc.setScreen(null);
		}

		switch (phase) {
			case 0 -> {
				// quickPlay 进世界后头 ~2s 是 "Loading terrain" 遮罩，必须判 overlay。
				if (mc.getOverlay() != null) {
					timer = 0;
				} else if (++timer >= SETTLE) {
					timer = 0;
					sub = 0;
					phase = 1;
				}
			}
			case 1 -> {
				String shot = PackageShotHarness.SHOTS[tier];
				if (sub == 0) {
					// 清场 → 拍空场参考帧
					PackageShotHarness.spawnRequest = -1;
					if (++timer >= CLEAR_WAIT) {
						save(mc, shot + "-empty");
						PackageShotHarness.spawnRequest = tier;
						timer = 0;
						sub = 1;
					}
				} else {
					if (timer >= AFTER_SPAWN + FRAME_GAP * frame) {
						save(mc, shot + "-f" + frame);
						frame++;
						if (frame >= FRAMES) {
							frame = 0;
							timer = 0;
							sub = 0;
							tier++;
							if (tier >= PackageShotHarness.SHOTS.length) {
								phase = 3;
							}
						}
					} else {
						timer++;
					}
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
