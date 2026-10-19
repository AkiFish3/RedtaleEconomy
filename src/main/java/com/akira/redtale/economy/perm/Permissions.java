package com.akira.redtale.economy.perm;

import com.akira.redtale.economy.RedtaleEconomy;
import com.akira.redtale.economy.config.CoreConfig;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.HashMap;
import java.util.Map;

/**
 * 权限总入口（spec v0.13 §18）。
 *
 * <p>探测顺序 {@code ftbranks → luckperms → op}，由 {@code permissions.provider} 配置决定；
 * {@code auto} 时按顺序探测。控制台/命令方块（非玩家源）一律放行。
 *
 * <p>节点名与 OP 等级映射都在 {@code core.json → permissions} 里，命令侧只写
 * {@link #NODE_USE} / {@link #NODE_ADMIN} / {@link #NODE_OWNER} / {@link #NODE_PREMIUM_GIVE}。
 */
public final class Permissions {

	public static final String NODE_USE = "use";
	public static final String NODE_ADMIN = "admin";
	public static final String NODE_OWNER = "owner";
	public static final String NODE_PREMIUM_GIVE = "premiumGive";

	private static volatile PermissionProvider provider;

	private Permissions() {
	}

	/** 启动/重载时调用；失败一律回落 OP，保证服务器不会因为权限插件异常锁死。 */
	public static synchronized void init() {
		String want = CoreConfig.permissionsProvider().toLowerCase();
		Map<String, Integer> levels = levels();
		PermissionProvider chosen = null;
		if (want.equals("op")) {
			chosen = new OpProvider(levels);
		} else {
			boolean auto = want.equals("auto");
			if ((auto || want.equals("ftbranks"))
					&& FabricLoader.getInstance().isModLoaded("ftb-ranks")) {
				FtbRanksProvider p = new FtbRanksProvider();
				if (FtbRanksProvider.usable()) chosen = p;
			}
			if (chosen == null && (auto || want.equals("luckperms"))
					&& FabricLoader.getInstance().isModLoaded("luckperms")) {
				LuckPermsProvider p = new LuckPermsProvider();
				if (LuckPermsProvider.usable()) chosen = p;
			}
			if (chosen == null) {
				// Q5 fail-closed：配了权限插件却用不上 ⇒ 只保留 use，管理节点一律拒绝
				Map<String, Integer> effective = levels;
				if (CoreConfig.failClosed() && !want.equals("op")) {
					effective = new HashMap<>();
					effective.put(CoreConfig.nodeName(NODE_USE), CoreConfig.nodeLevel(NODE_USE, 0));
				}
				chosen = new OpProvider(effective);
			}
		}
		provider = chosen;
		RedtaleEconomy.LOGGER.info("[perm] provider = {}（请求 {}）", chosen.id(), want);
	}

	public static PermissionProvider provider() {
		PermissionProvider p = provider;
		if (p == null) {
			init();
			p = provider;
		}
		return p;
	}

	/** 完整节点名 → OP 等级（供 OpProvider 与 fallback 使用）。 */
	private static Map<String, Integer> levels() {
		Map<String, Integer> m = new HashMap<>();
		m.put(CoreConfig.nodeName(NODE_USE), CoreConfig.nodeLevel(NODE_USE, 0));
		m.put(CoreConfig.nodeName(NODE_ADMIN), CoreConfig.nodeLevel(NODE_ADMIN,
				CoreConfig.perm("adminPermissionLevel", 4)));
		m.put(CoreConfig.nodeName(NODE_OWNER), CoreConfig.nodeLevel(NODE_OWNER,
				CoreConfig.perm("reloadPermissionLevel", 4)));
		m.put(CoreConfig.nodeName(NODE_PREMIUM_GIVE), CoreConfig.nodeLevel(NODE_PREMIUM_GIVE, 4));
		return m;
	}

	/** 节点未配置时是否退回 OP 等级判断（spec §18.4）。 */
	static boolean fallbackToOp(ServerPlayerEntity player, String fullNode) {
		// Q5：插件报错/节点缺失时，failClosed=true 一律拒绝，避免「插件挂了 = 人人 OP」
		if (CoreConfig.failClosed()) return false;
		if (!CoreConfig.fallbackToOp()) return false;
		Integer need = levels().get(fullNode);
		return need != null && player.hasPermissionLevel(need);
	}

	/** 命令侧统一入口：非玩家源放行，玩家源走 provider。 */
	public static boolean has(ServerCommandSource src, String node) {
		ServerPlayerEntity player = src.getPlayer();
		if (player == null) return true;
		return provider().has(player, CoreConfig.nodeName(node));
	}

	public static boolean has(ServerPlayerEntity player, String node) {
		return provider().has(player, CoreConfig.nodeName(node));
	}
}
