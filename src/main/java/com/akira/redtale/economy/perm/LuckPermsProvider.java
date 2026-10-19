package com.akira.redtale.economy.perm;

import com.akira.redtale.economy.RedtaleEconomy;
import net.minecraft.server.network.ServerPlayerEntity;

import java.lang.reflect.Method;
import java.util.Optional;
import java.util.UUID;

/**
 * LuckPerms 权限（兼容支持，spec v0.13 §18.1）。
 *
 * <p>链路：{@code LuckPermsProvider.get()} → {@code getUserManager().getUser(uuid)}
 * → {@code getCachedData().getPermissionData().checkPermission(node)} → {@code asBoolean()}。
 * 全程反射，未装 LuckPerms 时不会加载任何相关类。
 */
public final class LuckPermsProvider implements PermissionProvider {

	private static Method getApi;
	private static Method getUserManager;
	private static Method getUser;
	private static Method getCachedData;
	private static Method getPermissionData;
	private static Method checkPermission;
	private static Method asBoolean;
	private static boolean broken;

	LuckPermsProvider() {
		if (getApi != null || broken) return;
		try {
			Class<?> provider = Class.forName("net.luckperms.api.LuckPermsProvider");
			getApi = provider.getMethod("get");

			Class<?> api = Class.forName("net.luckperms.api.LuckPerms");
			getUserManager = api.getMethod("getUserManager");

			Class<?> userManager = Class.forName("net.luckperms.api.model.user.UserManager");
			getUser = userManager.getMethod("getUser", UUID.class);

			Class<?> user = Class.forName("net.luckperms.api.model.user.User");
			getCachedData = user.getMethod("getCachedData");

			Class<?> cachedData = Class.forName("net.luckperms.api.cacheddata.CachedData");
			getPermissionData = cachedData.getMethod("getPermissionData");

			Class<?> permData = Class.forName("net.luckperms.api.cacheddata.CachedPermissionData");
			checkPermission = permData.getMethod("checkPermission", String.class);

			Class<?> tristate = Class.forName("net.luckperms.api.util.Tristate");
			asBoolean = tristate.getMethod("asBoolean");
			RedtaleEconomy.LOGGER.info("[perm] LuckPerms 已接入");
		} catch (Throwable t) {
			broken = true;
			RedtaleEconomy.LOGGER.warn("[perm] LuckPerms API 反射失败：{}", t.toString());
		}
	}

	public static boolean usable() {
		return getApi != null && !broken;
	}

	@Override
	public String id() {
		return "luckperms";
	}

	@Override
	public boolean has(ServerPlayerEntity player, String node) {
		try {
			Object api = getApi.invoke(null);
			Object um = getUserManager.invoke(api);
			@SuppressWarnings("unchecked")
			Optional<Object> user = (Optional<Object>) getUser.invoke(um, player.getUuid());
			if (user.isEmpty()) return Permissions.fallbackToOp(player, node);
			Object data = getCachedData.invoke(user.get());
			Object permData = getPermissionData.invoke(data);
			Object tri = checkPermission.invoke(permData, node);
			Object result = asBoolean.invoke(tri);
			return Boolean.TRUE.equals(result);
		} catch (Throwable t) {
			broken = true;
			RedtaleEconomy.LOGGER.warn("[perm] LuckPerms 查询失败，降级 OP：{}", t.toString());
			return Permissions.fallbackToOp(player, node);
		}
	}
}
