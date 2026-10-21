package com.akira.redtale.economy.module;

import com.akira.redtale.economy.RedtaleEconomy;
import com.akira.redtale.economy.util.Logs;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityWorldChangeEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.List;

/**
 * 事件订阅集中点（T35）。
 *
 * <p>以前每个模块各自 {@code ServerPlayConnectionEvents.JOIN.register(...)}：Fabric 的注册顺序
 * 等于类加载顺序，一个模块抛异常会让同一事件后面的模块全部不执行（本工程踩过批量注册静默失败），
 * 而且没有任何地方能一眼看出「谁订阅了什么」。
 *
 * <p>现在所有模块级订阅都从这里过一层：
 * <ul>
 *   <li>注册顺序 = 调用顺序（{@link ModuleManager} 按 modules.json 的固定序列加载），可复现</li>
 *   <li>每个订阅单独 try/catch，一个坏了不影响其它模块（异常按 id 限流，60s 一条）</li>
 *   <li>订阅者清单可用 {@link #describe()} 打印，便于排查「为什么我的钩子没跑」</li>
 * </ul>
 *
 * <p>只收敛<b>模块业务</b>事件。根启动类里的 SERVER_STARTING/STARTED/STOPPING 与
 * 客户端事件不在此列（它们的时序和模块加载强相关，混进来反而难判）。
 */
public final class ModuleEvents {

	/** 玩家上线 / 下线。 */
	public interface PlayerHook {
		void accept(ServerPlayerEntity player);
	}

	/** 跨维度。 */
	public interface WorldChangeHook {
		void accept(ServerPlayerEntity player, ServerWorld origin, ServerWorld destination);
	}

	/** 右键方块。返回值非 PASS 会顶掉原版交互，慎用。 */
	public interface BlockUseHook {
		ActionResult accept(PlayerEntity player, World world, Hand hand, BlockHitResult hit);
	}

	/** 订阅项：id 只用于限流键与日志。包内可见，单测直接用。 */
	record Named<T>(String id, T hook) {
	}

	private static final List<Named<PlayerHook>> JOINS = new ArrayList<>();
	private static final List<Named<PlayerHook>> QUITS = new ArrayList<>();
	private static final List<Named<WorldChangeHook>> WORLD_CHANGES = new ArrayList<>();
	private static final List<Named<BlockUseHook>> BLOCK_USES = new ArrayList<>();
	private static boolean installed;
	private static boolean manifestLogged;

	private ModuleEvents() {
	}

	public static synchronized void onJoin(String id, PlayerHook hook) {
		install();
		JOINS.add(new Named<>(id, hook));
	}

	public static synchronized void onQuit(String id, PlayerHook hook) {
		install();
		QUITS.add(new Named<>(id, hook));
	}

	public static synchronized void onWorldChange(String id, WorldChangeHook hook) {
		install();
		WORLD_CHANGES.add(new Named<>(id, hook));
	}

	public static synchronized void onUseBlock(String id, BlockUseHook hook) {
		install();
		BLOCK_USES.add(new Named<>(id, hook));
	}

	/**
	 * 订阅者清单，格式 {@code join:guild,bounty;quit:...}。
	 * 用于启动日志与 `/rt admin modules` 一类诊断，不改运行时行为。
	 */
	public static synchronized String describe() {
		return "join:" + ids(JOINS)
				+ ";quit:" + ids(QUITS)
				+ ";world:" + ids(WORLD_CHANGES)
				+ ";block:" + ids(BLOCK_USES);
	}

	/** 仅测试用：清空订阅表并允许重新安装（生产路径不会调用）。 */
	public static synchronized void resetForTest() {
		JOINS.clear();
		QUITS.clear();
		WORLD_CHANGES.clear();
		BLOCK_USES.clear();
		installed = false;
		manifestLogged = false;
	}

	private static String ids(List<? extends Named<?>> list) {
		StringBuilder sb = new StringBuilder();
		for (Named<?> n : list) {
			if (sb.length() > 0) sb.append(',');
			sb.append(n.id());
		}
		return sb.toString();
	}

	private static void install() {
		if (installed) return;
		installed = true;

		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
				dispatch(JOINS, "join", handler.getPlayer()));

		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
				dispatch(QUITS, "quit", handler.getPlayer()));

		ServerEntityWorldChangeEvents.AFTER_PLAYER_CHANGE_WORLD.register((player, origin, destination) -> {
			for (Named<WorldChangeHook> n : WORLD_CHANGES) {
				try {
					n.hook().accept(player, origin, destination);
				} catch (Exception e) {
					Logs.warnOnce("ev.world." + n.id(), "[events] {} 维度切换处理失败：{}", n.id(), e.getMessage());
				}
			}
		});

		UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
			for (Named<BlockUseHook> n : BLOCK_USES) {
				try {
					ActionResult r = n.hook().accept(player, world, hand, hit);
					if (r != ActionResult.PASS) return r;
				} catch (Exception e) {
					Logs.warnOnce("ev.block." + n.id(), "[events] {} 右键处理失败：{}", n.id(), e.getMessage());
				}
			}
			return ActionResult.PASS;
		});
	}

	static void dispatch(List<Named<PlayerHook>> list, String kind, ServerPlayerEntity player) {
		logManifestOnce(kind);
		// 复制一份再遍历：钩子里注册新钩子不会 ConcurrentModificationException
		List<Named<PlayerHook>> snapshot = new ArrayList<>(list);
		for (Named<PlayerHook> n : snapshot) {
			try {
				n.hook().accept(player);
			} catch (Exception e) {
				Logs.warnOnce("ev." + kind + "." + n.id(), "[events] {} {} 处理失败：{}", n.id(), kind, e.getMessage());
			}
		}
	}

	/**
	 * 第一次真的收到事件时，把<b>当时</b>的订阅全表打进日志。
	 *
	 * <p>不能只在模块 bootstrap 里打：`net/*Networking` 的订阅（如 warehouse.cursor）是 bootstrap
	 * 之后才注册的，早打会漏人，日志写成"全量"就是假证据。
	 */
	private static void logManifestOnce(String kind) {
		if (manifestLogged) return;
		manifestLogged = true;
		try {
			RedtaleEconomy.LOGGER.info("[events] 首次事件={}，全量订阅：{}", kind, describe());
		} catch (Throwable ignored) {
			// 单测 / 无日志后端：打不出来不影响分发
		}
	}
}
