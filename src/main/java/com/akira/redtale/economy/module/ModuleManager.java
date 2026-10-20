package com.akira.redtale.economy.module;

import com.akira.redtale.economy.RedtaleEconomy;
import com.akira.redtale.economy.config.ConfigManager;
import com.akira.redtale.economy.config.ConfigSchema;
import com.akira.redtale.economy.config.ConfigStore;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.server.command.ServerCommandSource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 模块注册表 + {@code modules.json} 开关。
 *
 * <p>注册顺序即加载顺序；{@code requires} 在解析阶段求值，
 * 依赖不满足的模块会被强制关闭并在日志里说明原因 —— 不做静默降级。
 */
public final class ModuleManager {

	public static final String FILE = "modules.json";

	private static ModuleManager instance;

	private final Map<String, RedtaleModule> registry = new LinkedHashMap<>();
	private final Map<String, Boolean> effective = new LinkedHashMap<>();
	private final Map<String, String> reasons = new LinkedHashMap<>();
	private ConfigStore store;

	private ModuleManager() {
	}

	public static ModuleManager get() {
		if (instance == null) instance = new ModuleManager();
		return instance;
	}

	public void register(RedtaleModule module) {
		if (registry.containsKey(module.id())) {
			RedtaleEconomy.LOGGER.error("[module] 重复注册 {}，后者被忽略", module.id());
			return;
		}
		registry.put(module.id(), module);
	}

	public List<RedtaleModule> all() {
		return new ArrayList<>(registry.values());
	}

	public RedtaleModule get(String id) {
		return registry.get(id);
	}

	public boolean isEnabled(String id) {
		return Boolean.TRUE.equals(effective.get(id));
	}

	public String reason(String id) {
		return reasons.getOrDefault(id, "");
	}

	/** 在 loadAll 之前调用：登记各模块配置 + 生成 modules.json 的 schema。 */
	public void defineAll(ConfigManager cfg) {
		for (RedtaleModule m : registry.values()) {
			m.defineConfig(cfg);
		}
		ConfigSchema modules = ConfigSchema.obj("模块总开关：false 的模块不加载、不注册命令、不显示入口")
				.field("modules", buildModulesSchema());
		cfg.define(FILE, modules);
	}

	private ConfigSchema buildModulesSchema() {
		ConfigSchema obj = ConfigSchema.obj("模块 id → 是否启用（改完 /redtale reload 生效）");
		for (RedtaleModule m : registry.values()) {
			obj.field(m.id(), ConfigSchema.bool(m.defaultEnabled(), "模块 " + m.id() + "（默认 " + m.defaultEnabled() + "）"));
		}
		return obj;
	}

	/** 配置加载后调用：解析开关 + 依赖 + 触发 onLoad。 */
	public void bootstrap(ConfigManager cfg) {
		store = cfg.store(FILE);
		resolveStates();
		for (Map.Entry<String, Boolean> e : effective.entrySet()) {
			if (!e.getValue()) continue;
			RedtaleModule m = registry.get(e.getKey());
			try {
				m.onLoad(cfg);
			} catch (Exception ex) {
				effective.put(e.getKey(), false);
				reasons.put(e.getKey(), "onLoad 异常：" + ex);
				RedtaleEconomy.LOGGER.error("[module] {} 初始化失败，已停用", e.getKey(), ex);
			}
		}
		logStates();
	}

	public void reload(ConfigManager cfg) {
		resolveStates();
		for (Map.Entry<String, Boolean> e : effective.entrySet()) {
			if (!e.getValue()) continue;
			try {
				registry.get(e.getKey()).onReload(cfg);
			} catch (Exception ex) {
				RedtaleEconomy.LOGGER.error("[module] {} 重载失败", e.getKey(), ex);
			}
		}
	}

	/** R36：存档就绪后让各模块重新解析数据目录（{@code %world%} 这时才可用）。 */
	public void rebindDataDirs() {
		for (Map.Entry<String, Boolean> e : effective.entrySet()) {
			if (!e.getValue()) continue;
			try {
				registry.get(e.getKey()).rebindDataDir();
			} catch (Exception ex) {
				RedtaleEconomy.LOGGER.error("[module] {} 数据目录重绑失败：{}", e.getKey(), ex.getMessage());
			}
		}
	}

	public void registerCommands(CommandDispatcher<ServerCommandSource> dispatcher) {
		for (Map.Entry<String, Boolean> e : effective.entrySet()) {
			if (!e.getValue()) continue;
			try {
				registry.get(e.getKey()).registerCommands(dispatcher);
			} catch (Exception ex) {
				RedtaleEconomy.LOGGER.error("[module] {} 命令注册失败", e.getKey(), ex);
			}
		}
	}

	private void resolveStates() {
		effective.clear();
		reasons.clear();
		if (store == null) return;
		var mods = store.getObject("modules");

		for (RedtaleModule m : registry.values()) {
			boolean on = !mods.has(m.id())
					|| (mods.get(m.id()).isJsonPrimitive() && mods.get(m.id()).getAsJsonPrimitive().isBoolean()
							&& mods.get(m.id()).getAsBoolean());
			effective.put(m.id(), on);
			if (!on) reasons.put(m.id(), "modules.json 中关闭");
		}
		// 依赖解析：最多 3 轮，够处理链式依赖
		for (int round = 0; round < 3; round++) {
			boolean changed = false;
			for (RedtaleModule m : registry.values()) {
				if (!isEnabled(m.id())) continue;
				for (String dep : m.requires()) {
					if (!registry.containsKey(dep)) {
						effective.put(m.id(), false);
						reasons.put(m.id(), "缺少依赖模块 " + dep);
						changed = true;
						break;
					}
					if (!isEnabled(dep)) {
						effective.put(m.id(), false);
						reasons.put(m.id(), "依赖 " + dep + " 已关闭");
						changed = true;
						break;
					}
				}
			}
			if (!changed) break;
		}
	}

	private void logStates() {
		StringBuilder sb = new StringBuilder();
		for (RedtaleModule m : registry.values()) {
			sb.append(m.id()).append(isEnabled(m.id()) ? "=on" : "=off");
			if (!isEnabled(m.id())) sb.append("(").append(reason(m.id())).append(")");
			sb.append(" ");
		}
		RedtaleEconomy.LOGGER.info("[module] 状态：{}", sb.toString().trim());
	}
}
