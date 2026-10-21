package com.akira.redtale.economy.client.dev;

import com.akira.redtale.economy.client.checkin.CheckinClientNetworking;
import com.akira.redtale.economy.client.checkin.CheckinScreen;
import com.akira.redtale.economy.client.ShopClientState;
import com.akira.redtale.economy.client.shop.ShopScreen;
import com.akira.redtale.economy.client.tasks.TaskScreen;
import com.akira.redtale.economy.client.ui.hub.ClientUiConfig;
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
	/** 等主世界加载的上限（tick），超时就在标题界面拍照。 */
	private static final int JOIN_TIMEOUT = 400;
	/** 每个界面渲染多少 tick 再拍（首帧字体/贴图还没绑好）。 */
	private static final int SETTLE = 25;
	/** 最后一个拍照 step（U4 5 + U11 6 + U9 13 + U10 设置页 16 + R39 音效探针 2 + U13 9 + 非 OP 2 + T44 商店滚动 1，到 89）。 */
	private static final int LAST_STEP = 203;
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
				}
			}
		} catch (Exception e) {
			LOG.add("flag parse failed (" + e.getMessage() + "), use default scale=" + GUI_SCALE);
		}
		LOG.add("shots start, gameDir=" + gameDir.toAbsolutePath());
		ClientTickEvents.END_CLIENT_TICK.register(UiShots::onTick);
	}

	private static void onTick(MinecraftClient mc) {
		if (!active) return;
		tick++;
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
					int max = hub.devSettingsScrollMax();
					hub.devScrollSettings(max);
					LOG.add("settings scroll to " + max + " (contentH="
							+ hub.devSettingsContentHeight() + ")");
				}
				clickSettingsRow(mc, HubSettingsPage.Row.ADMIN_HEAD, "expand");
			});
			case 49 -> act(mc, () -> {
				// 展开后内容变长，滚动上限也变 ⇒ 重新滚到底，否则「重载配置」那行被裁掉
				if (mc.currentScreen instanceof HubScreen hub) {
					int max = hub.devSettingsScrollMax();
					hub.devScrollSettings(max);
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
					LOG.add("shop scroll bottom -> row " + hub.devScrollShopBottom()
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
					int max = hub.devSettingsScrollMax();
					hub.devScrollSettings(max);
					LOG.add("settings scroll to " + max + " (contentH="
							+ hub.devSettingsContentHeight() + ")");
				}
				logPerm(mc, "mp");
			});
			case 203 -> shotKeep(mc, settingsShot(mc, "u6-51-settings-mp-bottom"), () -> { });
			// 拍完再点：点中就说明行真的存在于布局里（OP true / 非 OP false）。
			// 放在拍照之后，避免展开态被点成折叠、把上一张的布局改掉。
			case 204 -> act(mc, () -> {
				boolean before = HubSettingsPage.devAdminOpen();
				boolean hit = mc.currentScreen instanceof HubScreen hub
						? hub.devClickSettingsRow(HubSettingsPage.Row.ADMIN_HEAD) : false;
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
			case 92 -> shotKeep(mc, "u6-53-board.png", () -> { });
			case 93 -> act(mc, () -> mc.setScreen(null));
			default -> finish(mc);
		}
	}

	/**
	 * T44：把窗口钉死成 854×480 + guiScale=2（逻辑 427×240）。
	 *
	 * <p>不钉的话，每次跑出来的「错位」都可能是窗口大小的差异而不是布局本身的缺陷 ——
	 * 截图作为回归证据就没法比。全屏版的逻辑尺寸由同一份 guiScale 推出来，
	 * 所以两版之间唯一的变量就是「逻辑像素多少」。
	 */
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
		String pid = cur instanceof HubScreen h ? " page=" + h.devPageId() : "";
		LOG.add("shot " + out + " at tick " + tick
				+ " fb=" + win.getFramebufferWidth() + "x" + win.getFramebufferHeight()
				+ " logical=" + win.getScaledWidth() + "x" + win.getScaledHeight()
				+ " scale=" + win.getScaleFactor()
				+ " screen=" + (cur == null ? "null" : cur.getClass().getSimpleName()) + pid);
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
		boolean hit = hub.devClickSidebar(index);
		LOG.add("click " + label + " (row " + index + ") hit=" + hit + " page=" + hub.devPageId()
				+ " at tick " + tick);
	}

	/** 点商店目录树第 row 行（走 HubScreen 真实 mouseClicked 命中路径），并记录路径变化。 */
	private static void clickTree(MinecraftClient mc, int row) {
		if (!(mc.currentScreen instanceof HubScreen hub)) {
			LOG.add("tree click " + row + " skipped: screen is "
					+ (mc.currentScreen == null ? "null" : mc.currentScreen.getClass().getSimpleName()));
			return;
		}
		String before = ShopClientState.filter().categoryOrEmpty();
		boolean hit = hub.devClickTreeRow(row);
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
		hub.devSearch(text);
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
		boolean before = hub.devRankActiveOnly();
		boolean hit = hub.devClickRankToggle();
		LOG.add("rank toggle hit=" + hit + " state " + before + " -> " + hub.devRankActiveOnly()
				+ " at tick " + tick);
	}

	/** 点排行页顶部的榜单下拉（验「其余榜是二级」的下拉真的能开）。 */
	private static void clickRankDropdown(MinecraftClient mc) {
		if (!(mc.currentScreen instanceof HubScreen hub)) {
			LOG.add("rank dropdown skipped: screen is "
					+ (mc.currentScreen == null ? "null" : mc.currentScreen.getClass().getSimpleName()));
			return;
		}
		boolean hit = hub.devClickRankDropdown();
		LOG.add("rank dropdown hit=" + hit + " at tick " + tick);
	}

	/** 从下拉里选第 idx 个榜（走真实 mouseClicked 命中路径）。 */
	private static void clickRankBoard(MinecraftClient mc, int idx) {
		if (!(mc.currentScreen instanceof HubScreen hub)) {
			LOG.add("rank board skipped: screen is "
					+ (mc.currentScreen == null ? "null" : mc.currentScreen.getClass().getSimpleName()));
			return;
		}
		boolean hit = hub.devClickRankBoard(idx);
		LOG.add("rank board[" + idx + "] hit=" + hit + " selected=" + hub.devRankBoard()
				+ " at tick " + tick);
	}

	/** 点排行页「同步」按钮，并记下点完后的冷却剩余秒数（验 60 秒冷却真的生效）。 */
	private static void clickRankSync(MinecraftClient mc, String tag) {
		if (!(mc.currentScreen instanceof HubScreen hub)) {
			LOG.add("rank sync skipped: screen is "
					+ (mc.currentScreen == null ? "null" : mc.currentScreen.getClass().getSimpleName()));
			return;
		}
		int before = hub.devRankSyncCooldownLeft();
		boolean hit = hub.devClickRankSync();
		LOG.add("rank sync[" + tag + "] hit=" + hit + " cooldownBefore=" + before
				+ " cooldownAfter=" + hub.devRankSyncCooldownLeft() + " at tick " + tick);
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
				+ " uiActiveOnly=" + (mc.currentScreen instanceof HubScreen h ? h.devRankActiveOnly() : null)
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
		boolean hit = hub.devClickSettingsRow(row);
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
		LOG.add("act step " + step + " at tick " + tick);
		next(step + 1);
	}

	private static boolean isOurs(Screen s) {
		return s instanceof HubScreen || s instanceof TaskScreen || s instanceof CheckinScreen
				|| s instanceof ShopScreen;
	}

	private static void next(int s) {
		step = s;
		stepAt = tick + SETTLE;
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
		try {
			Path dir = FabricLoader.getInstance().getGameDir();
			LOG.addAll(pairReport(dir.resolve("screenshots")));
			Files.write(dir.resolve("u6-shots.log"), LOG, StandardCharsets.UTF_8);
		} catch (IOException ignored) {
			// 日志写不出来不影响已经存下的图
		}
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
