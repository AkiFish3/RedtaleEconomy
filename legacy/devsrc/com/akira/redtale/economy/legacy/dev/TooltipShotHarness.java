package com.akira.redtale.economy.legacy.dev;

import com.akira.redtale.economy.legacy.LegacyInit;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * T39 最后一个子项（客户端视觉）的无人值守夹具：验证「三个 tooltip 模组同场时，
 * 绑定物品的 owner 提示行仍出现在 {@code ItemStack#getTooltip} 的最终列表里，且不抛异常」。
 *
 * <p>存在标记文件 {@code <gameDir>/b16-ttip.flag} 时才注册；专用服务端直接返回，
 * 客户端类 {@link TooltipShotClient} 只在 CLIENT 分支里被引用（常量池惰性解析）。
 */
public final class TooltipShotHarness {
	private static final String FLAG = "b16-ttip.flag";

	private TooltipShotHarness() {
	}

	public static void register() {
		Path flag = FabricLoader.getInstance().getGameDir().resolve(FLAG);
		if (!Files.exists(flag)) {
			return;
		}
		if (FabricLoader.getInstance().getEnvironmentType() != net.fabricmc.api.EnvType.CLIENT) {
			return;
		}
		TooltipShotClient.register();
		LegacyInit.LOGGER.info("[b16] tooltip shot harness armed (flag={})", flag);
	}
}
