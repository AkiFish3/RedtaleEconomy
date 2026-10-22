package com.akira.redtale.economy.legacy.dev;

import com.akira.redtale.economy.legacy.LegacyInit;
import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 物品动画的像素证据夹具（**开发期**，不在源集内 ⇒ 不进发行 jar）。
 *
 * <p>要证的事：{@code PackageItem} 的 {@code item.idle} 是否真的在播。第 37 轮（commit abf83fa）
 * 删掉了写死 {@code return 0} 的 {@code getTick} 覆盖，依据是 javap 读数（TICK 非 null ⇒
 * {@code GeoModel.handleAnimations} 每帧提前 return）。**那是静态推理，没有任何运行时证据。**
 *
 * <p>做法：开一个纯黑背景的自绘界面，把物品按 6 倍缩放画在屏幕正中，隔 N tick 连拍。
 * 黑色背景的好处是「非黑像素 = 物品本身」，不需要空场参考帧，也不需要钉时间/关云。
 *
 * <p>判据是 A/B 对照：
 * <ul>
 *   <li>正样本 {@code surprise}：{@code item.idle} 是 egg 骨骼 ±4° 摇晃 / 3 秒循环 ⇒ 帧差应显著大于 0；</li>
 *   <li>负样本 {@code package_normal}：{@code item.idle} 是空动画 ⇒ 帧差应≈0（同一套渲染路径，排除
 *       「GUI 里本来就有抖动」这种假绿）。</li>
 * </ul>
 *
 * <p>启用：flag {@code <gameDir>/b36-item-shots.flag} 存在时注册（见 {@code legacy/devsrc/README.md}）。
 */
public final class ItemShotHarness {
	static final String FLAG = "b36-item-shots.flag";
	/** 拍摄清单：物品 path（注册表路径）。正样本在前，负样本在后。 */
	static final String[] SHOTS = {"surprise", "package_normal"};
	/** 放大倍数：原版 GUI 物品是 16×16，6 倍 ⇒ 96×96，4° 旋转能落到好几像素上。 */
	static final float SCALE = 6.0F;
	/** 连拍帧数与间隔（tick）。3 秒一圈 ⇒ 10 tick = 0.5s ≈ 60°，肉眼等效于明显位移。 */
	static final int FRAMES = 8;
	static final int FRAME_GAP = 10;
	/** 开界面后先等这么多 tick（geckolib 首次渲染要建 renderer/缓存）。 */
	static final int AFTER_OPEN = 20;

	private ItemShotHarness() {
	}

	public static void register() {
		Path flag = FabricLoader.getInstance().getGameDir().resolve(FLAG);
		if (!Files.exists(flag)) {
			return;
		}
		if (FabricLoader.getInstance().getEnvironmentType() != EnvType.CLIENT) {
			return;
		}
		// 分支内引用客户端类：专用服务端不会走到这里，也不会解析该常量池项。
		ItemShotClient.register();
		LegacyInit.LOGGER.info("[b36] item shot harness armed (flag={})", flag);
	}
}
