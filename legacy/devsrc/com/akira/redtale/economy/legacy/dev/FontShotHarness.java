package com.akira.redtale.economy.legacy.dev;

import com.akira.redtale.economy.legacy.LegacyInit;
import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * T67 硬币字体的无人值守视觉夹具（**开发期**，不进发行 jar）。
 *
 * <p>存在标记文件 {@code <gameDir>/b32-font.flag} 时才注册；跑完自己 {@code scheduleStop()}。
 *
 * <p>为什么需要它：{@code HubMoney.fontReady()} 只查 {@code assets/.../font/coins.json} **存在**，
 * 查不出「png 放错目录导致整个 builder 被 rejecting」这类故障 —— 那时 json 还在、探针返回 true，
 * 字形却一个都没有。所以判据必须是像素：把 {@code HubMoney.asText()} 画在纯黑底上截图，
 * 再由 {@code _b32_fontpix.py} 数三枚硬币颜色的像素数。
 *
 * <p>只用生产 API（{@link com.akira.redtale.economy.client.ui.hub.HubMoney}），不复制渲染逻辑，
 * 否则测的是夹具自己。
 */
public final class FontShotHarness {
	private static final String FLAG = "b32-font.flag";

	private FontShotHarness() {
	}

	public static void register() {
		Path flag = FabricLoader.getInstance().getGameDir().resolve(FLAG);
		if (!Files.exists(flag)) {
			return;
		}
		if (FabricLoader.getInstance().getEnvironmentType() == EnvType.CLIENT) {
			// 分支内引用客户端类：专用服务端不会走到这里，也不会解析该常量池项。
			FontShotClient.register();
		}
		LegacyInit.LOGGER.info("[b32] font shot harness armed (flag={})", flag);
	}
}
