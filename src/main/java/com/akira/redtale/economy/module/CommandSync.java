package com.akira.redtale.economy.module;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.tree.CommandNode;
import net.minecraft.server.command.ServerCommandSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.util.Map;

/**
 * T51：根命令的「临时下线 / 重新上线」。
 *
 * <p>Brigadier 1.1.8 的 {@link CommandNode} <b>没有公开的删除接口</b>
 * （{@code removeChildByName} 是 1.2+ 才有），所以这里反射拿三个镜像表
 * （{@code children} / {@code literals} / {@code arguments}）一起删 ——
 * 只删一个会留下「{@code getChild} 返回 null 但 literals 里还在」的幽灵节点，
 * 补全时仍会出现该命令。
 *
 * <p>★只在 reload / 手动开关模块时调用★，不在每次注册时调用：反射一次约微秒级，
 * 但把「注册」这条路径依赖到反射上没有任何好处。
 *
 * <p>重新上线不用这里：直接再跑一遍 {@code module.registerCommands(dispatcher)} ——
 * Brigadier 的 {@code addChild} 对同名根字面量是合并语义，重复注册是幂等的。
 */
public final class CommandSync {

	private static final Logger LOG = LoggerFactory.getLogger("redtale-economy/commands");

	private CommandSync() {
	}

	/** 从命令树根部摘掉根字面量 {@code name}；返回是否真的摘掉了东西。 */
	@SuppressWarnings("unchecked")
	public static boolean removeRoot(CommandDispatcher<ServerCommandSource> dispatcher, String name) {
		if (dispatcher == null || name == null || name.isBlank()) return false;
		CommandNode<ServerCommandSource> root = dispatcher.getRoot();
		if (root.getChild(name) == null) return false;
		boolean ok = false;
		ok |= drop(root, "children", name);
		ok |= drop(root, "literals", name);
		ok |= drop(root, "arguments", name);
		if (!ok) LOG.warn("[cmd] /{} 下线失败（Brigadier 内部结构变化？）", name);
		return ok;
	}

	@SuppressWarnings("unchecked")
	private static boolean drop(CommandNode<ServerCommandSource> root, String field, String name) {
		try {
			Field f = CommandNode.class.getDeclaredField(field);
			f.setAccessible(true);
			Object raw = f.get(root);
			if (raw instanceof Map<?, ?> m) {
				// 只在确实存在同名键时记录，避免 children 删掉后 literals 的空删被当成失败
				return ((Map<String, ?>) m).remove(name) != null;
			}
		} catch (ReflectiveOperationException | RuntimeException e) {
			LOG.warn("[cmd] 摘除 /{} 时读取 {} 失败：{}", name, field, e.toString());
		}
		return false;
	}

	/** 仅单测：判断根上是否还有该名字。 */
	public static boolean hasRoot(CommandDispatcher<ServerCommandSource> dispatcher, String name) {
		return dispatcher != null && name != null && dispatcher.getRoot().getChild(name) != null;
	}
}
