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
 * <p>阶段一览（第 50 轮补齐规格 §七④⑤）：
 * 0~11 开界面 / 换档 / 切模式 / 塞格 / 三次「+」；12~17 入口②（按钮 → 点格 → W 关）；
 * 18~25 退出编辑态 / 三次「−」造权重 0 / 入口①（hover + W）；26 关窗（三保险第 1 层）；
 * 27~30 **再开一次**（§七④：重开后客户端镜像与角标是否还是 4 金 / 0 灰）；
 * 31~34 **原版背包**（§七⑤：W 不得在该界面被消费）；35~36 回读 + DONE。
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
	private static int minusClicks = 0;
	/** `GEOM` 行只写一次（判据要把逻辑坐标换算到截图像素）。 */
	private static boolean geomLogged = false;
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
				// ——— 退出权重模式（下面两条规格都要「非编辑态」才成立）———
				// 规格 §七③ 的三态是「0 灰 / 正常金 / 编辑绿」，而 weightColor() 里 editing 优先于
				// weight==0 ⇒ 留在权重模式下永远看不到灰。规格 §七① 也要求「悬停按 W」这条路
				// 与按钮模式分开验。所以先点回去。
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
					log("T80 btn2 at=" + cx + "," + cy + " (退出权重模式)");
					phase = 19;
				}
			}
			case 19, 20, 21 -> {
				// ——— 规格 §七③ 的「0 灰」：把 2 号条目（绿宝石，权重 1）按三次「−」降到 0 ———
				// 走的是权重表第 1 行的「−」热区（与「+」同一段命中代码），不是直接改 NBT。
				if (timer++ >= 5) {
					timer = 0;
					Geo g = geom(mc);
					if (g == null) {
						return;
					}
					int px = g.left + g.tableX + g.btnMinusX + BTN_HIT_PAD_X;
					int py = g.top + g.gridY + g.tableRowH + ROW_HIT_PAD_Y;
					click(mc, px, py, 0);
					minusClicks++;
					log("T80 minus#" + minusClicks + " at=" + px + "," + py);
					phase++;
				}
			}
			case 22 -> {
				// 07-zero：0 号格权重 4（金）+ 1 号格权重 0（灰），同一张图两个颜色态
				if (++timer >= 6) {
					timer = 0;
					save(mc, "b31-edit-07-zero");
					phase = 23;
				}
			}
			case 23 -> {
				// ——— 规格 §七① 入口①：悬停某格按 W → 在鼠标处弹气泡 ———
				// ★hover 状态在生产方法里算，夹具不自己赋值★：`updateHover(x, y)` 正是
				// `render()` 每帧调的那个私有方法，它把 mouseX/mouseY 换算成 `hoverGridSlot`。
				// 为什么不用「真的移动光标」：`Mouse.getX()` 由 GLFW 光标回调驱动，窗口失焦时
				// `glfwSetCursorPos` 不保证生效（本机跑法不抢焦点）⇒ 拿不到 hover 就变成
				// 「夹具缺陷伪装成产品缺陷」。这里注入的是**输入事件**（鼠标位置），
				// 之后 `keyPressed` 读 `hoverGridSlot` 的那段是纯生产路径。
				// ★必须与 keyPressed 同 tick★：`render()` 每帧都会用真实光标重算 hoverGridSlot，
				// 跨 tick 注入会被下一秒的 render 覆盖。
				if (++timer >= 6) {
					timer = 0;
					Geo g = geom(mc);
					if (g == null) {
						return;
					}
					int mx = g.left + g.gridX + 9;
					int my = g.top + g.gridY + 9;
					int hover = -1;
					try {
						Class<?> sc = Class.forName(SCREEN_CLASS);
						java.lang.reflect.Method m =
								sc.getDeclaredMethod("updateHover", double.class, double.class);
						m.setAccessible(true);
						m.invoke(mc.currentScreen, (double) mx, (double) my);
						java.lang.reflect.Field f = sc.getDeclaredField("hoverGridSlot");
						f.setAccessible(true);
						hover = f.getInt(mc.currentScreen);
					} catch (Throwable t) {
						log("HOVER-UNAVAILABLE " + t);
					}
					log("T80 hover slot=" + hover + " at=" + mx + "," + my);
					boolean handled = mc.currentScreen.keyPressed(KEY_W, 0, 0);
					log("T80 hoverW ret=" + handled);
					phase = 24;
				}
			}
			case 24 -> {
				if (++timer >= 6) {
					timer = 0;
					save(mc, "b31-edit-08-hoverpopup");
					phase = 25;
				}
			}
			case 25 -> {
				// 再按一次 W = 关闭（入口① 自己的关闭路径，与 06 的入口② 关闭分开取证）
				if (++timer >= 6) {
					timer = 0;
					boolean handled = mc.currentScreen == null
							|| mc.currentScreen.keyPressed(KEY_W, 0, 0);
					log("T80 hoverW close ret=" + handled);
					phase = 26;
				}
			}
			case 26 -> {
				if (++timer >= 10) {
					timer = 0;
					log("CLOSE screen=" + screenName(mc) + " plusClicks=" + plusClicks
							+ " minusClicks=" + minusClicks);
					if (mc.currentScreen != null) {
						// 真人按 Esc 的同一条路径 ⇒ 触发 onClosed ⇒ 三保险第 1 层
						mc.currentScreen.close();
					}
					phase = 27;
				}
			}
			case 27 -> {
				// ——— 规格 §七④「关闭重开 → 权重仍在」：真的再开一次 ———
				// 只读服务端 NBT（判据 4）证明不了这件事的另一半：**重开时客户端拿到的状态对不对**。
				// `PackageEditClientState` 是静态镜像，只在 DISCONNECT 时 reset；重开时靠
				// `PackageEditScreen.init()` 发 ACT_REQ_STATE 拉一份新的。若那条请求没发出去、
				// 或服务端没回，界面会拿**上一件包裹**的权重画角标（看起来完全正常、零报错），
				// 而且按「+」时以陈旧值为基数写回 ⇒ 静默改错数据。
				if (++timer >= 30) {
					timer = 0;
					log("INTERACT2 screen=" + screenName(mc) + " hand="
							+ net.minecraft.registry.Registries.ITEM
							.getId(mc.player.getMainHandStack().getItem()));
					var result2 = mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
					log("INTERACT2 result=" + result2);
					phase = 28;
				}
			}
			case 28 -> {
				if (++timer >= 25) {
					timer = 0;
					log("REOPEN screen=" + screenName(mc));
					save(mc, "b31-edit-09-reopen");
					phase = 29;
				}
			}
			case 29 -> {
				// 客户端镜像的权重（公开静态方法，直接读；不碰 private 字段）
				if (++timer >= 4) {
					timer = 0;
					int w0 = -1;
					int w1 = -1;
					long rev = -1L;
					try {
						w0 = com.akira.redtale.economy.legacy.screen.client.PackageEditClientState.weight(0);
						w1 = com.akira.redtale.economy.legacy.screen.client.PackageEditClientState.weight(1);
						rev = com.akira.redtale.economy.legacy.screen.client.PackageEditClientState.revision();
					} catch (Throwable t) {
						log("STATE-UNAVAILABLE " + t);
					}
					log("T80 reopen w0=" + w0 + " w1=" + w1 + " rev=" + rev);
					phase = 30;
				}
			}
			case 30 -> {
				// 关掉重开的编辑器（第二次 onClosed 也要正常走一遍）
				if (++timer >= 8) {
					timer = 0;
					log("CLOSE2 screen=" + screenName(mc));
					if (mc.currentScreen != null) {
						mc.currentScreen.close();
					}
					phase = 31;
				}
			}
			case 31 -> {
				// ——— 规格 §七⑤「普通箱子/背包行为完全不变」：打开**原版背包** ———
				// 走与玩家按 E 完全相同的路径 `new InventoryScreen(player)`；handler 就是
				// `playerScreenHandler`，与我们一行关系都没有。这里要证的是**键位没被全局劫持**：
				// 我们的 W 只在本界面里消费，原版界面上必须返回 false。
				if (++timer >= 12) {
					timer = 0;
					try {
						mc.setScreen(new net.minecraft.client.gui.screen.ingame.InventoryScreen(mc.player));
						log("T80 vanilla screen=" + screenName(mc));
					} catch (Throwable t) {
						log("VANILLA-UNAVAILABLE " + t);
					}
					phase = 32;
				}
			}
			case 32 -> {
				if (++timer >= 8) {
					timer = 0;
					boolean handled = mc.currentScreen != null
							&& mc.currentScreen.keyPressed(KEY_W, 0, 0);
					log("T80 vanilla keyW=" + handled);
					phase = 33;
				}
			}
			case 33 -> {
				if (++timer >= 6) {
					timer = 0;
					save(mc, "b31-edit-10-vanilla");
					phase = 34;
				}
			}
			case 34 -> {
				if (++timer >= 6) {
					timer = 0;
					if (mc.currentScreen != null) {
						mc.currentScreen.close();
					}
					log("CLOSE3 screen=" + screenName(mc));
					phase = 35;
				}
			}
			case 35 -> {
				if (++timer >= 40) {
					timer = 0;
					EditShotHarness.request = EditShotHarness.REQ_VERIFY;
					phase = 36;
				}
			}
			case 36 -> {
				if (++timer >= 20) {
					timer = 0;
					log("DONE screen=" + screenName(mc)
							+ " hand=" + net.minecraft.registry.Registries.ITEM
							.getId(mc.player.getMainHandStack().getItem()));
					mc.scheduleStop();
					phase = 37;
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
			Geo geo = new Geo(anchor[0], anchor[1],
					constInt(sc, "MODE_Y"), constInt(sc, "MODE_CHIP_W"), constInt(sc, "MODE_CHIP_H"),
					constInt(sc, "TIER_Y"), constInt(sc, "TIER_CHIP_W"), constInt(sc, "TIER_CHIP_H"),
					constInt(sc, "TABLE_X"), constInt(sc, "BTN_PLUS_X"), constInt(sc, "BTN_MINUS_X"),
					constInt(sc, "WEIGHT_BTN_X"), constInt(sc, "WEIGHT_BTN_Y"),
					constInt(sc, "WEIGHT_BTN_W"), constInt(sc, "WEIGHT_BTN_H"),
					// 网格坐标也走反射：`PackageEditHandler.GRID_X` 是编译期常量，直接写会**内联进夹具**，
					// 下次改布局夹具依旧拿旧值（和本轮修掉的那 6 个常量是同一个陷阱）。
					constInt(Class.forName(HANDLER_CLASS), "GRID_X"),
					constInt(Class.forName(HANDLER_CLASS), "GRID_Y"),
					constInt(Class.forName(HANDLER_CLASS), "GRID_COLS"),
					constInt(Class.forName(HANDLER_CLASS), "GRID_ROWS"),
					constInt(sc, "TABLE_ROW_H"));
			// ★角标颜色判据要把「逻辑坐标 → 截图像素」换算一遍★：截图是 **framebuffer 尺寸**（窗口像素），
			// 面板坐标是 **scaled 尺寸**（逻辑像素），两者差一个 GUI 缩放。夹具把这两组数和网格矩形
			// 一次性写进日志，判据就不用自己猜、也不用在 Python 里抄一份 GRID_X/GRID_COLS（= 又一个漂移源）。
			if (!geomLogged) {
				geomLogged = true;
				log("GEOM w=" + mc.getWindow().getScaledWidth()
						+ " h=" + mc.getWindow().getScaledHeight()
						+ " left=" + geo.left + " top=" + geo.top
						+ " grid=" + geo.gridX + "," + geo.gridY + ","
						+ (geo.gridCols * 18) + "," + (geo.gridRows * 18));
			}
			return geo;
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
					   int tableX, int btnPlusX, int btnMinusX,
					   int weightBtnX, int weightBtnY, int weightBtnW, int weightBtnH,
					   int gridX, int gridY, int gridCols, int gridRows, int tableRowH) {

		// ★「权重」按钮的可见右缘钳位（weightBtnX()）不在夹具里复刻★：
		// 那是**窄屏才生效**的分支，复刻一份等于又埋一个"改一边忘一边"的坑
		// ⇒ 直接反射调用生产方法 `panelInt(screen, "weightBtnX")`。
	}
}
