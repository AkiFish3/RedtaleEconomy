package com.akira.redtale.economy.perm;

import net.minecraft.server.network.ServerPlayerEntity;

/**
 * 退回原版 OP 等级（永远可用）。
 *
 * <p>节点 → 等级 的映射由 {@link Permissions} 传入，不再硬编码在命令里。
 */
public final class OpProvider implements PermissionProvider {

	private final java.util.Map<String, Integer> levels;

	OpProvider(java.util.Map<String, Integer> levels) {
		this.levels = levels;
	}

	@Override
	public String id() {
		return "op";
	}

	@Override
	public boolean has(ServerPlayerEntity player, String node) {
		Integer need = levels.get(node);
		if (need == null) return false;
		return player.hasPermissionLevel(need);
	}

	@Override
	public int level(ServerPlayerEntity player) {
		for (int i = 4; i >= 0; i--) {
			if (player.hasPermissionLevel(i)) return i;
		}
		return 0;
	}
}
