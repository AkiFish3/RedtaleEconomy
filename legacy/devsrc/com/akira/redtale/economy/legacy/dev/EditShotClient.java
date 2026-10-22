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
 * 编辑器验收夹具的**客户端半边**（开发期，不进发行 jar）。覆盖 T71（编辑器基本链路）+ T80（权重编辑器）。
 *
 * <p>走的是真实交互路径，不是直接构造界面：
 * {@code interactionManager.interactItem} → 服务端 {@code PackageItem.use} → {@code openHandledScreen}
 * → 服务端下发 OpenScreen → 客户端 {@code ScreenRegistry} 建出 {@code PackageEditScreen}。
 * 所以「界面能不能开」＝ ScreenRegistry 注册 + ExtendedScreenHandlerType 反序列化 两件事都被覆盖到了。
 *
 * <p>点击用 {@code screen.mouseClicked(x, y, button)}：这是 GuiScreen 的原始分发入口，
 * 命中判定（chip 区间 / 表格 +/- 热区）与真人点击走同一段代码。
 *
 * <p>★★几何一律**运行期解析**，本文件不留任何面板坐标字面量★★（第 46 轮重写）：
 * 旧实现把 {@code PANEL_W=300 / PANEL_H=318 / MODE_Y=20 / TIER_Y=36 / TABLE_X=176} 抄了一份，
 * 而第 39~43 轮面板已改成 408×240 / MODE_Y=16 / TIER_Y=30 / TABLE_X=286、锚点还从「居中」改成了
 * 「居中后钳到 ≥0」。两者一分叉，夹具的点击坐标就整体偏移：`TABLE_X` 差 110px ⇒ 想点权重表的「+」
 * 实际落在**网格第 13 列**上 ⇒ `super.mouseClicked` 把物品抓到光标上，后面的权重断言、条目数断言
 * 全部失真（而且日志里写的是「CLICK plus#1」，看起来完全正常）。
 * 现在：
 * <ul>
 *   <li>面板左上角 = 从**活的界面对象**反射读 {@code HandledScreen.x/y}（就是渲染/命中共用的那一份）；</li>
 *   <li>其余面板常量 = 反射读 {@code PackageEditScreen} 的 {@code private static final int}；</li>
 *   <li>解析失败就 {@code GEOM-UNAVAILABLE} + 停跑，**绝不带着猜的坐标继续点**。</li>
 * </ul>
 */
@Environment(EnvType.CLIENT)
final class EditShotClient {
	private static final int SETTLE = 40;
	private static final int TIMEOUT_TICKS = 20 * 240;

	/** 唤出气泡的键（与 PackageEditScreen.WEIGHT_KEY 同一个键；这是**输入**语义，不是几何，允许独立写）。 */
	private static final int KEY_W = org.lwjgl.glfw.GLFW.GLFW_KEY_W;
	/** '+' 按钮热区中心相对按钮左边的偏移（点中心比点边缘稳）。 */
	private static final int BTN_HIT_PAD_X = 5;
	private static final int ROW_HIT_PAD_Y = 9;

	private static final String SCREEN_CLASS =
			"com.akira.redtale.economy.legacy.screen.client.PackageEditScreen";
	/** 网格几何的唯一真值在服务端 handler 里（`PackageEditHandler.GRID_X/GRID_Y`）。 */
	private static final String HANDLER_CLASS =
			"com.akira.redtale.economy.legacy.screen.PackageEditHandler";

