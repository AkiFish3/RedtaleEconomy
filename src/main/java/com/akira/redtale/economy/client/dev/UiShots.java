package com.akira.redtale.economy.client.dev;

import com.akira.redtale.economy.client.checkin.CheckinClientNetworking;
import com.akira.redtale.economy.client.checkin.CheckinScreen;
import com.akira.redtale.economy.client.ShopClientState;
import com.akira.redtale.economy.client.shop.ShopScreen;
import com.akira.redtale.economy.client.tasks.TaskScreen;
import com.akira.redtale.economy.client.ui.hub.HubPage;
import com.akira.redtale.economy.client.ui.hub.HubScreen;
import com.akira.redtale.economy.net.TaskProtocol;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.util.ScreenshotRecorder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * U6 截图用的开发期夹具（不是玩法代码）。
 *
 * <p>存在标记文件 {@code <gameDir>/u6-shots.flag} 时才注册，跑完自己退出客户端。
 * 它只做三件事：串行打开四个界面 → 等 N tick 让界面画完 → 存 PNG。
 *
 * <p>为什么不用 F2：无人值守，没手按键。为什么不用外挂录制：窗口在后台时
 * 截屏工具拿到的是别的窗口。直接读 framebuffer 最稳。
 */
@Environment(EnvType.CLIENT)
public final class UiShots {

	private static final String FLAG = "u6-shots.flag";
	/** 等主世界加载的上限（tick），超时就在标题界面拍照。 */
	private static final int JOIN_TIMEOUT = 400;
	/** 每个界面渲染多少 tick 再拍（首帧字体/贴图还没绑好）。 */
	private static final int SETTLE = 25;

	private static boolean active;
	private static int tick;
	private static int step;
	private static int stepAt;
	private static boolean inWorld;
	private static final List<String> LOG = new ArrayList<>();

	private UiShots() {
	}

	public static void register() {
		Path gameDir = FabricLoader.getInstance().getGameDir();
		if (!Files.exists(gameDir.resolve(FLAG))) {
			return;
		}
		active = true;
		LOG.add("shots start, gameDir=" + gameDir.toAbsolutePath());
		ClientTickEvents.END_CLIENT_TICK.register(UiShots::onTick);
	}

	private static void onTick(MinecraftClient mc) {
		if (!active) return;
		tick++;
		if (step == 0) {
			// 等进世界：有玩家才有真实数据（余额/任务/商店页）
			if (mc.player != null && mc.currentScreen == null) {
				inWorld = true;
				LOG.add("joined world at tick " + tick);
				next(1);
			} else if (tick > JOIN_TIMEOUT) {
				LOG.add("join timeout at tick " + tick + ", fallback to title screen");
				next(1);
			}
			return;
		}
		if (tick < stepAt) return;

		switch (step) {
			case 1 -> shot(mc, "u6-01-hub-checkin.png", () -> open(mc, HubPage.CHECKIN, "rt checkin"));
			case 2 -> shot(mc, "u6-02-hub-shop.png", () -> open(mc, HubPage.SHOP, "rt shop"));
			case 3 -> shot(mc, "u6-03-tasks.png", () -> {
				if (inWorld) {
					mc.player.networkHandler.sendChatCommand("tasks");
				} else {
					mc.setScreen(new TaskScreen(TaskProtocol.Snapshot.empty()));
				}
			});
			case 4 -> shot(mc, "u6-04-checkin.png", () -> {
				if (inWorld) {
					mc.player.networkHandler.sendChatCommand("checkin");
				} else {
					mc.setScreen(new CheckinScreen(CheckinClientNetworking.LAST));
				}
			});
			case 5 -> shot(mc, "u6-05-shop-standalone.png", () -> {
				var page = ShopClientState.page();
				if (page != null && !page.shopId().isEmpty()) {
					mc.setScreen(new ShopScreen(page.shopId()));
				} else if (inWorld) {
					mc.player.networkHandler.sendChatCommand("shop");
				}
			});
			default -> finish(mc);
		}
	}

	/** 先开界面 → 等 SETTLE tick 后拍。step 里做的是「打开」，下一 step 才是「拍照」。 */
	private static void open(MinecraftClient mc, HubPage page, String command) {
		if (inWorld) {
			mc.player.networkHandler.sendChatCommand(command);
		} else {
			HubScreen.open(page);
		}
	}

	private static void shot(MinecraftClient mc, String name, Runnable action) {
		Screen cur = mc.currentScreen;
		if (cur == null || !isOurs(cur)) {
			// 还没开：这一 step 用来「打开」，再等 SETTLE
			action.run();
			stepAt = tick + SETTLE;
			LOG.add("open for " + name + " at tick " + tick + " (world=" + inWorld + ")");
			return;
		}
		// 界面已经在了：拍
		ScreenshotRecorder.saveScreenshot(mc.runDirectory, name, mc.getFramebuffer(),
				text -> LOG.add("saved " + name + " : " + text.getString()));
		LOG.add("shot " + name + " at tick " + tick + " screen=" + cur.getClass().getSimpleName());
		// 拍完清屏，下一步必须自己重新打开，否则会重复拍同一屏
		mc.setScreen(null);
		next(step + 1);
	}

	private static boolean isOurs(Screen s) {
		return s instanceof HubScreen || s instanceof TaskScreen || s instanceof CheckinScreen
				|| s instanceof ShopScreen;
	}

	private static void next(int s) {
		step = s;
		stepAt = tick + SETTLE;
		if (s > 5) {
			// 拍完再等几 tick 让最后一张落盘
			stepAt = tick + 10;
		}
	}

	private static void finish(MinecraftClient mc) {
		active = false;
		try {
			Path dir = FabricLoader.getInstance().getGameDir();
			Files.write(dir.resolve("u6-shots.log"), LOG, StandardCharsets.UTF_8);
		} catch (IOException ignored) {
			// 日志写不出来不影响已经存下的图
		}
		mc.scheduleStop();
	}
}
