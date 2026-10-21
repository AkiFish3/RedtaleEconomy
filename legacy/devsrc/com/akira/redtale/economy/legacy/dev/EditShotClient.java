package com.akira.redtale.economy.legacy.dev;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.util.Hand;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * T71 编辑器验收夹具的**客户端半边**（开发期，不进发行 jar）。
 *
 * <p>走的是真实交互路径，不是直接构造界面：
 * {@code interactionManager.interactItem} → 服务端 {@code PackageItem.use} → {@code openHandledScreen}
 * → 服务端下发 OpenScreen → 客户端 {@code ScreenRegistry} 建出 {@code PackageEditScreen}。
 * 所以「界面能不能开」＝ ScreenRegistry 注册 + ExtendedScreenHandlerType 反序列化 两件事都被覆盖到了。
 *
 * <p>点击用 {@code screen.mouseClicked(x, y, button)}：这是 GuiScreen 的原始分发入口，
 * 命中判定（chip 区间 / 表格 +/- 热区）与真人点击走同一段代码。
 */
@Environment(EnvType.CLIENT)
final class EditShotClient {
	private static final int SETTLE = 40;
	private static final int TIMEOUT_TICKS = 20 * 240;

	/** 编辑器面板几何（与 PackageEditScreen 一致：面板 300×318，左上角居中）。 */
	private static final int PANEL_W = 300;
	private static final int PANEL_H = 318;
	private static final int MODE_Y = 20;
	private static final int MODE_CHIP_W = 86;
	private static final int MODE_CHIP_H = 12;
	private static final int MODE_CHIP_GAP = 4;
	private static final int TIER_Y = 36;
	private static final int TIER_CHIP_W = 45;
	private static final int TIER_CHIP_H = 12;
	private static final int TABLE_X = 176;
	private static final int BTN_PLUS_X = 52;

	private static int phase = 0;
	private static int timer = 0;
	private static int ticks = 0;
	/** 点击次数计数器（证明两次「+」真的各发一次动作）。 */
	private static int plusClicks = 0;
	private static Path out;

	private EditShotClient() {
	}

