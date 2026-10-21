package com.akira.redtale.economy.legacy.dev;

import com.akira.redtale.economy.legacy.LegacyInit;
import com.akira.redtale.economy.legacy.entity.PackageEntity;
import com.akira.redtale.economy.legacy.init.REItems;
import net.fabricmc.api.EnvType;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * T38 §2.1/§2.2/§2.3 的无人值守视觉夹具（**开发期**，不是玩法代码）。
 *
 * <p>存在标记文件 {@code <gameDir>/b14-pkg-shots.flag} 时才注册。单机（集成服务端）下客户端与服务端同进程，
 * 所以客户端状态机用静态字段向服务端下单：「放一个某档位的包裹实体，摆到屏幕正中」。
 *
 * <p>为什么不用人眼：三档颜色与动画只能靠像素差自证。做法是把玩家钉在 (0.5,200,0.5) 面朝 +Z、
 * 包裹放在正前方 4 格且 Y == 玩家眼高、pitch=0 ⇒ 实体恒在画面正中，三张图背景完全一致，
 * 于是「换档位前后中心区域是否变化」就是贴图是否生效的直接证据；「同档位隔 12 tick 两帧是否有差」
 * 就是动画是否在播的证据。
 *
 * <p>安全：只在 flag 文件存在时激活；跑完自己 {@code scheduleStop()}。
 */
public final class PackageShotHarness {
	private static final String FLAG = "b14-pkg-shots.flag";
	static final String[] TIERS = {"normal", "rare", "legendary"};
	/** 玩家锚点与包裹位置（固定，保证背景一致）。 */
	static final double ANCHOR_X = 0.5;
	static final double ANCHOR_Y = 200.0;
	static final double ANCHOR_Z = 0.5;
	static final double PACKAGE_Z = 4.5;

	private static volatile boolean armed = false;
	/** -1 = 空场；0..2 = 请求生成对应档位。 */
	static volatile int spawnRequest = -1;
	private static int lastHandled = -2;
	private static PackageEntity current = null;

	private PackageShotHarness() {
	}

	public static void register() {
		Path flag = FabricLoader.getInstance().getGameDir().resolve(FLAG);
		if (!Files.exists(flag)) {
			return;
		}
		armed = true;
		ServerTickEvents.END_SERVER_TICK.register(PackageShotHarness::onServerTick);
		if (FabricLoader.getInstance().getEnvironmentType() == EnvType.CLIENT) {
			// 分支内引用客户端类：专用服务端不会走到这里，也不会解析该常量池项。
			PackageShotClient.register();
		}
		LegacyInit.LOGGER.info("[b14] pkg shot harness armed (flag={})", flag);
	}

	static Item tierItem(int idx) {
		return switch (idx) {
			case 1 -> REItems.RARE_PACKAGE;
			case 2 -> REItems.LEGENDARY_PACKAGE;
			default -> REItems.NORMAL_PACKAGE;
		};
	}

	private static void onServerTick(MinecraftServer server) {
		if (!armed) {
			return;
		}
		List<ServerPlayerEntity> players = server.getPlayerManager().getPlayerList();
		if (players.isEmpty()) {
			return;
		}
		int req = spawnRequest;
		if (req == lastHandled) {
			// 冻住，防止 capture 期间玩家掉落导致取景变化；正午也每秒重申一次
			ServerPlayerEntity p = players.get(0);
			p.setVelocity(0, 0, 0);
			((ServerWorld) p.getWorld()).setTimeOfDay(6000L);
			return;
		}
		lastHandled = req;

		ServerPlayerEntity player = players.get(0);
		ServerWorld world = (ServerWorld) player.getWorld();
		if (current != null) {
			if (current.isAlive()) {
				current.discard();
			}
			current = null;
		}

		// 固化环境：正午、晴天、玩家无重力
		world.setTimeOfDay(6000L);
		world.setWeather(6000, 0, false, false);
		player.setNoGravity(true);
		player.setVelocity(0, 0, 0);
		player.teleport(world, ANCHOR_X, ANCHOR_Y, ANCHOR_Z, 0.0F, 0.0F);

		if (req < 0 || req >= TIERS.length) {
			LegacyInit.LOGGER.info("[b14] cleared package field");
			return;
		}

		ItemStack stack = new ItemStack(tierItem(req));
		// chooseMode=true：服务端不会走给奖/自毁分支（否则 80 tick 就消失），实体能活到拍完。
		PackageEntity entity = new PackageEntity(world, ANCHOR_X, player.getEyeY(), PACKAGE_Z,
				stack, "idle", true);
		entity.setOwnerUuid(player.getUuid());
		world.spawnEntity(entity);
		current = entity;
		LegacyInit.LOGGER.info("[b14] spawned tier={} item={} at y={} playerEyeY={}",
				TIERS[req], stack.getItem(), entity.getY(), player.getEyeY());
	}
}
