package com.akira.redtale.economy.command;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.server.command.ServerCommandSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 根命令声明表（T35「声明式命令注册」）。
 *
 * <p>注册顺序不能再靠「谁先调 register」隐式决定：Brigadier 的 {@code addChild} 对同名
 * 根字面量是<b>静默合并</b>（只合并子节点与 executor，<b>不合并 requires</b>），
 * 于是「两个模块抢同一个名字」和「抢到原版同名命令」在日志里全是干净的 —— 表现只有
 * 玩家侧一句 {@code Unknown or incomplete command}。实测过一次：{@code /title} 被原版
 * 吞掉，改名 {@code /titles} 才修好（2026-10-21）。
 *
 * <p>因此每个命令类在 {@code register()} 的第一行<b>声明</b>自己占哪个根名字：
 * <pre>{@code
 * CommandTable.declare(d, "shop", "shop");
 * }</pre>
 * 声明是纯登记 + 冲突检测，不改变命令树本身；树仍然由各命令类自己搭（4.7k 行）
 * —— 一次性重写成「表驱动建树」收益不抵风险，这里只把「谁占了哪个名字」变成可查证的事实。
 *
 * <p>冲突只记一次（同一 name+owner 重复声明是 reload 时的正常重注册，不报）。
 */
public final class CommandTable {

	private static final Logger LOG = LoggerFactory.getLogger("redtale-economy/commands");

	/** 一条声明：根命令名 + 归属模块 id。 */
	public record Entry(String name, String owner) {
	}

	private static final Map<String, Entry> CLAIMS = new LinkedHashMap<>();
	private static final List<String> CONFLICTS = new ArrayList<>();

	private CommandTable() {
	}

	/**
	 * 声明根命令 {@code name} 归 {@code owner}，并检查它是否已被别人先占（原版/别的模组/别的模块）。
	 *
	 * <p>必须在 {@code dispatcher.register(...)} <b>之前</b>调用，否则自己的节点已在根上，
	 * 首次注册也会被当成「被占用」。
	 */
	public static void declare(CommandDispatcher<ServerCommandSource> dispatcher, String name, String owner) {
		boolean first = !CLAIMS.containsKey(name);
		declare(name, owner);
		if (!first || dispatcher == null) return;
		if (dispatcher.getRoot().getChild(name) != null) {
			conflict("根命令 /" + name + " 在注册前已存在于命令树（原版或其它模组），"
					+ owner + " 的子命令不会对普通玩家生效");
		}
	}

	/** 纯登记：同名不同 owner 记冲突并保留先声明者。 */
	public static void declare(String name, String owner) {
		if (name == null || name.isBlank()) return;
		Entry prev = CLAIMS.get(name);
		if (prev == null) {
			CLAIMS.put(name, new Entry(name, owner));
			return;
		}
		if (!prev.owner().equals(owner)) {
			conflict("根命令 /" + name + " 被两个模块声明：" + prev.owner() + " 与 " + owner);
		}
	}

	/** 已声明的根命令（按声明顺序）。 */
	public static List<Entry> entries() {
		return List.copyOf(CLAIMS.values());
	}

	/** 冲突明细（空 = 无冲突）。 */
	public static List<String> conflicts() {
		return List.copyOf(CONFLICTS);
	}

	public static boolean isDeclared(String name) {
		return CLAIMS.containsKey(name);
	}

	/** 单测用：清空状态（生产环境不调）。 */
	public static void resetForTest() {
		CLAIMS.clear();
		CONFLICTS.clear();
	}

	private static void conflict(String msg) {
		if (CONFLICTS.contains(msg)) return;
		CONFLICTS.add(msg);
		LOG.error("[cmd] {}", msg);
	}
}
