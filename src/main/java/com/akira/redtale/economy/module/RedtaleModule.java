package com.akira.redtale.economy.module;

import com.akira.redtale.economy.config.ConfigManager;
import net.minecraft.server.command.ServerCommandSource;
import com.mojang.brigadier.CommandDispatcher;

import java.util.List;

/**
 * 功能模块统一接口（spec 9.1）。
 *
 * <p>加新功能（拍卖行、银行、战令）= 新增一个实现 + 在 {@code ModuleManager} 注册，
 * 不改核心代码。关掉的模块：不读配置、不注册命令、不发包、不给客户端入口。
 */
public interface RedtaleModule {

	/** 唯一 id，对应 modules.json 里的键，也用于权限 / 语言 key 前缀。 */
	String id();

	default boolean defaultEnabled() {
		return true;
	}

	/** 依赖的模块 id；被依赖项关闭时本模块强制关闭。 */
	default List<String> requires() {
		return List.of();
	}

	/** 声明本模块的配置文件与 schema（在 loadAll 之前调用）。 */
	default void defineConfig(ConfigManager cfg) {
	}

	/** 配置已加载，做初始化（注册物品 / 缓存解析结果等）。 */
	default void onLoad(ConfigManager cfg) {
	}

	/** 热重载：只重算数值，不重建注册期资源。 */
	default void onReload(ConfigManager cfg) {
	}

	/**
	 * 数据目录重绑（R36）：{@code %world%} 在模组加载期不可用，模块 {@code onLoad} 里解析到的是
	 * 暂存目录 {@code redtale-data.pending}；服务器起来后 {@code DataRoot.onWorldReady} 会回调这里，
	 * 实现方重新解析目录并把内存里的数据从新目录重读一遍。
	 */
	default void rebindDataDir() {
	}

	/** 注册命令；模块关闭时不会被调用。 */
	default void registerCommands(CommandDispatcher<ServerCommandSource> dispatcher) {
	}
}
