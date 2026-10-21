package com.akira.redtale.economy.client.dev;

import java.util.List;
import java.util.Set;

/**
 * T52：识别「致命界面」—— 客户端停在这一屏就说明这一轮截图必然全废，
 * 夹具必须立刻取证并退出，而不是傻等 400 tick 超时后再被人为/脚本重开一次。
 *
 * <p>实测过的两种表现（2026-10-21~22）：
 * <ul>
 *   <li>{@code libs/dev} 里某个模组缺依赖 ⇒ 启动后弹 {@code NoticeScreen}（标题就是
 *       "Incompatible mods found" / "Missing dependencies"），夹具既不进世界也不报错，
 *       表现为「游戏起来了但一张图没有，重开一次还是」。 </li>
 *   <li>渲染/初始化真崩 ⇒ {@code CrashScreen}（"游戏崩溃了"），此时 latest.log 里
 *       的堆栈才是根因，界面上的标题几乎没信息量。</li>
 * </ul>
 *
 * <p>为什么按「类名链」判而不是 {@code instanceof}：这些类在不同版本包里搬过，
 * 且 {@code CrashScreen} 是 {@code Screen} 直接子类而非 {@code ErrorScreen} 子类，
 * 用名字链可以一次覆盖子类（{@code DisconnectedScreen} 之类）而不引入编译期依赖。
 *
 * <p>本类刻意不含任何 Minecraft 类引用 —— 这样单测能在纯 JVM 下加载。
 */
public final class FatalScreens {

	/**
	 * 命中即判定「本轮无意义」的界面类（全限定名）。
	 *
	 * <p>★2026-10-22 javap 核对（yarn 1.20.1+build.10）★：本版本**没有**
	 * {@code ErrorScreen} 与 {@code CrashScreen}（那是 mojmap/旧版记忆），真正的三张是
	 * {@code FatalErrorScreen} / {@code NoticeScreen} / {@code OutOfMemoryScreen}，
	 * 全部直接继承 {@code Screen}。所以「按父类 instanceof 判」在这版根本不成立，
	 * 必须按类名逐个列。{@code CrashScreen} 留作跨版本兼容（1.21 有）。
	 */
	public static final Set<String> NAMES = Set.of(
			"net.minecraft.client.gui.screen.FatalErrorScreen",
			"net.minecraft.client.gui.screen.NoticeScreen",
			"net.minecraft.client.gui.screen.OutOfMemoryScreen",
			"net.minecraft.client.gui.screen.CrashScreen",
			"net.minecraft.client.gui.screen.ErrorScreen");

	/**
	 * 已知但**不算致命**的界面：连接失败（{@code DisconnectedScreen}）在
	 * quickPlayMultiplayer 连不上时很常见，此时退回标题界面继续跑标题栏截图仍有价值，
	 * 不该整轮中止。
	 */
	public static final Set<String> NOT_FATAL = Set.of(
			"net.minecraft.client.gui.screen.DisconnectedScreen",
			"net.minecraft.client.gui.screen.ConnectScreen",
			"net.minecraft.client.gui.screen.MessageScreen");

	private FatalScreens() {
	}

	/** 单个类名是否致命。{@code null} 安全（返回 false）。 */
	public static boolean matches(String className) {
		if (className == null) return false;
		return !NOT_FATAL.contains(className) && NAMES.contains(className);
	}

	/**
	 * 沿继承链判：{@code chain} 必须**从子类到父类**排列（调用方自己 getSuperclass 走出来）。
	 * 命中 NOT_FATAL 立即返回 false（子类豁免优先于父类命中）。
	 */
	public static boolean matchesChain(List<String> chain) {
		if (chain == null) return false;
		for (String n : chain) {
			if (n == null) continue;
			if (NOT_FATAL.contains(n)) return false;
			if (NAMES.contains(n)) return true;
		}
		return false;
	}

	/** 给日志用的一句话建议（避免下次又去猜「是不是缺依赖」）。 */
	public static String hint(String className) {
		if (className == null) return "unknown screen";
		if (className.endsWith("OutOfMemoryScreen")) return "堆内存耗尽：调 -Xmx，别重开";
		if (className.endsWith("FatalErrorScreen")) return "真崩溃：看 logs/latest.log 的堆栈与 crash-reports/";
		if (className.endsWith("NoticeScreen") || className.endsWith("ErrorScreen")) {
			return "多为模组缺依赖/版本冲突：先跑 _mod_deps_check.py，别盲目重开";
		}
		return "unknown screen";
	}
}