	static void register() {
		out = FabricLoader.getInstance().getGameDir().resolve("b31-edit-client.txt");
		try {
			Files.writeString(out, "# b31 editor client harness\n", StandardCharsets.UTF_8,
					StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
		} catch (Exception ignored) {
			// 夹具失败不影响游戏
		}
		ClientTickEvents.END_CLIENT_TICK.register(EditShotClient::tick);
	}

	private static void tick(MinecraftClient mc) {
		ticks++;
		if (ticks > TIMEOUT_TICKS) {
			log("TIMEOUT phase=" + phase);
			mc.scheduleStop();
			return;
		}
		if (mc.world == null || mc.player == null || mc.interactionManager == null) {
			return;
		}
		mc.options.hudHidden = true;
		// ★GUI 缩放必须降到 1★：默认 dev 窗口 854×480、缩放 2 ⇒ 逻辑分辨率 427×240，
		// 而编辑器面板是 300×318 ⇒ top() 为负，chip 被推到屏幕外（点得到但看不见，判据失真）。
		if (mc.options.getGuiScale().getValue() != 1) {
			mc.options.getGuiScale().setValue(1);
			if (mc.currentScreen != null) {
				mc.currentScreen.resize(mc, mc.getWindow().getScaledWidth(), mc.getWindow().getScaledHeight());
			}
		}
		// 云/天空不影响面板判据，但统一关掉能让三张图背景一致（与 T72 剪影夹具同一口径）
		mc.options.getCloudRenderMode().setValue(net.minecraft.client.option.CloudRenderMode.OFF);

		switch (phase) {
			case 0 -> {
				// quickPlay 进世界前 ~2s 是 Loading terrain，且服务端会推 Hub 界面 ⇒ 这段时间每 tick 清屏
				if (mc.currentScreen != null) {
					mc.setScreen(null);
				}
				if (mc.getOverlay() != null) {
					timer = 0;
				} else if (++timer >= SETTLE) {
					timer = 0;
					phase = 1;
				}
			}
			case 1 -> {
				EditShotHarness.request = EditShotHarness.REQ_SETUP;
				phase = 2;
				timer = 0;
			}
			case 2 -> {
				if (++timer >= 15) {
					timer = 0;
					log("PRE hand=" + net.minecraft.registry.Registries.ITEM
							.getId(mc.player.getMainHandStack().getItem())
							+ " creative=" + mc.player.getAbilities().creativeMode
							+ " screen=" + screenName(mc));
					var result = mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
					log("INTERACT result=" + result);
					phase = 3;
				}
			}
			case 3 -> {
				if (++timer >= 25) {
					timer = 0;
					String screen = screenName(mc);
					log("OPEN screen=" + screen);
					save(mc, "b31-edit-01-open");
					phase = 4;
				}
			}
			case 4 -> {
				// 模式 chip：默认 RANDOM（无 NBT 包裹走 PackageData.fromNbt(null) = RANDOM）⇒ 点 ALL(0)
				if (++timer >= 5) {
					timer = 0;
					int[] l = geom(mc);
					click(mc, l[0] + 8 + MODE_CHIP_W / 2, l[1] + MODE_Y + MODE_CHIP_H / 2, 0);
					log("CLICK mode=0 at=" + (l[0] + 8 + MODE_CHIP_W / 2) + "," + (l[1] + MODE_Y + MODE_CHIP_H / 2));
					phase = 5;
				}
			}
			case 5 -> {
				// 档位 chip：当前 rare(1) → 点 legend(3)
				if (++timer >= 5) {
					timer = 0;
					int[] l = geom(mc);
					int cx = l[0] + 8 + 3 * TIER_CHIP_W + (TIER_CHIP_W - 1) / 2;
					int cy = l[1] + TIER_Y + TIER_CHIP_H / 2;
					click(mc, cx, cy, 0);
					log("CLICK tier=3 at=" + cx + "," + cy);
					phase = 6;
				}
			}
			case 6 -> {
				if (++timer >= 8) {
					timer = 0;
					EditShotHarness.request = EditShotHarness.REQ_SEED;
					phase = 7;
				}
			}
			case 7 -> {
				if (++timer >= 12) {
					timer = 0;
					log("GRID hand=" + net.minecraft.registry.Registries.ITEM
							.getId(mc.player.getMainHandStack().getItem()) + " screen=" + screenName(mc));
					save(mc, "b31-edit-02-grid");
					phase = 8;
				}
			}
			case 8, 9, 10 -> {
				// 三次「+」：默认权重 1 ⇒ 4（若 +/- 热区错位，NBT 里不会出现这个值）
				if (timer++ >= 5) {
					timer = 0;
					int[] l = geom(mc);
					int px = l[0] + TABLE_X + BTN_PLUS_X + 5;
					int py = l[1] + EditShotHarnessGrid.GRID_Y + 9;
					click(mc, px, py, 0);
					plusClicks++;
					log("CLICK plus#" + plusClicks + " at=" + px + "," + py);
					phase++;
				}
			}
			case 11 -> {
				if (++timer >= 10) {
					timer = 0;
					save(mc, "b31-edit-03-weight");
					log("CLOSE screen=" + screenName(mc) + " plusClicks=" + plusClicks);
					if (mc.currentScreen != null) {
						// 真人按 Esc 的同一条路径 ⇒ 触发 onClosed ⇒ 三保险第 1 层
						mc.currentScreen.close();
					}
					phase = 12;
				}
			}
			case 12 -> {
				if (++timer >= 40) {
					timer = 0;
					EditShotHarness.request = EditShotHarness.REQ_VERIFY;
					phase = 13;
				}
			}
			case 13 -> {
				if (++timer >= 20) {
					log("DONE screen=" + screenName(mc)
							+ " hand=" + net.minecraft.registry.Registries.ITEM
							.getId(mc.player.getMainHandStack().getItem()));
					mc.scheduleStop();
					phase = 14;
				}
			}
			default -> {
				// 已 scheduleStop
			}
		}
	}

	/** 面板左上角（与 PackageEditScreen.left()/top() 同一公式）。 */
	private static int[] geom(MinecraftClient mc) {
		int w = mc.getWindow().getScaledWidth();
		int h = mc.getWindow().getScaledHeight();
		return new int[]{(w - PANEL_W) / 2, (h - PANEL_H) / 2};
	}

	private static void click(MinecraftClient mc, double x, double y, int button) {
		if (mc.currentScreen == null) {
			log("CLICK skipped (no screen) at=" + x + "," + y);
			return;
		}
		mc.currentScreen.mouseClicked(x, y, button);
	}

	private static String screenName(MinecraftClient mc) {
		return mc.currentScreen == null ? "null" : mc.currentScreen.getClass().getName();
	}

	private static void save(MinecraftClient mc, String name) {
		ScreenshotRecorder.saveScreenshot(mc.runDirectory, name + ".png", mc.getFramebuffer(), text -> {
		});
	}

	private static void log(String line) {
		if (out != null) {
			try {
				Files.writeString(out, line + "\n", StandardCharsets.UTF_8,
						StandardOpenOption.CREATE, StandardOpenOption.APPEND);
			} catch (Exception ignored) {
			}
		}
	}

	/** 只读一次 grid 常量，避免与 handler 的几何漂移（编译期常量内联，零成本）。 */
	private static final class EditShotHarnessGrid {
		static final int GRID_Y = com.akira.redtale.economy.legacy.screen.PackageEditHandler.GRID_Y;
	}
}
