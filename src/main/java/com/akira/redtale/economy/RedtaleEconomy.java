package com.akira.redtale.economy;

import com.akira.redtale.economy.command.ItemIdArgument;
import com.akira.redtale.economy.command.RedtaleCommands;
import net.fabricmc.fabric.api.command.v2.ArgumentTypeRegistry;
import net.minecraft.command.argument.serialize.ConstantArgumentSerializer;
import net.minecraft.util.Identifier;
import com.akira.redtale.economy.config.ConfigManager;
import com.akira.redtale.economy.config.CoreConfig;
import com.akira.redtale.economy.config.EconomyConfig;
import com.akira.redtale.economy.config.FtbqConfig;
import com.akira.redtale.economy.config.PremiumConfig;
import com.akira.redtale.economy.config.StorageConfig;
import com.akira.redtale.economy.economy.BalanceService;
import com.akira.redtale.economy.lang.Lang;
import com.akira.redtale.economy.util.DevCommandFile;
import com.akira.redtale.economy.module.ModuleManager;
import com.akira.redtale.economy.module.impl.AliasModule;
import com.akira.redtale.economy.module.impl.BattlePassModule;
import com.akira.redtale.economy.module.impl.BoardModule;
import com.akira.redtale.economy.module.impl.BountyModule;
import com.akira.redtale.economy.module.impl.EasyNpcModule;
import com.akira.redtale.economy.module.impl.GuildModule;
import com.akira.redtale.economy.module.impl.MailModule;
import com.akira.redtale.economy.module.impl.TitleModule;
import com.akira.redtale.economy.module.impl.CheckinModule;
import com.akira.redtale.economy.module.impl.EconomyModule;
import com.akira.redtale.economy.module.impl.PremiumModule;
import com.akira.redtale.economy.module.impl.ShopModule;
import com.akira.redtale.economy.module.impl.StorageModule;
import com.akira.redtale.economy.module.impl.TaskModule;
import com.akira.redtale.economy.module.impl.WarehouseModule;
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

		// ★自定义 ArgumentType 必须注册进 Brigadier 的类型表★
		// 否则服务端向客户端下发命令树时抛 Unrecognized argument type，
		// 表现为进游戏报「无效的玩家数据 / Couldn't place player in world」
		ArgumentTypeRegistry.registerArgumentType(
				new Identifier(MOD_ID, "item_id"),
				ItemIdArgument.class,
				ConstantArgumentSerializer.of(ItemIdArgument::itemId));

		FabricLoader.getInstance().getModContainer(MOD_ID)
				.ifPresent(c -> version = c.getMetadata().getVersion().getFriendlyString());

		// 商店终端方块（R24）：站地 + 墙面两个 id，共用一个方块实体
		com.akira.redtale.economy.shop.TerminalRegistry.register();

		// 补签券物品（Q1）：签到补签用，id 可在 checkin.json → makeup.ticketItem 引用
		net.minecraft.registry.Registry.register(net.minecraft.registry.Registries.ITEM,
				new Identifier(MOD_ID, "makeup_ticket"),
				new net.minecraft.item.Item(new net.minecraft.item.Item.Settings().maxCount(16)));
		// 刷新券（R29）：/tasks refresh 次数用完后消耗，也可在商店上架
		net.minecraft.registry.Registry.register(net.minecraft.registry.Registries.ITEM,
				new Identifier(MOD_ID, "refresh_ticket"),
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
		modules.register(new WarehouseModule());
		modules.register(new BountyModule());
		modules.register(new GuildModule());
		modules.register(new TitleModule());
		modules.register(new BattlePassModule());
		modules.register(new BoardModule());
		modules.register(new MailModule());
		modules.register(new EasyNpcModule());
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
			com.akira.redtale.economy.shop.TerminalRegistry.registerCommands(dispatcher);
		});

		// 开发用命令文件通道（config/redtale/devcmd.enabled 存在时才生效）
		DevCommandFile.init();
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK
				.register(DevCommandFile::tick);

		ServerLifecycleEvents.SERVER_STARTED.register(s -> server = s);
		net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.DISCONNECT.register(
				(handler, s) -> ShopNetworking.forget(handler.getPlayer().getUuid()));
		ServerLifecycleEvents.SERVER_STOPPING.register(s -> {
			// R34：关服按配置还原称号（避免留下半套 display_name）
			try {
				TitleModule.restoreAll(s);
			} catch (Exception e) {
				LOGGER.warn("[title] 关服还原失败：{}", e.getMessage());
			}
			// B25 关服顺序：各模块脏数据 → 快照 → 余额落盘 → 流水排空 → 关连接
			// 新模块（仓库/悬赏/协会/战令/邮件/委托板）都是「内存改 + 延迟落盘」，
			// 关服时必须显式排空，否则最后 5 秒内的改动会丢
			try {
				com.akira.redtale.economy.warehouse.WarehouseData.flushAll();
				com.akira.redtale.economy.bounty.BountyState.flushAll();
				com.akira.redtale.economy.guild.GuildData.flushAll();
				com.akira.redtale.economy.mail.MailQueue.flushAll();
				com.akira.redtale.economy.module.impl.BattlePassModule.flushAll();
				com.akira.redtale.economy.board.BoardStore.save();
			} catch (Exception e) {
				LOGGER.warn("[shutdown] 模块落盘失败：{}", e.getMessage());
			}
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
