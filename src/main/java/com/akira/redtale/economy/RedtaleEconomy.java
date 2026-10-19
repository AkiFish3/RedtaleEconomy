package com.akira.redtale.economy;

import com.akira.redtale.economy.command.RedtaleCommands;
import com.akira.redtale.economy.config.ConfigManager;
import com.akira.redtale.economy.config.CoreConfig;
import com.akira.redtale.economy.config.EconomyConfig;
import com.akira.redtale.economy.config.FtbqConfig;
import com.akira.redtale.economy.config.PremiumConfig;
import com.akira.redtale.economy.config.StorageConfig;
import com.akira.redtale.economy.economy.BalanceService;
import com.akira.redtale.economy.lang.Lang;
import com.akira.redtale.economy.module.ModuleManager;
import com.akira.redtale.economy.module.impl.AliasModule;
import com.akira.redtale.economy.module.impl.CheckinModule;
import com.akira.redtale.economy.module.impl.EconomyModule;
import com.akira.redtale.economy.module.impl.PremiumModule;
import com.akira.redtale.economy.module.impl.ShopModule;
import com.akira.redtale.economy.module.impl.StorageModule;
import com.akira.redtale.economy.module.impl.TaskModule;
import com.akira.redtale.economy.net.ConfigNetworking;
import com.akira.redtale.economy.net.ShopNetworking;
import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Redtale Economy 主入口（Fabric 1.20.1）。
 *
 * <p>与旧 `redtale` 模组无任何兼容关系：旧工程只复用构建配置，
 * 其 item/entity/screen 注册一律不迁移。
 *
 * <p>启动顺序：登记配置 schema → 注册模块 → 加载配置 → 解析模块开关 → 注册命令。
 * 配置先于模块加载，保证模块 onLoad 里读到的都是已校验过的值。
 */
public class RedtaleEconomy implements ModInitializer {

	public static final String MOD_ID = "redtale_economy";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private static RedtaleEconomy instance;
	private static String version = "unknown";
	private static MinecraftServer server;

	@Override
	public void onInitialize() {
		instance = this;
		LOGGER.info("[{}] initializing (Fabric 1.20.1)", MOD_ID);

		FabricLoader.getInstance().getModContainer(MOD_ID)
				.ifPresent(c -> version = c.getMetadata().getVersion().getFriendlyString());

		// 补签券物品（Q1）：签到补签用，id 可在 checkin.json → makeup.ticketItem 引用
		net.minecraft.registry.Registry.register(net.minecraft.registry.Registries.ITEM,
				new Identifier(MOD_ID, "makeup_ticket"),
				new net.minecraft.item.Item(new net.minecraft.item.Item.Settings().maxCount(16)));

		ConfigManager cfg = ConfigManager.create();
		cfg.define(CoreConfig.FILE, CoreConfig.SCHEMA);
		cfg.define(EconomyConfig.FILE, EconomyConfig.SCHEMA);
		cfg.define(StorageConfig.FILE, StorageConfig.SCHEMA);
		cfg.define(PremiumConfig.FILE, PremiumConfig.SCHEMA);
		cfg.define(FtbqConfig.FILE, FtbqConfig.SCHEMA);
		cfg.define(com.akira.redtale.economy.config.SdmMigrateConfig.FILE,
				com.akira.redtale.economy.config.SdmMigrateConfig.SCHEMA);

		ModuleManager modules = ModuleManager.get();
		modules.register(new EconomyModule());
		modules.register(new ShopModule());
		modules.register(new CheckinModule());
		modules.register(new TaskModule());
		modules.register(new StorageModule());
		modules.register(new PremiumModule());
		// 别名必须最后注册：它要看到其它模块已挂好的命令
		modules.register(new AliasModule());
		// 后续模块（checkin / tasks / shop / premium / ftbq）在此注册
		modules.defineAll(cfg);

		cfg.loadAll();
		Lang.init();
		com.akira.redtale.economy.perm.Permissions.init();
		modules.bootstrap(cfg);

		// FTB Quests 软依赖（R9）：未装时 init() 立即返回，不加载任何 FTBQ 类型
		if (FabricLoader.getInstance().isModLoaded("ftbquests")) {
			com.akira.redtale.economy.integration.FtbqIntegration.init();
		}

		// 配置编辑器（R14 V1）：与商店通道同级，模块关闭不影响「改配置」这条治理路径
		ConfigNetworking.registerServer();

		// ★必须同时注册「模块命令」★ 只注册 /redtale 会导致 /shop /bal /checkin /tasks 全都不存在
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
			RedtaleCommands.register(dispatcher);
			modules.registerCommands(dispatcher);
		});

		ServerLifecycleEvents.SERVER_STARTED.register(s -> server = s);
		net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.DISCONNECT.register(
				(handler, s) -> ShopNetworking.forget(handler.getPlayer().getUuid()));
		ServerLifecycleEvents.SERVER_STOPPING.register(s -> {
			// B25 关服顺序：快照 → 余额落盘 → 流水排空 → 关连接
			com.akira.redtale.economy.storage.SnapshotService.flushOnline(s);
			StorageModule.shutdown();
			BalanceService.shutdown();
			server = null;
		});

		LOGGER.info("[{}] ready ({} config files, {} modules)", MOD_ID,
				cfg.definedFiles().size(), modules.all().size());
	}

	public static RedtaleEconomy get() {
		return instance;
	}

	public static String version() {
		return version;
	}

	public static MinecraftServer server() {
		return server;
	}

	/** 供模块注册额外命令时复用同一 dispatcher（例如 /shop admin）。 */
	public static void registerExtraCommands(CommandDispatcher<ServerCommandSource> dispatcher) {
		// 模块命令由 ModuleManager 统一分发，这里留作 1.21.1 双端抽象接缝
	}

	public static Identifier id(String path) {
		return new Identifier(MOD_ID, path);
	}
}
