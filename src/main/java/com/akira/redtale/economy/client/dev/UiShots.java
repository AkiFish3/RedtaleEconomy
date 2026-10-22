package com.akira.redtale.economy.client.dev;

import com.akira.redtale.economy.client.ui.hub.HubDev;

import com.akira.redtale.economy.client.checkin.CheckinClientNetworking;
import com.akira.redtale.economy.client.checkin.CheckinScreen;
import com.akira.redtale.economy.client.ShopClientState;
import com.akira.redtale.economy.client.shop.ShopScreen;
import com.akira.redtale.economy.client.tasks.TaskScreen;
import com.akira.redtale.economy.client.ui.hub.ClientUiConfig;
import com.akira.redtale.economy.client.ui.hub.HubBoardPage;
import com.akira.redtale.economy.client.ui.hub.HubLedgerPage;
import com.akira.redtale.economy.client.ui.hub.HubMoney;
import com.akira.redtale.economy.client.ui.hub.HubPage;
import com.akira.redtale.economy.client.ui.hub.HubScreen;
import com.akira.redtale.economy.client.ui.hub.HubSettingsPage;
import com.akira.redtale.economy.client.ui.hub.HubTheme;
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
	/**
	 * T48 步骤白名单（可选）：{@code <gameDir>/u6-steps.txt}，内容形如 {@code 94,95,96,97}
	 * 或按行写。存在且非空时只对列出的 step 拍照，其余 step 仍执行但其动作用「快速推进」，
	 * 不再等 25 tick 渲染、也不再切全屏（一次全量 sweep 要 ~5 分钟，回归一轮只需几张图）。
	 *
	 * <p>★它只是过滤器，不改顺序★：某步依赖前一步状态（如 95 依赖 94 发的 /rt ledger），
	 * 就必须把前一步一起写进白名单。
	 */
	private static final String STEPS_FILE = "u6-steps.txt";
	/** 白名单外的 step 推进间隔（tick）。只够命令出队，不保证界面画好——反正不拍。 */
	private static final int FAST_SETTLE = 2;
	/** 等主世界加载的上限（tick），超时就在标题界面拍照。 */
	private static final int JOIN_TIMEOUT = 400;
	/** 每个界面渲染多少 tick 再拍（首帧字体/贴图还没绑好）。 */
	private static final int SETTLE = 25;
	/** 最后一个拍照 step（U4 5 + U11 6 + U9 13 + U10 设置页 16 + R39 音效探针 2 + U13 9 + 非 OP 2 + T44 商店滚动 1，到 89）。 */
	private static final int LAST_STEP = 241;
	/**
	 * 多人模式（连专用服务器）的起点 step。
	 *
	 * <p>单机那套 1..86 里一半命令要 OP（/give、/deop、pass unlock），非 OP 客户端跑会一路失败、
	 * 白等 12 次重试。所以只要检测到「连的是远程服务器」，直接跳到这条精简线：
	 * 只验 U10 的权限分支（设置页行数 / 管理员区可见性），外加一次「点管理员行必须打不中」。
	 */
	private static final int MULTI_BASE = 200;

	// ——— T44：双分辨率 ———
	/** 窗口态强制尺寸（Aki 定的验收口径：854×480 + guiScale=2 ⇒ 427×240 逻辑像素）。 */
	private static final int WIN_W = 854;
	private static final int WIN_H = 480;
	/** 强制 GUI 缩放（0=自动，2=固定）。 */
	private static final int GUI_SCALE = 2;

	// ——— T44 轮转：本档位（由 flag 文件内容覆盖，默认 = Aki 定的窗口口径） ———
	/** 生效的 guiScale / 窗口尺寸；{@code scale=0} 表示「自动」。 */
	private static int guiScale = GUI_SCALE;
	private static int winW = WIN_W;
	private static int winH = WIN_H;
	/**
	 * 切分辨率后的 settle（tick）。
	 *
	 * <p>换 framebuffer 会让 GuiGraphics 的投影矩阵重建、字体图集重新上传，
	 * 而且 resize 回调是在下一帧才跑，所以 25 tick 的普通 SETTLE 不够（实测会拍到旧尺寸的残影）。
	 */
	private static final int RES_SETTLE = 40;

	private static boolean active;
	private static int tick;
	private static int step;
	private static int stepAt;
	private static boolean inWorld;
	/** 同一步重试上限（避免界面打不开就死等）。 */
	private static final int MAX_ATTEMPTS = 12;
	private static int attempts;
	/** 分辨率（窗口尺寸 + guiScale）是否已落地。 */
	private static boolean resReady;
	/** 请求切全屏的那个 tick；-1 表示当前没有待生效的切换。 */
	private static int fsToggleAt = -1;
	/** 本次全屏里是否已经重新应用过 guiScale（窗口态会被钳制，必须进全屏后再设一次）。 */
	private static boolean fsScaleApplied;
	/** 成对率统计：窗口版 / 全屏版各存了几张。 */
	private static int winShots;
	private static int fsShots;
	/** T48：要拍照的 step 集合；{@code null} = 全量（旧行为）。 */
	private static java.util.Set<Integer> whitelist;
	/** 当前 step 是否只是「跑状态、不拍照」。 */
	private static boolean stateOnly;
	/**
	 * 白名单模式下「本 step 的 action 是否已跑过一次」。
	 *
	 * <p>不加这条会拍到上一屏：state-only 步骤（例如 step 1 的 /rt checkin）刚开出来的
	 * HubScreen 还在，白名单里的 step 2 一看「screen 是我们的」就跳过自己的 action 直接拍，
	 * 结果 u6-02 存的是签到页（u6-19 那次「HubScreen 已开着」的假阳性同源）。
	 */
	private static boolean actionRanThisStep;
	/** 被快速跳过的 step 数（收尾写进日志，用来证明白名单真的生效）。 */
	private static int skipped;
	/** 已落盘的日志行数（增量追加用；被别的夹具提前 scheduleStop 时至少留下已有的证据）。 */
	private static int persisted;
	/** T52：本轮已因致命界面取证并退出（防止每 tick 重复落盘）。 */
	private static boolean fatalSeen;
	/** T52 的取证文件：存在即代表「本轮是被致命界面中止的」，跑完由调用方检查。 */
	private static final String FATAL_FILE = "u6-fatal.txt";
	/** T52 自检开关（flag 里写 {@code probe=fatal}）：第 20 tick 自己弹一张致命界面，验证取证链路。 */
	private static boolean fatalProbe;
	private static final List<String> LOG = new ArrayList<>();

	private UiShots() {
	}

	public static void register() {
		Path gameDir = FabricLoader.getInstance().getGameDir();
		if (!Files.exists(gameDir.resolve(FLAG))) {
			return;
		}
		active = true;
		// T44 轮转：flag 内容可覆盖档位，例如 "scale=3" 或 "win=1280x720\nscale=0"。
		// 组合来自 _shot_plan.py next（fs:s3 / fs:auto 等），不再需要改代码重编。
		try {
			String body = Files.readString(gameDir.resolve(FLAG), StandardCharsets.UTF_8).trim();
			for (String line : body.split("[\\r\\n]+")) {
				String s = line.trim();
				if (s.startsWith("scale=")) {
					guiScale = Integer.parseInt(s.substring(6).trim());
					LOG.add("flag override guiScale=" + guiScale);
				} else if (s.startsWith("win=")) {
					String[] wh = s.substring(4).trim().split("[xX]");
					if (wh.length == 2) {
						winW = Integer.parseInt(wh[0].trim());
						winH = Integer.parseInt(wh[1].trim());
						LOG.add("flag override window=" + winW + "x" + winH);
					}
				} else if (s.equals("probe=fatal")) {
					// T52 自检：人为弹一张致命界面，证明「取证 + 立即退出」真的会触发。
					// 只有 flag 显式写了才开，正常截图轮不受影响。
					fatalProbe = true;
					LOG.add("flag override probe=fatal (T52 self-check)");
				}
			}
		} catch (Exception e) {
			LOG.add("flag parse failed (" + e.getMessage() + "), use default scale=" + GUI_SCALE);
		}
		LOG.add("shots start, gameDir=" + gameDir.toAbsolutePath());
		warnForeignFlags(gameDir);
		readWhitelist(gameDir);
		try {
			// 每轮从头记：不然上一轮的追加会混进来，pairs 统计就不可信了
			Files.deleteIfExists(gameDir.resolve("u6-shots.log"));
			// T52：上一轮的致命取证不能留到这一轮冒充（存在性就是「本轮失败」的判据）
			Files.deleteIfExists(gameDir.resolve(FATAL_FILE));
		} catch (IOException ignored) {
			// 删不掉就追加，至少不丢
		}
		flushLog(gameDir);
		ClientTickEvents.END_CLIENT_TICK.register(UiShots::onTick);
	}

	/**
	 * 别的队列的截图夹具在同一次 client 里也会 arm，它们跑完会 {@code scheduleStop()}。
	 *
	 * <p>2026-10-21 实测：{@code run/} 是全队共用的 gameDir，B 的 {@code b14-pkg-shots.flag}
	 * 留在那里 ⇒ 同一进程里 B 的夹具 15 秒跑完就把客户端关了，我方一张图都拍不到、
	 * 连 u6-shots.log 都不会生成（finish() 永远到不了）。这条 warning 是给下一次的定位线索。
	 */
	private static void warnForeignFlags(Path gameDir) {
		try (var stream = Files.list(gameDir)) {
			for (Path p : stream.toList()) {
				String n = p.getFileName().toString();
				if (n.endsWith(".flag") && n.contains("shots") && !n.equals(FLAG)) {
					LOG.add("WARN foreign shot flag present: " + n
							+ " (另一队列的夹具会提前 scheduleStop，本轮大概率拍不到图)");
				}
			}
		} catch (IOException e) {
			LOG.add("WARN flag scan failed: " + e.getMessage());
		}
	}

	/** 增量落盘：拍一张就追加一次，别等 finish()（被提前关掉时才有证据）。 */
	private static void flushLog(Path dir) {
		if (persisted >= LOG.size()) {
			return;
		}
		List<String> tail = new ArrayList<>(LOG.subList(persisted, LOG.size()));
		try {
			Files.write(dir.resolve("u6-shots.log"), tail, StandardCharsets.UTF_8,
					java.nio.file.StandardOpenOption.CREATE,
					java.nio.file.StandardOpenOption.APPEND);
			persisted = LOG.size();
		} catch (IOException ignored) {
			// 写不出来不影响已经存下的图
		}
	}

	/** T48：读步骤白名单。文件不存在或解析出 0 个号 ⇒ 保持全量。 */
	private static void readWhitelist(Path gameDir) {
		Path f = gameDir.resolve(STEPS_FILE);
		if (!Files.exists(f)) {
			return;
		}
		try {
			String body = Files.readString(f, StandardCharsets.UTF_8).trim();
			java.util.Set<Integer> set = new java.util.TreeSet<>();
			for (String tok : body.split("[,\\s]+")) {
				String s = tok.trim();
				if (s.isEmpty() || s.startsWith("#")) continue;
				try {
					set.add(Integer.parseInt(s));
				} catch (NumberFormatException e) {
					LOG.add("steps: ignore bad token \"" + s + "\"");
				}
			}
			if (set.isEmpty()) {
				LOG.add("steps: " + STEPS_FILE + " present but empty -> full sweep");
				return;
			}
			whitelist = set;
			LOG.add("steps: whitelist=" + set + " (others = state-only, no capture)");
		} catch (Exception e) {
			LOG.add("steps: read failed (" + e.getMessage() + ") -> full sweep");
		}
	}

	private static void onTick(MinecraftClient mc) {
		if (!active) return;
		tick++;
		// T70 快速探针：不依赖跑到 step 237（整轮 sweep 在装了 REI/Xaero 后极慢）。
		// 资源管理器一就绪就能判「Numismatic 贴图能不能找到」，写盘后立刻 flush。
		if (tick == 40 || tick == 200) {
			LOG.add("money tick=" + tick + " " + HubMoney.texProbe());
			flushLog(mc.runDirectory.toPath());
		}
		if (fatalProbe && tick > 20 && !fatalSeen && mc.getOverlay() == null) {
			// 自检：故意弹致命界面（文案写明是夹具自检，别让人误判成真崩溃）。
			// ★必须等 overlay 为 null★：tick 20 时还在 Reloading ResourceManager，
			// 此时 scheduleStop 会让 TextureManager.close 抛 ConcurrentModificationException，
			// 日志里看着像真崩溃（2026-10-22 首跑实测）。
			mc.setScreen(new net.minecraft.client.gui.screen.FatalErrorScreen(
					net.minecraft.text.Text.literal("T52 probe: fatal screen"),
					net.minecraft.text.Text.literal("夹具自检，非真实崩溃")));
		}
		// ★T52：先判致命界面，再走正常流程★。放在 resReady 之前，是因为缺依赖的
		// NoticeScreen 在窗口/分辨率都还没就绪时就已弹出 —— 等 setupResolution 跑完再判，
		// 会白等几百 tick 然后超时（那正是「重开一次还是没图」的成因）。
		if (checkFatal(mc)) return;
		if (!resReady) {
			// 世界还没进就得先把窗口尺寸定下来，否则后面所有布局都是按别的大小算的
			setupResolution(mc);
			return;
		}
		if (step == 0) {
			// 等进世界：有玩家才有真实数据（余额/任务/商店页）
			if (mc.player != null && mc.currentScreen == null) {
				inWorld = true;
				boolean multi = mc.getCurrentServerEntry() != null;
				LOG.add("joined " + (multi ? "multiplayer " + mc.getCurrentServerEntry().address
						: "singleplayer") + " at tick " + tick);
				next(multi ? MULTI_BASE : 1);
			} else if (tick > JOIN_TIMEOUT) {
				LOG.add("join timeout at tick " + tick + ", fallback to title screen");
				next(1);
			}
			return;
		}
		if (tick < stepAt) {
			// ★清聊天要留出两帧★：截图抓的是已画好的帧，且 /give 的反馈比 clear 晚到；
			// 在拍照前 2 tick 清一次，画面里才干净（在拍照当 tick 清是无效的）。
			if (tick == stepAt - 2) mc.inGameHud.getChatHud().clear(true);
			return;
		}

		stateOnly = whitelist != null && !whitelist.contains(step);
		switch (step) {
			case 1 -> shot(mc, "u6-01-hub-checkin.png", () -> open(mc, HubPage.CHECKIN, "rt checkin"));
			case 2 -> shot(mc, "u6-02-hub-shop.png", () -> open(mc, HubPage.SHOP, "rt shop"));
			case 3 -> shot(mc, "u6-03-tasks.png", () -> {
				if (inWorld) {
					// 夹具：给点材料，「可缴纳」状态在截图里要能看出来
					mc.player.networkHandler.sendChatCommand("give @s minecraft:oak_log 16");
					mc.player.networkHandler.sendChatCommand("give @s minecraft:birch_log 16");
					mc.player.networkHandler.sendChatCommand("give @s minecraft:coal 16");
					mc.player.networkHandler.sendChatCommand("give @s minecraft:bread 8");
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
			// R42：解锁前先拍一张付费轨（画暗），解锁后再拍一张，靠明暗对比判断
			case 6 -> shot(mc, "u6-06-pass-locked.png", () -> open(mc, HubPage.PASS, "rt pass"));
			case 7 -> act(mc, () -> {
				if (mc.player != null) {
					mc.player.networkHandler.sendChatCommand(
							"rt admin pass unlock " + mc.player.getGameProfile().getName());
				}
			});
			case 8 -> shot(mc, "u6-07-pass-unlocked.png", () -> open(mc, HubPage.PASS, "rt pass"));
			// U10：设置页（管理员区默认收起 → 展开 → 滚到底看管理员行）
			case 9 -> shot(mc, "u6-08-settings.png", () -> open(mc, HubPage.SETTINGS, "rt settings"));
			case 10 -> act(mc, () -> HubSettingsPage.setAdminOpen(true));
			case 11 -> shot(mc, "u6-09-settings-admin.png", () -> {
				HubSettingsPage.setAdminOpen(true);
				open(mc, HubPage.SETTINGS, "rt settings");
			});
			case 12 -> act(mc, () -> HubSettingsPage.scrollBy(400));
			case 13 -> shot(mc, "u6-10-settings-bottom.png", () -> {
				HubSettingsPage.setAdminOpen(true);
				HubSettingsPage.scrollBy(400);
				open(mc, HubPage.SETTINGS, "rt settings");
			});
			// R39：主题切到原版（视觉差异），拍完立刻切回
			case 14 -> shot(mc, "u6-11-hub-vanilla.png", () -> {
				ClientUiConfig.theme = "vanilla";
				ClientUiConfig.save();
				HubTheme.apply("vanilla");
				open(mc, HubPage.CHECKIN, "rt checkin");
			});
			case 15 -> act(mc, () -> {
				ClientUiConfig.theme = "dark";
				ClientUiConfig.save();
				HubTheme.apply("dark");
			});
			// ★U4：侧边栏验收★ —— 宽窗口（1280x720 + guiScale 2 = 640x360 逻辑）下才有
			// 「图标+文字」；再用真实 mouseClicked 命中路径点三页，证明一个界面能跳到各功能。
			case 16 -> shotKeep(mc, "u6-12-hub-sidebar-text.png",
					() -> open(mc, HubPage.CHECKIN, "rt checkin"));
			case 17 -> act(mc, () -> clickEntry(mc, "市场", 2));
			case 18 -> shotKeep(mc, "u6-13-hub-click-market.png", () -> { });
			case 19 -> act(mc, () -> clickEntry(mc, "排行", 9));
			case 20 -> shotKeep(mc, "u6-14-hub-click-rank.png", () -> { });
			// showLabels=false ⇒ 侧边栏收成 40px 纯图标（tooltip 要鼠标悬停，截图验不到）
			case 21 -> act(mc, () -> {
				ClientUiConfig.showLabels = false;
				ClientUiConfig.save();
				LOG.add("showLabels=false, sidebar should be icon-only");
			});
			case 22 -> shotKeep(mc, "u6-15-hub-sidebar-icons.png", () -> { });
			case 23 -> act(mc, () -> {
				ClientUiConfig.showLabels = true;
				ClientUiConfig.save();
			});
			// ★U11：商店目录树★ —— 根 → 第 2 级 → 第 3 级（面包屑与列表同步收窄），
			// 再走真实 charTyped 写搜索词，验「搜索真的过服务端」。
			// 先关掉上一屏（rank），否则 shotKeep 看到 HubScreen 就以为"已打开"，拍的还是排行页。
			case 24 -> act(mc, () -> mc.setScreen(null));
			case 25 -> shotKeep(mc, "u6-16-shop-tree.png", () -> open(mc, HubPage.SHOP, "rt shop"));
			case 26 -> act(mc, () -> clickTree(mc, 0));
			case 27 -> shotKeep(mc, "u6-17-shop-tree-l2.png", () -> { });
			case 28 -> act(mc, () -> clickTree(mc, 0));
			case 29 -> shotKeep(mc, "u6-18-shop-tree-l3.png", () -> { });
			case 30 -> act(mc, () -> search(mc, "oak"));
			case 31 -> shotKeep(mc, "u6-19-shop-search-oak.png", () -> { });
			// ★U9：排行榜★ 默认（只看活跃）→ 点开关 → 全量 → 打开榜单下拉。
			// 判断依据不只靠看图：每步把 RankClientNetworking.LAST 的行数 / total / self 写进日志，
			// 图能看到 6 行 vs 10 行、下拉里的五个榜名。
			case 32 -> act(mc, () -> mc.setScreen(null));
			case 33 -> shotKeep(mc, "u6-20-rank-active.png", () -> open(mc, HubPage.RANK, "rt rank"));
			case 34 -> act(mc, () -> {
				// 打开界面后 25 tick 才走到这里 ⇒ LAST 已是服务端回来的活跃榜
				logRank(mc, "active");
				clickRankToggle(mc);
			});
			case 35 -> shotKeep(mc, "u6-21-rank-all.png", () -> { });
			case 36 -> act(mc, () -> {
				logRank(mc, "all");
				clickRankDropdown(mc);
			});
			case 37 -> shotKeep(mc, "u6-22-rank-boards.png", () -> { });
			// §43.6：「同步」60 秒冷却 —— 第一次点真的发请求，紧接着再点应该被冷却拦下
			case 38 -> act(mc, () -> clickRankDropdown(mc));   // 关掉下拉，否则下一次点击被它吃掉
			case 39 -> act(mc, () -> clickRankSync(mc, "first"));
			case 40 -> shotKeep(mc, "u6-23-rank-sync.png", () -> { });
			case 41 -> act(mc, () -> clickRankSync(mc, "second"));
			case 42 -> shotKeep(mc, "u6-24-rank-sync-cooldown.png", () -> { });
			// 二级榜：下拉里选「收入」—— 该榜没数据源，界面应明确说"尚未接入"而不是照搬财富数据
			case 43 -> act(mc, () -> clickRankDropdown(mc));
			case 44 -> act(mc, () -> clickRankBoard(mc, 1));
			case 45 -> shotKeep(mc, "u6-25-rank-board-income.png", () -> { });
			// ★U10 收口：设置页还剩三处「纸面承诺、实机没验」的行为★
			// ① 管理员折叠区能否展开（点「管理工具」那一行，走真实 mouseClicked）
			// ② 「修改键位」是否真跳原版界面　③ 「显示语言」是否真跳原版界面
			// 都是「点一下 → 界面变了」的可截图状态，不属于文档 §7 的悬停/拖拽/滚轮。
			case 46 -> act(mc, () -> {
				mc.setScreen(null);
				HubSettingsPage.setAdminOpen(false);
				HubSettingsPage.scrollTo(0);
				LOG.add("settings reset: adminOpen=" + HubSettingsPage.devAdminOpen()
						+ " scroll=" + HubSettingsPage.devScroll());
			});
			case 47 -> shotKeep(mc, "u6-26-settings-collapsed.png",
					() -> open(mc, HubPage.SETTINGS, "rt settings"));
			case 48 -> act(mc, () -> {
				// 427x240 逻辑分辨率下「管理员」整组在折叠之外 ⇒ 先滚到底再点，
				// 否则 rowCenter 返回 null（第一次跑就踩到：hit=false）。
				if (mc.currentScreen instanceof HubScreen hub) {
					int max = HubDev.settingsScrollMax(hub);
					HubDev.scrollSettings(hub, max);
					LOG.add("settings scroll to " + max + " (contentH="
							+ HubDev.settingsContentHeight(hub) + ")");
				}
				clickSettingsRow(mc, HubSettingsPage.Row.ADMIN_HEAD, "expand");
			});
			case 49 -> act(mc, () -> {
				// 展开后内容变长，滚动上限也变 ⇒ 重新滚到底，否则「重载配置」那行被裁掉
				if (mc.currentScreen instanceof HubScreen hub) {
					int max = HubDev.settingsScrollMax(hub);
					HubDev.scrollSettings(hub, max);
					LOG.add("settings scroll after expand to " + max);
				}
			});
			case 50 -> shotKeep(mc, "u6-27-settings-expanded.png", () -> { });
			// 再点一次：折叠区必须能双向切换，不是「展开就回不去」
			case 51 -> act(mc, () -> clickSettingsRow(mc, HubSettingsPage.Row.ADMIN_HEAD, "collapse"));
			case 52 -> shotKeep(mc, "u6-33-settings-collapsed-again.png", () -> { });
			case 53 -> act(mc, () -> clickSettingsRow(mc, HubSettingsPage.Row.LANGUAGE, "language"));
			case 54 -> shotRaw(mc, "u6-34-settings-language.png");
			case 55 -> act(mc, () -> {
				mc.setScreen(null);
				HubSettingsPage.setAdminOpen(true);
				HubSettingsPage.scrollTo(0);
			});
			case 56 -> shotKeep(mc, "u6-35-settings-back.png",
					() -> open(mc, HubPage.SETTINGS, "rt settings"));
			case 57 -> act(mc, () -> clickSettingsRow(mc, HubSettingsPage.Row.KEYBINDS, "keybinds"));
			case 58 -> shotRaw(mc, "u6-36-settings-keybinds.png");
			// 设置页在原版主题下的可读性（分组标题/悬停行曾写死深色值）
			case 59 -> act(mc, () -> {
				mc.setScreen(null);
				ClientUiConfig.theme = "vanilla";
				ClientUiConfig.save();
				HubTheme.apply("vanilla");
				HubSettingsPage.scrollTo(0);
			});
			case 60 -> shotKeep(mc, "u6-37-settings-vanilla.png",
					() -> open(mc, HubPage.SETTINGS, "rt settings"));
			case 61 -> act(mc, () -> {
				ClientUiConfig.theme = "dark";
				ClientUiConfig.save();
				HubTheme.apply("dark");
			});
			// ★R39 收口：按钮音效开关★ 截不到声音，改为验证「是否向声音系统请求播放」——
			// sound=vanilla 时 click() 必须调用 SoundManager.play，silent 时必须直接 return。
			case 62 -> act(mc, () -> soundProbe(mc, "silent"));
			case 63 -> act(mc, () -> soundProbe(mc, "vanilla"));
			// ★U13：主题覆盖收口★ —— 九个页在原版主题下逐个拍，验「页内卡片不再画深色」。
			// 全部走 clickEntry（真实 mouseClicked 命中侧边栏），一屏接一屏地换页。
			case 64 -> act(mc, () -> {
				mc.setScreen(null);
				ClientUiConfig.theme = "vanilla";
				ClientUiConfig.save();
				HubTheme.apply("vanilla");
				LOG.add("theme=vanilla for U13 sweep");
			});
			case 65 -> shotKeep(mc, "u6-38-vanilla-checkin.png",
					() -> open(mc, HubPage.CHECKIN, "rt checkin"));
			case 66 -> act(mc, () -> clickEntry(mc, "商店", 1));
			case 67 -> shotKeep(mc, "u6-39-vanilla-shop.png", () -> { });
			case 68 -> act(mc, () -> clickEntry(mc, "市场", 2));
			case 69 -> shotKeep(mc, "u6-40-vanilla-market.png", () -> { });
			case 70 -> act(mc, () -> clickEntry(mc, "仓储", 3));
			case 71 -> shotKeep(mc, "u6-41-vanilla-storage.png", () -> { });
			case 72 -> act(mc, () -> clickEntry(mc, "悬赏", 4));
			case 73 -> shotKeep(mc, "u6-42-vanilla-bounty.png", () -> { });
			case 74 -> act(mc, () -> clickEntry(mc, "战令", 6));
			case 75 -> shotKeep(mc, "u6-43-vanilla-pass.png", () -> { });
			case 76 -> act(mc, () -> clickEntry(mc, "协会", 7));
			case 77 -> shotKeep(mc, "u6-44-vanilla-guild.png", () -> { });
			case 78 -> act(mc, () -> clickEntry(mc, "邮件", 8));
			case 79 -> shotKeep(mc, "u6-45-vanilla-mail.png", () -> { });
			case 80 -> act(mc, () -> clickEntry(mc, "排行", 9));
			case 81 -> shotKeep(mc, "u6-46-vanilla-rank.png", () -> { });
			// ★U10 最后一条：非 OP 少 4 行★ —— 单机主机恒 OP4，所以先拍一张 OP 基线，
			// 再 /deop @s 把权限降下来，同一个界面下一帧就会少掉整组管理员行。
			case 82 -> act(mc, () -> {
				mc.setScreen(null);
				ClientUiConfig.theme = "dark";
				ClientUiConfig.save();
				HubTheme.apply("dark");
				HubSettingsPage.setAdminOpen(true);
				HubSettingsPage.scrollTo(0);
			});
			case 83 -> shotKeep(mc, "u6-47-settings-op.png", () -> {
				HubSettingsPage.setAdminOpen(true);
				open(mc, HubPage.SETTINGS, "rt settings");
			});
			case 84 -> act(mc, () -> {
				logPerm(mc, "before-deop");
				if (mc.player != null) {
					mc.player.networkHandler.sendChatCommand("deop " + mc.player.getGameProfile().getName());
				}
			});
			case 85 -> act(mc, () -> logPerm(mc, "after-deop"));
			case 86 -> shotRaw(mc, "u6-48-settings-nonop.png");
			// ★T44 副产物：小窗口下商店网格只能放 2×2★ —— 服务端一页 40 条，
			// 以前超出的行被 break 掉、翻页也翻不到。这两步验「滚到底能看到第 5 行之后的卡」。
			case 87 -> act(mc, () -> {
				mc.setScreen(null);
				open(mc, HubPage.SHOP, "rt shop");
			});
			case 88 -> act(mc, () -> {
				if (mc.currentScreen instanceof HubScreen hub) {
					LOG.add("shop scroll bottom -> row " + HubDev.scrollShopBottom(hub)
							+ " at tick " + tick);
				} else {
					LOG.add("shop scroll skipped: screen is "
							+ (mc.currentScreen == null ? "null"
							: mc.currentScreen.getClass().getSimpleName()));
				}
			});
			case 89 -> shotKeep(mc, "u6-52-shop-scrolled.png", () -> { });
			// ★U10 非 OP 分支（专用服务器）★ —— 单机主机恒 OP4、/deop 无效（上一轮实证），
			// 只能在真正的专用服务器上用非 OP 客户端验。这条线只跑三件事：
			// ① 进服后权限等级与设置页行数　② 顺手点「管理工具」必须打不中（行不存在）
			// ③ 截图留证。服务端侧由 RCON 事后 /op 再复测一次做 A/B 对照。
			case 200 -> act(mc, () -> {
				mc.setScreen(null);
				HubSettingsPage.setAdminOpen(true);
				HubSettingsPage.scrollTo(0);
				ClientUiConfig.theme = "dark";
				ClientUiConfig.save();
				HubTheme.apply("dark");
			});
			case 201 -> shotKeep(mc, settingsShot(mc, "u6-49-settings-mp"),
					() -> open(mc, HubPage.SETTINGS, "rt settings"));
			case 202 -> act(mc, () -> {
				// 427x240 逻辑高度下「管理员」整组在折叠线以下 ⇒ 必须滚到底，
				// 否则 OP / 非 OP 两张图在默认位置长得一模一样，等于没证据。
				if (mc.currentScreen instanceof HubScreen hub) {
					int max = HubDev.settingsScrollMax(hub);
					HubDev.scrollSettings(hub, max);
					LOG.add("settings scroll to " + max + " (contentH="
							+ HubDev.settingsContentHeight(hub) + ")");
				}
				logPerm(mc, "mp");
			});
			case 203 -> shotKeep(mc, settingsShot(mc, "u6-51-settings-mp-bottom"), () -> { });
			// 拍完再点：点中就说明行真的存在于布局里（OP true / 非 OP false）。
			// 放在拍照之后，避免展开态被点成折叠、把上一张的布局改掉。
			case 204 -> act(mc, () -> {
				boolean before = HubSettingsPage.devAdminOpen();
				boolean hit = mc.currentScreen instanceof HubScreen hub
						? HubDev.clickSettingsRow(hub, HubSettingsPage.Row.ADMIN_HEAD) : false;
				LOG.add("settings row ADMIN_HEAD[mp-probe] hit=" + hit
						+ " adminOpen " + before + " -> " + HubSettingsPage.devAdminOpen());
				HubSettingsPage.setAdminOpen(true);
			});
			case 90 -> act(mc, () -> mc.player.networkHandler.sendChatCommand("redtale grant money @s 400"));
			case 91 -> act(mc, () -> {
				mc.player.networkHandler.sendChatCommand("board post minecraft:oak_planks 4 20");
				// ★命令与拍照不能同 tick★：/board 的回包要一个网络往返，
				// 写在 shot 的 action 里会拍到上一帧（16:34 那轮拍到的是商店页）。
				mc.player.networkHandler.sendChatCommand("board");
			});
			case 92 -> shotKeep(mc, "u6-53-board.png", () -> LOG.add("board probe " + HubBoardPage.devProbe()));
			case 93 -> act(mc, () -> mc.setScreen(null));
			// T16：流水页。先 /rt ledger 拉一页（服务端按玩家/方向/类型筛），再切「市场」桶看筛选真的生效
			case 94 -> act(mc, () -> mc.player.networkHandler.sendChatCommand("rt ledger"));
			case 95 -> shotKeep(mc, "u6-54-ledger.png",
					() -> LOG.add("ledger probe " + HubLedgerPage.devProbe()));
			case 96 -> act(mc, () -> com.akira.redtale.economy.client.ui.hub.LedgerClientNetworking
					.request(2, 0, 2, false));
			case 97 -> shotKeep(mc, "u6-55-ledger-market.png",
					() -> LOG.add("ledger probe " + HubLedgerPage.devProbe()));
			case 98 -> act(mc, () -> mc.setScreen(null));
			// T11：表格编辑器批量行。「开界面」与「拍照」必须分成两步 ——
			// /shop admin edit 是服务端下发 open，同 tick 拍照拿到的是上一屏。
			case 99 -> act(mc, () -> mc.player.networkHandler.sendChatCommand("rtedit testshop"));
			case 100 -> shotKeep(mc, "u6-56-table-batch.png", () -> LOG.add("table probe "
					+ (mc.currentScreen instanceof com.akira.redtale.economy.client.admin.TableEditorScreen t
							? t.devProbe() : "not-editor:" + mc.currentScreen)));
			case 101 -> act(mc, () -> LOG.add("table click "
					+ (mc.currentScreen instanceof com.akira.redtale.economy.client.admin.TableEditorScreen t
							? t.devClickBatchAdd("devprobe:__none__") : "not-editor:" + mc.currentScreen)));
			case 102 -> act(mc, () -> LOG.add("table click "
					+ (mc.currentScreen instanceof com.akira.redtale.economy.client.admin.TableEditorScreen t
							? t.devClickBatchRemove() : "not-editor:" + mc.currentScreen)));
			case 103 -> shotKeep(mc, "u6-57-table-toast.png", () -> LOG.add("table probe "
					+ (mc.currentScreen instanceof com.akira.redtale.economy.client.admin.TableEditorScreen t
							? t.devProbe() : "not-editor:" + mc.currentScreen)));
			case 104 -> act(mc, () -> mc.setScreen(null));
			// T28：管理员控制台。open 会自己 pullData（发请求），但回包要一个往返 ⇒ 分成两步
			case 105 -> act(mc, () -> com.akira.redtale.economy.client.ui.hub.HubScreen.open(
					com.akira.redtale.economy.client.ui.hub.HubPage.ADMIN));
			case 106 -> shotKeep(mc, "u6-58-admin.png", () -> LOG.add("admin probe "
					+ com.akira.redtale.economy.client.ui.hub.HubAdminPage.devProbe()));
			// 刷新 chip：点一次应重新请求（探针里的 recv 时间戳要前进）
			case 107 -> act(mc, () -> LOG.add("admin refresh "
					+ com.akira.redtale.economy.client.ui.hub.HubAdminPage.devClickRefresh()));
			case 108 -> shotKeep(mc, "u6-59-admin-refresh.png", () -> LOG.add("admin probe "
					+ com.akira.redtale.economy.client.ui.hub.HubAdminPage.devProbe()));
			case 109 -> act(mc, () -> mc.setScreen(null));
			// ★T51 模块总开关★：设置页管理员区的「模块开关」子区 + 关掉 shop 后侧栏消失。
			// ★滚动不能塞进 shotKeep 的 action★：open() 是发 /rt settings，屏幕要等一个往返才出现，
			// 同 tick 判 instanceof 恒 false（首轮就是这么丢掉 scrollToModule，导致点行 hit=false）。
			case 110 -> act(mc, () -> {
				mc.setScreen(null);
				com.akira.redtale.economy.client.ui.hub.HubSettingsPage.setAdminOpen(true);
				com.akira.redtale.economy.client.ui.hub.HubSettingsPage.setModulesOpen(true);
				LOG.add("modules states(before)=" + com.akira.redtale.economy.client.ui.hub
						.ModulesClientNetworking.devStates());
			});
			case 111 -> shotKeep(mc, "u6-60-modules.png", () -> open(mc, HubPage.SETTINGS, "rt settings"));
			case 112 -> act(mc, () -> {
				if (mc.currentScreen instanceof HubScreen hub) {
					com.akira.redtale.economy.client.ui.hub.HubSettingsPage.scrollToModule("shop",
							HubDev.settingsContentHeight(hub));
					LOG.add("modules scroll=" + com.akira.redtale.economy.client.ui.hub
							.HubSettingsPage.devScroll());
				} else {
					LOG.add("modules scroll skipped: screen="
							+ (mc.currentScreen == null ? "null" : mc.currentScreen.getClass().getSimpleName()));
				}
			});
			case 113 -> shotKeep(mc, "u6-61-modules-rows.png", () -> { });
			case 114 -> act(mc, () -> {
				if (mc.currentScreen instanceof HubScreen hub) {
					LOG.add("modules click shop hit=" + HubDev.clickModuleRow(hub, "shop"));
				} else {
					LOG.add("modules click shop skipped: screen="
							+ (mc.currentScreen == null ? "null" : mc.currentScreen.getClass().getSimpleName()));
				}
			});
			case 115 -> shotKeep(mc, "u6-62-modules-shop-off.png", () -> LOG.add("modules states(after)="
					+ com.akira.redtale.economy.client.ui.hub.ModulesClientNetworking.devStates()));
			case 116 -> act(mc, () -> mc.setScreen(null));
			case 117 -> shotKeep(mc, "u6-63-sidebar-no-shop.png", () -> open(mc, HubPage.CHECKIN, "rt checkin"));
			// 还原：截图不能把 dev 环境留在「商店关闭」状态（后续轮次会被它误伤）
			case 118 -> act(mc, () -> {
				com.akira.redtale.economy.client.ui.hub.ModulesClientNetworking.set("shop", true);
				LOG.add("modules restore shop=on");
			});
			// ---- T23 称号颜色/渐变 + 前后缀双槽位 ----
			// 119 先给本机种一份「前缀 + 后缀 + 渐变」数据（单机主机是 OP4，直接走命令）。
			case 119 -> act(mc, () -> {
				mc.setScreen(null);
				if (mc.player == null) {
					LOG.add("titles seed skipped: no player");
					return;
				}
				String self = mc.player.getGameProfile().getName();
				mc.player.networkHandler.sendChatCommand("titles set " + self + " 木匠");
				mc.player.networkHandler.sendChatCommand("titles suffix " + self + " 赤物语");
				mc.player.networkHandler.sendChatCommand("titles style " + self + " gradient #FFD700:#FF3B3B");
				LOG.add("titles seed for " + self);
			});
			case 120 -> act(mc, () -> open(mc, HubPage.CHECKIN, "rt checkin"));
			case 121 -> act(mc, () -> clickEntry(mc, "称号", 11));
			case 122 -> shotKeep(mc, "u6-64-titles-style.png",
					() -> LOG.add("titles snapshot=" + com.akira.redtale.economy.client.title
							.TitlesClientNetworking.LAST.prefix() + "|"
							+ com.akira.redtale.economy.client.title.TitlesClientNetworking.LAST.suffix()
							+ "|" + com.akira.redtale.economy.client.title.TitlesClientNetworking.LAST.gradient()));
			// ★T69b 商店双模式★：同一批商品在「图标网格」与「卡片列表」下各拍一张，
			// 并各做一次真实点击（几何不同 ⇒ 命中必须各自成立）。
			// 先复位成网格再打开界面：上一步可能把 shopView 留在列表。
			case 218 -> act(mc, () -> {
				ClientUiConfig.shopView = 0;
				ClientUiConfig.save();
				mc.setScreen(null);
				open(mc, HubPage.SHOP, "rt shop");
			});
			case 219 -> shotKeep(mc, "u6-70-shop-grid.png",
					() -> LOG.add("shop grid view=" + (mc.currentScreen instanceof HubScreen h
							? HubDev.shopViewVal(h) : -1)
							+ " clickItem0=" + (mc.currentScreen instanceof HubScreen h
							? HubDev.clickShopItem(h, 0) : false)));
			case 220 -> act(mc, () -> {
				if (mc.currentScreen instanceof HubScreen hub) {
					LOG.add("shop view chip hit=" + HubDev.clickShopViewChip(hub)
							+ " -> view=" + HubDev.shopViewVal(hub));
				} else {
					LOG.add("shop view chip skipped: screen="
							+ (mc.currentScreen == null ? "null" : mc.currentScreen.getClass().getSimpleName()));
				}
			});
			case 221 -> shotKeep(mc, "u6-71-shop-list.png",
					() -> LOG.add("shop list clickItem1=" + (mc.currentScreen instanceof HubScreen h
							? HubDev.clickShopItem(h, 1) : false)));
			case 222 -> act(mc, () -> {
				if (mc.currentScreen instanceof HubScreen hub) {
					int bottom = HubDev.scrollShopBottom(hub);
					int last = ShopClientState.page() == null ? -1
							: ShopClientState.page().entries().size() - 1;
					LOG.add("shop list scroll bottom -> row " + bottom + " last=" + last
							+ " clickLast=" + (last >= 0 ? HubDev.clickShopItem(hub, last) : false));
				}
			});
			case 223 -> shotKeep(mc, "u6-72-shop-list-bottom.png", () -> { });
			// 收尾：把偏好复位成网格，别把夹具的状态留给玩家
			case 224 -> act(mc, () -> {
				ClientUiConfig.shopView = 0;
				ClientUiConfig.save();
				LOG.add("shop view restored to grid");
			});
			// ★T68 阶段2 抽样★：阶段 1 新增/重做的页面各拍一张（任务/战令/协会/邮件/仓储/签到/称号/市场）。
			// 每步先 setScreen(null) 再发命令 —— shotKeep 看到「已经是 HubScreen」会以为已打开，
			// 拍的还是上一页（T12 踩过一次）。
			case 225 -> shotKeep(mc, "u6-73-hub-tasks.png",
					() -> openFresh(mc, HubPage.TASKS, "rt tasks"));
			case 226 -> act(mc, () -> LOG.add("tasks page rows="
					+ com.akira.redtale.economy.client.ui.hub.TasksHubClientNetworking.LAST.tasks().size()
					+ " refreshes="
					+ com.akira.redtale.economy.client.ui.hub.TasksHubClientNetworking.LAST.refreshesLeft()));
			case 227 -> shotKeep(mc, "u6-74-hub-pass.png",
					() -> openFresh(mc, HubPage.PASS, "rt pass"));
			case 228 -> act(mc, () -> LOG.add("pass page season="
					+ com.akira.redtale.economy.client.pass.PassClientNetworking.LAST.season()
					+ " lv=" + com.akira.redtale.economy.client.pass.PassClientNetworking.LAST.level()
					+ " xp=" + com.akira.redtale.economy.client.pass.PassClientNetworking.LAST.xp()
					+ "/" + com.akira.redtale.economy.client.pass.PassClientNetworking.LAST.xpNext()
					+ " free=" + com.akira.redtale.economy.client.pass.PassClientNetworking.LAST.free().size()));
			case 229 -> shotKeep(mc, "u6-75-hub-guild.png",
					() -> openFresh(mc, HubPage.GUILD, "rt guild"));
			case 230 -> shotKeep(mc, "u6-76-hub-mail.png",
					() -> openFresh(mc, HubPage.MAIL, "rt mail"));
			case 231 -> act(mc, () -> {
				var snap = com.akira.redtale.economy.client.ui.hub.MailClientNetworking.LAST;
				LOG.add("mail page entries=" + snap.entries().size()
						+ " pendingMoney=" + snap.pendingMoney() + " items=" + snap.itemCount()
						+ " full=" + snap.mailboxFull());
			});
			case 232 -> shotKeep(mc, "u6-77-hub-storage.png",
					() -> openFresh(mc, HubPage.STORAGE, "rt storage"));
			case 233 -> shotKeep(mc, "u6-78-hub-checkin.png",
					() -> openFresh(mc, HubPage.CHECKIN, "rt checkin"));
			case 234 -> act(mc, () -> clickPage(mc, HubPage.TITLES));
			case 235 -> shotKeep(mc, "u6-79-hub-titles.png", () -> clickPage(mc, HubPage.TITLES));
			case 236 -> shotKeep(mc, "u6-80-hub-market.png",
					() -> openFresh(mc, HubPage.MARKET, "rt market"));
			// T70/T67 证据：硬币走贴图还是几何圆片，日志里给 true/false，别靠肉眼量 1 像素。
			case 237 -> act(mc, () -> LOG.add("money " + HubMoney.texProbe()));
			// ★T61 回归★：战令滚动条点到最右端。上一版滚动条钉在内容区最底、正好压在付费轨上，
			// 427×240 下点它命中不到（也确实看不见）。这一对图用来证明「看得见 + 点得中」。
			// 238 先把页切回来（230/231 的邮件页把它切走了；同 tick 内换页还没到位，不能就地点击）。
			// ★HubScreen.page 是包内字段，本包（client/dev）读不到★ ⇒ 用 HubDev.pageId 字符串判。
			case 238 -> act(mc, () -> {
				boolean onPass = mc.currentScreen instanceof HubScreen hs
						&& HubPage.PASS.id.equals(HubDev.pageId(hs));
				if (!onPass) openFresh(mc, HubPage.PASS, "rt pass");
			});
			case 239 -> act(mc, () -> {
				if (mc.currentScreen instanceof HubScreen hs) {
					boolean hit = HubDev.clickPassBarEnd(hs);
					LOG.add("pass bar hit=" + hit + " scroll=" + HubDev.passScrollVal(hs));
				}
			});
			case 240 -> shotKeep(mc, "u6-81-hub-pass-bottom.png", () -> { });
			// 末尾必须是纯 act 步：最后一步若是 shotKeep 的全屏态，客户端会卡在 toggle windowed 前（见 _LESSONS）
			case 241 -> act(mc, () -> LOG.add("pass tail scroll="
					+ (mc.currentScreen instanceof HubScreen hs ? HubDev.passScrollVal(hs) : -1)));
			// ★稀疏编号★：这个 step 号没有动作时直接跳下一个，不要 finish。
			// 以前是 `default -> finish`，于是「在末尾追加 218+」会被 123 这个空号直接截断
			// （整轮只跑到 122 就退出，新步骤永远不执行、也不报错）。
			// 真正的结束条件仍在 tick() 里：step > LAST_STEP。
			default -> {
				step += 1;
				stepAt = tick + FAST_SETTLE;
			}
		}
	}

	/**
	 * T44：把窗口钉死成 854×480 + guiScale=2（逻辑 427×240）。
	 *
	 * <p>不钉的话，每次跑出来的「错位」都可能是窗口大小的差异而不是布局本身的缺陷 ——
	 * 截图作为回归证据就没法比。全屏版的逻辑尺寸由同一份 guiScale 推出来，
	 * 所以两版之间唯一的变量就是「逻辑像素多少」。
	 */
	/**
	 * T52：致命界面取证并立刻退出。
	 *
	 * <p>为什么必须「抓文本」：{@code NoticeScreen}/{@code CrashScreen} 的标题在截图里
	 * 往往只有一句「游戏崩溃了」，真正原因（缺哪个依赖、哪个 mixin 注入失败）只在
	 * {@code logs/latest.log} 里。夹具自己把日志尾巴抄进 {@code u6-shots.log} 与
	 * {@code u6-fatal.txt}，下一轮就不用再跑一遍复现。
	 */
	private static boolean checkFatal(MinecraftClient mc) {
		if (fatalSeen) return true;
		Screen cur = mc.currentScreen;
		if (cur == null) return false;
		List<String> chain = new ArrayList<>();
		for (Class<?> c = cur.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
			chain.add(c.getName());
		}
		if (!FatalScreens.matchesChain(chain)) return false;

		fatalSeen = true;
		active = false;
		Path dir = FabricLoader.getInstance().getGameDir();
		String cls = cur.getClass().getName();
		String title = "";
		try {
			title = cur.getTitle().getString();
		} catch (Throwable ignored) {
			// 个别界面构造未完成时 getTitle 会 NPE —— 取证不能因此中断
		}
		List<String> detail = new ArrayList<>();
		detail.add("FATAL screen=" + cls);
		detail.add("FATAL title=\"" + title + "\"");
		detail.add("FATAL at tick=" + tick + " step=" + step + " inWorld=" + inWorld);
		detail.add("FATAL chain=" + chain);
		detail.add("FATAL hint=" + FatalScreens.hint(cls));
		LOG.addAll(detail);
		LOG.addAll(tailOfLatestLog(dir, 30));
		LOG.addAll(pairReport(dir.resolve("screenshots")));
		flushLog(dir);
		writeFatalFile(dir, detail);
		mc.scheduleStop();
		return true;
	}

	/** 抄 {@code logs/latest.log} 末尾若干行（致命原因几乎总在这里，界面标题没信息量）。 */
	private static List<String> tailOfLatestLog(Path dir, int n) {
		List<String> out = new ArrayList<>();
		Path log = dir.resolve("logs").resolve("latest.log");
		if (!Files.isRegularFile(log)) {
			out.add("log| (logs/latest.log 不存在)");
			return out;
		}
		try {
			List<String> all = Files.readAllLines(log, StandardCharsets.UTF_8);
			int from = Math.max(0, all.size() - n);
			out.add("log| ---- latest.log tail (" + (all.size() - from) + "/" + all.size() + ") ----");
			for (int i = from; i < all.size(); i++) {
				out.add("log| " + all.get(i));
			}
		} catch (IOException e) {
			out.add("log| read failed: " + e.getMessage());
		}
		return out;
	}

	/** 落一份独立取证文件（存在即代表本轮被致命界面中止，调用方据此决定是否重跑）。 */
	private static void writeFatalFile(Path dir, List<String> detail) {
		StringBuilder sb = new StringBuilder("# UiShots 致命界面取证\n");
		for (String s : detail) sb.append(s).append('\n');
		try {
			Files.write(dir.resolve(FATAL_FILE), sb.toString().getBytes(StandardCharsets.UTF_8));
		} catch (IOException ignored) {
			// 落盘失败不影响 u6-shots.log 里已经有的证据
		}
	}

	private static void setupResolution(MinecraftClient mc) {
		resReady = true;
		var win = mc.getWindow();
		if (win.isFullscreen()) {
			// 上一轮可能停在全屏，先回窗口，否则 setWindowedSize 不生效
			win.toggleFullscreen();
		}
		mc.options.getGuiScale().setValue(guiScale);
		win.setWindowedSize(winW, winH);
		mc.onResolutionChanged();
		LOG.add("resolution pinned to " + winW + "x" + winH + " guiScale=" + guiScale
				+ " (fb=" + win.getFramebufferWidth() + "x" + win.getFramebufferHeight()
				+ ", logical=" + win.getScaledWidth() + "x" + win.getScaledHeight() + ")");
	}

	/**
	 * 一张图存两版：窗口态 {@code -win}，全屏态 {@code -fs}。
	 *
	 * <p>顺序固定「先窗口、再全屏、拍完回窗口」，所以同一个 step 内两次拍照看到的是
	 * <b>同一份界面状态</b>（页面、滚动位置、展开项都没变，resize 只改投影）。
	 * 想反过来「两组独立跑两遍」不行：任务/商店/冷却都是有状态的，第二遍数据就不一样了。
	 *
	 * @param clearAfter 拍完全屏版后是否清屏（{@link #shot} 为 true，其余为 false）
	 */
	private static void captureBoth(MinecraftClient mc, String name, boolean clearAfter) {
		var win = mc.getWindow();
		boolean fs = win.isFullscreen();
		if (!fs) {
			if (fsToggleAt >= 0 && tick - fsToggleAt < RES_SETTLE) {
				return; // 正在等全屏生效
			}
			if (fsToggleAt >= 0) {
				// 切了 40 tick 还没进全屏 ⇒ 环境不支持（无显示器/驱动），记缺口后继续，不能死循环
				LOG.add("fullscreen unavailable for " + name + " at tick " + tick + ", -fs skipped");
				fsToggleAt = -1;
				if (clearAfter) mc.setScreen(null);
				next(step + 1);
				return;
			}
			save(mc, name, false);
			win.toggleFullscreen();
			fsToggleAt = tick;
			fsScaleApplied = false;
			stepAt = tick + RES_SETTLE;
			LOG.add("toggle fullscreen at tick " + tick);
			return;
		}
		// ★全屏后必须重设 guiScale★：窗口态 854x480 会被 MC 钳到 scale≤2（最小 320x240），
		// 所以 flag 里写 scale=3 在窗口态拿不到，只有进了 2560x1440 全屏才允许。设完再等一轮 settle。
		if (!fsScaleApplied) {
			fsScaleApplied = true;
			int before = mc.options.getGuiScale().getValue();
			mc.options.getGuiScale().setValue(guiScale);
			mc.onResolutionChanged();
			stepAt = tick + RES_SETTLE;
			LOG.add("fullscreen scale " + before + " -> " + mc.options.getGuiScale().getValue()
					+ " (requested " + guiScale + ") at tick " + tick);
			return;
		}
		save(mc, name, true);
		win.toggleFullscreen();
		fsToggleAt = -1;
		if (clearAfter) mc.setScreen(null);
		next(step + 1);
		// 回窗口也要 settle：下一屏是按窗口尺寸重新算的布局
		stepAt = tick + RES_SETTLE;
		LOG.add("toggle windowed at tick " + tick);
	}

	/** 落盘 + 记一行「这张图是什么分辨率下拍的」。 */
	private static void save(MinecraftClient mc, String name, boolean fullscreen) {
		var win = mc.getWindow();
		String out = name.endsWith(".png")
				? name.substring(0, name.length() - 4) + (fullscreen ? "-fs.png" : "-win.png")
				: name + (fullscreen ? "-fs" : "-win");
		ScreenshotRecorder.saveScreenshot(mc.runDirectory, out, mc.getFramebuffer(),
				text -> LOG.add("saved " + out + " : " + text.getString()));
		if (fullscreen) {
			fsShots++;
		} else {
			winShots++;
		}
		Screen cur = mc.currentScreen;
		String pid = cur instanceof HubScreen h ? " page=" + HubDev.pageId(h) : "";
		LOG.add("shot " + out + " at tick " + tick
				+ " fb=" + win.getFramebufferWidth() + "x" + win.getFramebufferHeight()
				+ " logical=" + win.getScaledWidth() + "x" + win.getScaledHeight()
				+ " scale=" + win.getScaleFactor()
				+ " screen=" + (cur == null ? "null" : cur.getClass().getSimpleName()) + pid);
		flushLog(mc.runDirectory.toPath());
	}

	/** 先开界面 → 等 SETTLE tick 后拍。step 里做的是「打开」，下一 step 才是「拍照」。 */
	private static void open(MinecraftClient mc, HubPage page, String command) {
		if (inWorld) {
			mc.player.networkHandler.sendChatCommand(command);
		} else {
			HubScreen.open(page);
		}
	}

	/**
	 * T68：先清屏再发命令。
	 *
	 * <p>★不能直接 {@link #open}★：{@code shotKeep} 判「是 HubScreen 就拍」，换页时屏幕还是上一页，
	 * 于是画的是旧页面、日志还说成功了（T12 的 board 截图拍成商店页就是这么来的）。
	 */
	private static void openFresh(MinecraftClient mc, HubPage page, String command) {
		mc.setScreen(null);
		open(mc, page, command);
	}

	private static void shot(MinecraftClient mc, String name, Runnable action) {
		if (stateOnly) {
			stateOnlyStep(mc, name, action);
			return;
		}
		if (whitelist != null && !actionRanThisStep) {
			actionRanThisStep = true;
			action.run();
			stepAt = tick + SETTLE;
			LOG.add("whitelist step " + step + " ran its own action at tick " + tick);
			return;
		}
		Screen cur = mc.currentScreen;
		if (cur == null || !isOurs(cur)) {
			// 还没开：这一 step 用来「打开」，再等 SETTLE
			action.run();
			stepAt = tick + SETTLE;
			LOG.add("open for " + name + " at tick " + tick + " (world=" + inWorld + ")");
			// ★界面迟迟不开（模块被关/权限不足/命令失败）不能死等★：超时跳过并继续下一步，
			// 否则整个夹具卡在这一步、客户端永远不退出（2026-10-21 实测卡了 4 分钟）。
			if (++attempts > MAX_ATTEMPTS) {
				LOG.add("timeout " + name + " after " + attempts + " tries, skip");
				attempts = 0;
				mc.setScreen(null);
				next(step + 1);
			}
			return;
		}
		// 界面已经在了：拍（窗口 + 全屏各一张，见 captureBoth）
		attempts = 0;
		captureBoth(mc, name, true);
	}

	/**
	 * 和 {@link #shot} 一样「先开再拍」，但**拍完不清屏** ——
	 * 连续几步要在同一屏上点侧边栏（U4）时用，否则每步都被迫重新打开、页面状态丢失。
	 */
	private static void shotKeep(MinecraftClient mc, String name, Runnable action) {
		if (stateOnly) {
			stateOnlyStep(mc, name, action);
			return;
		}
		if (whitelist != null && !actionRanThisStep) {
			actionRanThisStep = true;
			action.run();
			stepAt = tick + SETTLE;
			LOG.add("whitelist step " + step + " ran its own action at tick " + tick);
			return;
		}
		Screen cur = mc.currentScreen;
		if (cur == null || !isOurs(cur)) {
			action.run();
			stepAt = tick + SETTLE;
			LOG.add("open for " + name + " at tick " + tick + " (world=" + inWorld + ")");
			if (++attempts > MAX_ATTEMPTS) {
				LOG.add("timeout " + name + " after " + attempts + " tries, skip");
				attempts = 0;
				mc.setScreen(null);
				next(step + 1);
			}
			return;
		}
		attempts = 0;
		captureBoth(mc, name, false);
	}

	/** 点侧边栏第 index 项（走 HubScreen 真实 mouseClicked 命中路径），并记录结果。 */
	private static void clickEntry(MinecraftClient mc, String label, int index) {
		if (!(mc.currentScreen instanceof HubScreen hub)) {
			LOG.add("click " + label + " skipped: screen is "
					+ (mc.currentScreen == null ? "null" : mc.currentScreen.getClass().getSimpleName()));
			return;
		}
		boolean hit = HubDev.clickSidebar(hub, index);
		LOG.add("click " + label + " (row " + index + ") hit=" + hit + " page=" + HubDev.pageId(hub)
				+ " at tick " + tick);
	}

	/** T68：按页面点侧栏（下标会随模块开关变，不能写死）。 */
	private static void clickPage(MinecraftClient mc, HubPage page) {
		if (!(mc.currentScreen instanceof HubScreen hub)) {
			LOG.add("page click " + page + " skipped: screen is "
					+ (mc.currentScreen == null ? "null" : mc.currentScreen.getClass().getSimpleName()));
			return;
		}
		boolean hit = HubDev.clickSidebar(hub, page);
		LOG.add("page click " + page + " hit=" + hit + " page=" + HubDev.pageId(hub) + " at tick " + tick);
	}

	/** 点商店目录树第 row 行（走 HubScreen 真实 mouseClicked 命中路径），并记录路径变化。 */
	private static void clickTree(MinecraftClient mc, int row) {
		if (!(mc.currentScreen instanceof HubScreen hub)) {
			LOG.add("tree click " + row + " skipped: screen is "
					+ (mc.currentScreen == null ? "null" : mc.currentScreen.getClass().getSimpleName()));
			return;
		}
		String before = ShopClientState.filter().categoryOrEmpty();
		boolean hit = HubDev.clickTreeRow(hub, row);
		LOG.add("tree click row=" + row + " hit=" + hit + " path=" + before + " -> "
				+ ShopClientState.filter().categoryOrEmpty() + " at tick " + tick);
	}

	/** 用真实 charTyped 通道写搜索词（不聚焦就写不进去），再让服务端回一页。 */
	private static void search(MinecraftClient mc, String text) {
		if (!(mc.currentScreen instanceof HubScreen hub)) {
			LOG.add("search skipped: screen is "
					+ (mc.currentScreen == null ? "null" : mc.currentScreen.getClass().getSimpleName()));
			return;
		}
		HubDev.search(hub, text);
		LOG.add("search \"" + text + "\" at tick " + tick + " path="
				+ ShopClientState.filter().categoryOrEmpty());
	}

	/** 点排行页「只看活跃」复选框（走 HubScreen 真实 mouseClicked 命中路径）。 */
	private static void clickRankToggle(MinecraftClient mc) {
		if (!(mc.currentScreen instanceof HubScreen hub)) {
			LOG.add("rank toggle skipped: screen is "
					+ (mc.currentScreen == null ? "null" : mc.currentScreen.getClass().getSimpleName()));
			return;
		}
		boolean before = HubDev.rankActiveOnlyVal(hub);
		boolean hit = HubDev.clickRankToggle(hub);
		LOG.add("rank toggle hit=" + hit + " state " + before + " -> " + HubDev.rankActiveOnlyVal(hub)
				+ " at tick " + tick);
	}

	/** 点排行页顶部的榜单下拉（验「其余榜是二级」的下拉真的能开）。 */
	private static void clickRankDropdown(MinecraftClient mc) {
		if (!(mc.currentScreen instanceof HubScreen hub)) {
			LOG.add("rank dropdown skipped: screen is "
					+ (mc.currentScreen == null ? "null" : mc.currentScreen.getClass().getSimpleName()));
			return;
		}
		boolean hit = HubDev.clickRankDropdown(hub);
		LOG.add("rank dropdown hit=" + hit + " at tick " + tick);
	}

	/** 从下拉里选第 idx 个榜（走真实 mouseClicked 命中路径）。 */
	private static void clickRankBoard(MinecraftClient mc, int idx) {
		if (!(mc.currentScreen instanceof HubScreen hub)) {
			LOG.add("rank board skipped: screen is "
					+ (mc.currentScreen == null ? "null" : mc.currentScreen.getClass().getSimpleName()));
			return;
		}
		boolean hit = HubDev.clickRankBoard(hub, idx);
		LOG.add("rank board[" + idx + "] hit=" + hit + " selected=" + HubDev.rankBoardVal(hub)
				+ " at tick " + tick);
	}

	/** 点排行页「同步」按钮，并记下点完后的冷却剩余秒数（验 60 秒冷却真的生效）。 */
	private static void clickRankSync(MinecraftClient mc, String tag) {
		if (!(mc.currentScreen instanceof HubScreen hub)) {
			LOG.add("rank sync skipped: screen is "
					+ (mc.currentScreen == null ? "null" : mc.currentScreen.getClass().getSimpleName()));
			return;
		}
		int before = HubDev.rankSyncCooldownLeftVal(hub);
		boolean hit = HubDev.clickRankSync(hub);
		LOG.add("rank sync[" + tag + "] hit=" + hit + " cooldownBefore=" + before
				+ " cooldownAfter=" + HubDev.rankSyncCooldownLeftVal(hub) + " at tick " + tick);
	}

	/** 把客户端缓存的榜快照写进日志：行数 / total / 自己那行 / 快照自带的 activeOnly。 */
	private static void logRank(MinecraftClient mc, String tag) {
		var snap = com.akira.redtale.economy.client.rank.RankClientNetworking.LAST;
		if (snap == null) {
			LOG.add("rank[" + tag + "] no snapshot");
			return;
		}
		var self = snap.self();
		LOG.add("rank[" + tag + "] rows=" + snap.top().size() + " total=" + snap.total()
				+ " snapActiveOnly=" + snap.activeOnly()
				+ " self=" + (self == null ? "null" : self.rank() + "/" + self.name() + "/" + self.balance())
				+ " uiActiveOnly=" + (mc.currentScreen instanceof HubScreen h ? HubDev.rankActiveOnlyVal(h) : null)
				+ " at tick " + tick);
		if (!snap.top().isEmpty()) {
			StringBuilder sb = new StringBuilder("rank[" + tag + "] top=");
			for (var r : snap.top()) sb.append(r.rank()).append(':').append(r.name()).append(' ');
			LOG.add(sb.toString().trim());
		}
	}

	/** 点设置页某一行（走 HubScreen 真实 mouseClicked 命中路径），并记录点击前后状态与落到哪个界面。 */
	private static void clickSettingsRow(MinecraftClient mc, HubSettingsPage.Row row, String tag) {
		if (!(mc.currentScreen instanceof HubScreen hub)) {
			LOG.add("settings row " + row + "[" + tag + "] skipped: screen is "
					+ (mc.currentScreen == null ? "null" : mc.currentScreen.getClass().getSimpleName()));
			return;
		}
		boolean before = HubSettingsPage.devAdminOpen();
		boolean hit = HubDev.clickSettingsRow(hub, row);
		LOG.add("settings row " + row + "[" + tag + "] hit=" + hit
				+ " adminOpen " + before + " -> " + HubSettingsPage.devAdminOpen()
				+ " screen=" + (mc.currentScreen == null ? "null"
				: mc.currentScreen.getClass().getSimpleName())
				+ " at tick " + tick);
	}

	/**
	 * 拍「不是我们的界面」（原版键位/语言界面）：不判 isOurs，有什么拍什么。
	 *
	 * <p>不清屏 —— 下一步自己负责把界面关掉。
	 */
	private static void shotRaw(MinecraftClient mc, String name) {
		if (stateOnly) {
			stateOnlyStep(mc, name, null);
			return;
		}
		Screen cur = mc.currentScreen;
		if (cur == null) {
			LOG.add("raw shot " + name + " skipped: screen is null at tick " + tick);
			next(step + 1);
			return;
		}
		LOG.add("raw shot " + name + " start at tick " + tick + " screen=" + cur.getClass().getSimpleName());
		captureBoth(mc, name, false);
	}

	/**
	 * 探针：设成 {@code mode} 后真的点一次（走 {@link ClientUiConfig#click()}），
	 * 把「有没有向 SoundManager 请求播放」写进日志。
	 */
	private static void soundProbe(MinecraftClient mc, String mode) {
		ClientUiConfig.sound = mode;
		ClientUiConfig.save();
		ClientUiConfig.click();
		LOG.add("sound[" + mode + "] playRequested=" + ClientUiConfig.devLastClickRequestedSound()
				+ " label=" + ClientUiConfig.soundLabel() + " at tick " + tick);
	}

	/** 记下当前权限等级与设置页可见行数（U10：非 OP 应比 OP 少 4 行）。 */
	private static void logPerm(MinecraftClient mc, String tag) {
		if (mc.player == null) {
			LOG.add("perm[" + tag + "] no player");
			return;
		}
		LOG.add("perm[" + tag + "] name=" + mc.player.getGameProfile().getName()
				+ " level4=" + mc.player.hasPermissionLevel(4)
				+ " level2=" + mc.player.hasPermissionLevel(2)
				+ " adminVisible=" + HubSettingsPage.devAdminVisible()
				+ " rows=" + HubSettingsPage.devRowCount()
				+ " adminOpen=" + HubSettingsPage.devAdminOpen()
				+ " screen=" + (mc.currentScreen == null ? "null"
				: mc.currentScreen.getClass().getSimpleName())
				+ " at tick " + tick);
	}

	/** 设置页截图名带上权限分支（同一次实验要拍 OP / 非 OP 两张，不能互相覆盖）。 */
	private static String settingsShot(MinecraftClient mc, String base) {
		boolean admin = mc.player != null && mc.player.hasPermissionLevel(4);
		return base.replace("mp", admin ? "op-mp" : "nonop-mp") + ".png";
	}

	/** 仅执行、不拍照（改配置/切主题/发命令），等 SETTLE 后进下一步。 */
	private static void act(MinecraftClient mc, Runnable action) {
		action.run();
		if (stateOnly) {
			// 白名单外：动作照跑（它是后面截图步骤的前置状态），但不等渲染
			nextFast("act");
			return;
		}
		LOG.add("act step " + step + " at tick " + tick);
		next(step + 1);
	}

	/**
	 * T48：白名单外的拍摄步骤 —— 只跑「打开界面/改状态」那半个动作，绝不存图、不切全屏。
	 *
	 * <p>存在的意义是把 90 多个 step 的整轮回归压成十几秒的前置走位，
	 * 让「只补一张图」的成本从 5 分钟降到 1 分钟。
	 */
	private static void stateOnlyStep(MinecraftClient mc, String tag, Runnable action) {
		Screen cur = mc.currentScreen;
		if (action != null && (cur == null || !isOurs(cur))) {
			action.run();
		}
		nextFast(tag);
	}

	/** 快速推进：不落盘、不切全屏，只留 2 tick 让命令出队。 */
	private static void nextFast(String tag) {
		skipped++;
		LOG.add("state-only step " + step + " (" + tag + ") at tick " + tick);
		step = step + 1;
		stepAt = tick + (step > LAST_STEP ? 10 : FAST_SETTLE);
		actionRanThisStep = false;
	}

	private static boolean isOurs(Screen s) {
		return s instanceof HubScreen || s instanceof TaskScreen || s instanceof CheckinScreen
				|| s instanceof ShopScreen
				// ★T11 卡了两轮的真因★：表格编辑器不在名单里 ⇒ shotKeep 认为"还没开"，
				// 每 25 tick 重跑一次 action（只记日志），13 次后 timeout skip，一张图都不拍。
				|| s instanceof com.akira.redtale.economy.client.admin.TableEditorScreen
				|| s instanceof com.akira.redtale.economy.client.admin.ShopEditScreen;
	}

	private static void next(int s) {
		step = s;
		stepAt = tick + SETTLE;
		actionRanThisStep = false;
		if (s > LAST_STEP) {
			// 拍完再等几 tick 让最后一张落盘
			stepAt = tick + 10;
		}
	}

	private static void finish(MinecraftClient mc) {
		active = false;
		if (mc.getWindow().isFullscreen()) {
			mc.getWindow().toggleFullscreen(); // 别把下一次开发运行留在全屏
		}
		Path dir = FabricLoader.getInstance().getGameDir();
		LOG.add("steps: whitelist=" + (whitelist == null ? "ALL (full sweep)" : whitelist.toString())
				+ " stateOnlySteps=" + skipped);
		LOG.addAll(pairReport(dir.resolve("screenshots")));
		flushLog(dir);
		mc.scheduleStop();
	}

	/**
	 * T44 成对自检：同一 base 必须 {@code -win.png} 与 {@code -fs.png} 都在，缺一即报出来。
	 *
	 * <p>放在这里是因为「少一张」正是本轮验证失败的定义 —— 让人肉翻 90 个文件名找缺口不现实。
	 */
	private static List<String> pairReport(Path shotsDir) {
		List<String> out = new ArrayList<>();
		if (!Files.isDirectory(shotsDir)) {
			out.add("pairs: screenshots dir missing " + shotsDir);
			return out;
		}
		List<String> missing = new ArrayList<>();
		try (var stream = Files.list(shotsDir)) {
			for (Path p : stream.toList()) {
				String n = p.getFileName().toString();
				if (n.endsWith("-win.png")) {
					if (!Files.exists(shotsDir.resolve(n.substring(0, n.length() - 8) + "-fs.png"))) {
						missing.add(n);
					}
				} else if (n.endsWith("-fs.png")) {
					if (!Files.exists(shotsDir.resolve(n.substring(0, n.length() - 7) + "-win.png"))) {
						missing.add(n);
					}
				}
			}
		} catch (IOException e) {
			out.add("pairs: scan failed " + e);
			return out;
		}
		out.add("pairs: win=" + winShots + " fs=" + fsShots
				+ " unpaired=" + missing.size() + (missing.isEmpty() ? "" : " -> " + missing));
		return out;
	}
}