	private static int phase = 0;
	private static int timer = 0;
	private static int ticks = 0;
	/** 点击次数计数器（证明三次「+」真的各发一次动作）。 */
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
		// 面板 408×240 虽然刚好放得下，但 427 宽只留 19px 余量、任何加宽都会把权重表顶出屏幕，
		// 判据容易受窗口尺寸影响。gs1 ⇒ 854×480，几何一目了然。
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
					log("OPEN screen=" + screenName(mc));
					save(mc, "b31-edit-01-open");
					phase = 4;
				}
			}
			case 4 -> {
				// 模式 chip：默认 CHOOSE/RANDOM（无 NBT 包裹走 PackageData.fromNbt(null) = RANDOM）⇒ 点 ALL(0)
				if (++timer >= 5) {
					timer = 0;
					Geo g = geom(mc);
					if (g == null) {
						return;
					}
					int cx = g.left + 8 + g.modeChipW / 2;
					int cy = g.top + g.modeY + g.modeChipH / 2;
					click(mc, cx, cy, 0);
					log("CLICK mode=0 at=" + cx + "," + cy);
					phase = 5;
				}
			}
			case 5 -> {
				// 档位 chip：当前 rare(1) → 点 legend(3)
				if (++timer >= 5) {
					timer = 0;
					Geo g = geom(mc);
					if (g == null) {
						return;
					}
					int cx = g.left + 8 + 3 * g.tierChipW + (g.tierChipW - 1) / 2;
					int cy = g.top + g.tierY + g.tierChipH / 2;
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
					Geo g = geom(mc);
					if (g == null) {
						return;
					}
					int px = g.left + g.tableX + g.btnPlusX + BTN_HIT_PAD_X;
					int py = g.top + g.gridY + ROW_HIT_PAD_Y;
					click(mc, px, py, 0);
					plusClicks++;
					log("CLICK plus#" + plusClicks + " at=" + px + "," + py);
					phase++;
				}
			}
			case 11 -> {
				// 三次「+」之后的静态留证（权重 4、非编辑态 ⇒ 金色角标）
				if (++timer >= 6) {
					timer = 0;
					save(mc, "b31-edit-03-weight");
					phase = 12;
				}
			}
			case 12 -> {
				// ——— T80 入口② 上：右上角「权重」按钮切模式 ———
				if (++timer >= 6) {
					timer = 0;
					Geo g = geom(mc);
					if (g == null) {
						return;
					}
					int bx;
					try {
						bx = panelInt(mc.currentScreen, "weightBtnX");
					} catch (Throwable t) {
						log("GEOM-UNAVAILABLE phase=" + phase + " (weightBtnX: " + t + ")");
						mc.scheduleStop();
						return;
					}
					int cx = g.left + bx + g.weightBtnW / 2;
					int cy = g.top + g.weightBtnY + g.weightBtnH / 2;
					click(mc, cx, cy, 0);
					log("T80 btn at=" + cx + "," + cy + " relBtnX=" + bx);
					phase = 13;
				}
			}
			case 13 -> {
				// ★截图必须比点击晚 ≥2 tick★：`ScreenshotRecorder.saveScreenshot` 抓的是**当前已渲染完**
				// 的 framebuffer，而点击发生在 END_CLIENT_TICK ⇒ 同一 tick 里截图拿到的是点击之前那一帧。
				// 旧写法「点完立刻 save」会让 04/05/06 三张图全部错位到前一状态（气泡永远拍不到），
				// 而 `_b31_edit_check.py` 的第 11/12 条像素判据会因此恒 FAIL。
				if (++timer >= 6) {
					timer = 0;
					save(mc, "b31-edit-04-weightmode");
					phase = 14;
				}
			}
			case 14 -> {
				// ——— T80 入口② 下：权重模式下点有物品的格子 ⇒ 弹气泡且**不搬物品** ———
				// 反证方式：若这里漏了拦截，点击会走 super.mouseClicked 把物品抓到光标上，
				// 关窗后服务端的 VERIFY items.count 就会从 2 掉到 1（`_b31_edit_check.py` 第 13 条）。
				if (++timer >= 6) {
					timer = 0;
					Geo g = geom(mc);
					if (g == null) {
						return;
					}
					int cx = g.left + g.gridX + 9;
					int cy = g.top + g.gridY + 9;
					click(mc, cx, cy, 0);
					log("T80 cell at=" + cx + "," + cy);
					phase = 15;
				}
			}
			case 15 -> {
				if (++timer >= 6) {
					timer = 0;
					save(mc, "b31-edit-05-popup");
					phase = 16;
				}
			}
			case 16 -> {
				// ——— T80 入口①：气泡开着时按 W 必须被消费（返回 true = 关闭气泡） ———
				if (++timer >= 6) {
					timer = 0;
					if (mc.currentScreen == null) {
						log("T80 W close ret=no-screen");
					} else {
						boolean handled = mc.currentScreen.keyPressed(KEY_W, 0, 0);
						log("T80 W close ret=" + handled);
					}
					phase = 17;
				}
			}
			case 17 -> {
				if (++timer >= 6) {
					timer = 0;
					save(mc, "b31-edit-06-popup-closed");
					phase = 18;
				}
			}
			case 18 -> {
				if (++timer >= 10) {
					timer = 0;
					log("CLOSE screen=" + screenName(mc) + " plusClicks=" + plusClicks);
					if (mc.currentScreen != null) {
						// 真人按 Esc 的同一条路径 ⇒ 触发 onClosed ⇒ 三保险第 1 层
						mc.currentScreen.close();
					}
					phase = 19;
				}
			}
			case 19 -> {
				if (++timer >= 40) {
					timer = 0;
					EditShotHarness.request = EditShotHarness.REQ_VERIFY;
					phase = 20;
				}
			}
			case 20 -> {
				if (++timer >= 20) {
					log("DONE screen=" + screenName(mc)
							+ " hand=" + net.minecraft.registry.Registries.ITEM
							.getId(mc.player.getMainHandStack().getItem()));
					mc.scheduleStop();
					phase = 21;
				}
			}
			default -> {
				// 已 scheduleStop
			}
		}
	}

	/**
	 * 从**活的界面对象**解析面板几何。返回 null = 解析失败（调用方必须停跑，不许猜）。
	 *
	 * <p>为什么不用「与 PackageEditScreen 相同的公式」重算：那等于把真值抄第二份，
	 * 第 39~43 轮已经证明抄的那份一定会漂（面板尺寸、锚点钳位、TABLE_X 各改过一次）。
	 * 读 {@code HandledScreen.x/y} 拿到的是渲染槽位与命中判定共用的那一份真值，天然同步。
	 */
	private static Geo geom(MinecraftClient mc) {
		if (mc.currentScreen == null) {
			log("GEOM-UNAVAILABLE phase=" + phase + " (no screen)");
			mc.scheduleStop();
			return null;
		}
		try {
			Class<?> sc = Class.forName(SCREEN_CLASS);
			int[] anchor = anchorOf(mc.currentScreen, sc, mc);
			if (anchor == null) {
				log("GEOM-UNAVAILABLE phase=" + phase + " (anchor)");
				mc.scheduleStop();
				return null;
			}
			return new Geo(anchor[0], anchor[1],
					constInt(sc, "MODE_Y"), constInt(sc, "MODE_CHIP_W"), constInt(sc, "MODE_CHIP_H"),
					constInt(sc, "TIER_Y"), constInt(sc, "TIER_CHIP_W"), constInt(sc, "TIER_CHIP_H"),
					constInt(sc, "TABLE_X"), constInt(sc, "BTN_PLUS_X"),
					constInt(sc, "WEIGHT_BTN_X"), constInt(sc, "WEIGHT_BTN_Y"),
					constInt(sc, "WEIGHT_BTN_W"), constInt(sc, "WEIGHT_BTN_H"),
					// 网格坐标也走反射：`PackageEditHandler.GRID_X` 是编译期常量，直接写会**内联进夹具**，
					// 下次改布局夹具依旧拿旧值（和本轮修掉的那 6 个常量是同一个陷阱）。
					constInt(Class.forName(HANDLER_CLASS), "GRID_X"),
					constInt(Class.forName(HANDLER_CLASS), "GRID_Y"));
		} catch (Throwable t) {
			log("GEOM-UNAVAILABLE phase=" + phase + " (" + t + ")");
			mc.scheduleStop();
			return null;
		}
	}

	/** 面板左上角：先反射读 {@code HandledScreen.x/y}，读不到再按 init() 同口径重算（居中 + 钳 ≥0）。 */
	private static int[] anchorOf(Object screen, Class<?> screenClass, MinecraftClient mc) {
		for (Class<?> c = screen.getClass(); c != null; c = c.getSuperclass()) {
			try {
				java.lang.reflect.Field fx = c.getDeclaredField("x");
				java.lang.reflect.Field fy = c.getDeclaredField("y");
				fx.setAccessible(true);
				fy.setAccessible(true);
				return new int[]{fx.getInt(screen), fy.getInt(screen)};
			} catch (NoSuchFieldException ignored) {
				// 继续往上找
			} catch (Throwable t) {
				break;
			}
		}
		try {
			int pw = constInt(screenClass, "PANEL_W");
			int ph = constInt(screenClass, "PANEL_H");
			int w = mc.getWindow().getScaledWidth();
			int h = mc.getWindow().getScaledHeight();
			// 与 PackageEditScreen.init() 完全同口径（居中后钳到 ≥0）
			return new int[]{Math.max(0, (w - pw) / 2), Math.max(0, (h - ph) / 2)};
		} catch (Throwable t) {
			return null;
		}
	}

	/**
	 * 反射调用 {@code PackageEditScreen} 的**无参 private 实例方法**并取 int 返回值。
	 *
	 * <p>用于 `weightBtnX()` 这类「几何由状态算出来」的方法（它依赖窗口宽度与锚点钳位）：
	 * 夹具复刻一份公式就会在窄屏路径上和生产分叉，而窄屏恰恰是最容易错的那一档。
	 */
	private static int panelInt(Object screen, String method) throws Exception {
		java.lang.reflect.Method m = Class.forName(SCREEN_CLASS).getDeclaredMethod(method);
		m.setAccessible(true);
		return (Integer) m.invoke(screen);
	}

	/** 读 {@code PackageEditScreen} 的 {@code private static final int}（常量表达式有 ConstantValue，反射可取）。 */
	private static int constInt(Class<?> c, String name) throws Exception {
		java.lang.reflect.Field f = c.getDeclaredField(name);
		f.setAccessible(true);
		return f.getInt(null);
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

	/** 运行期解析出来的面板几何（全是**面板相对**坐标，除 left/top）。 */
	private record Geo(int left, int top,
					   int modeY, int modeChipW, int modeChipH,
					   int tierY, int tierChipW, int tierChipH,
					   int tableX, int btnPlusX,
					   int weightBtnX, int weightBtnY, int weightBtnW, int weightBtnH,
					   int gridX, int gridY) {

		// ★「权重」按钮的可见右缘钳位（weightBtnX()）不在夹具里复刻★：
		// 那是**窄屏才生效**的分支，复刻一份等于又埋一个"改一边忘一边"的坑
		// ⇒ 直接反射调用生产方法 `panelInt(screen, "weightBtnX")`。
	}
}
