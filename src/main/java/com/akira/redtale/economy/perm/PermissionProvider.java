package com.akira.redtale.economy.perm;

import net.minecraft.server.network.ServerPlayerEntity;

/**
 * 权限提供者抽象（spec v0.13 §18.1）。
 *
 * <p>顺序：FTB Ranks → LuckPerms → OP，由 {@link Permissions} 在启动时探测。
 * 三个实现都用反射访问外部 mod 的 API，因此<b>没有编译期依赖</b>，
 * 未安装时对应类不会被实例化。
 */
public interface PermissionProvider {

	/** 检测顺序里的名字，用于日志。 */
	String id();

	/** 玩家是否拥有该节点。 */
	boolean has(ServerPlayerEntity player, String node);

	/** 仅 OP 模式使用；其它实现返回 -1 表示「不适用」。 */
	default int level(ServerPlayerEntity player) {
		return -1;
	}
}
