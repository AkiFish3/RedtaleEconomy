package com.akira.redtale.economy.perm;

import com.akira.redtale.economy.RedtaleEconomy;
import net.minecraft.server.network.ServerPlayerEntity;

import java.lang.reflect.Method;

/**
 * FTB Ranks 权限（spec v0.13 §18.2，Aki 实际在用）。
 *
 * <p>反射调用 {@code FTBRanksAPI.getPermissionValue(ServerPlayer, String)}，
 * 再取 {@code asBooleanOrFalse()}。节点<b>未配置</b>时返回 {@code MISSING}，
 * 由 {@link Permissions} 决定是否按 OP 等级兜底。
 *
 * <p>用反射而不是 compileOnly：FTB Ranks 的 jar 不在构建机上，
 * 而且版本间 API 偶有变动，反射失败只会降级到下一个 provider，不影响启动。
 */
public final class FtbRanksProvider implements PermissionProvider {

	private static Method getPermissionValue;
	private static Method asBooleanOrFalse;
	private static Method isMissing;
	private static boolean broken;

	FtbRanksProvider() {
		if (getPermissionValue != null || broken) return;
		try {
			Class<?> api = Class.forName("dev.ftb.mods.ftbranks.api.FTBRanksAPI");
			getPermissionValue = api.getMethod("getPermissionValue", ServerPlayerEntity.class, String.class);
			Class<?> value = Class.forName("dev.ftb.mods.ftbranks.api.PermissionValue");
			asBooleanOrFalse = value.getMethod("asBooleanOrFalse");
			try {
				isMissing = value.getMethod("isMissing");
			} catch (NoSuchMethodException ignored) {
				isMissing = null;
			}
			RedtaleEconomy.LOGGER.info("[perm] FTB Ranks 已接入");
		} catch (Throwable t) {
			broken = true;
			RedtaleEconomy.LOGGER.warn("[perm] FTB Ranks API 反射失败：{}", t.toString());
		}
	}

	/** 是否可用（API 反射成功）。 */
	public static boolean usable() {
		return getPermissionValue != null && !broken;
	}

	@Override
	public String id() {
		return "ftbranks";
	}

	@Override
	public boolean has(ServerPlayerEntity player, String node) {
		try {
			Object value = getPermissionValue.invoke(null, player, node);
			if (value == null) return false;
			if (isMissing != null && Boolean.TRUE.equals(isMissing.invoke(value))) {
				return Permissions.fallbackToOp(player, node);
			}
			return Boolean.TRUE.equals(asBooleanOrFalse.invoke(value));
		} catch (Throwable t) {
			broken = true;
			RedtaleEconomy.LOGGER.warn("[perm] FTB Ranks 查询失败，降级 OP：{}", t.toString());
			return Permissions.fallbackToOp(player, node);
		}
	}
}
