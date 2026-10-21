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
	/**
	 * 已经成功跑过 {@code onLoad} 的模块。
	 *
	 * <p>热开关要靠它区分「首次启动」和「关掉再打开」：{@code onLoad} 里会建目录、
	 * 挂事件、初始化缓存，同一实例跑两遍不保证幂等（例如 BattlePass 的定时器）。
	 * 二次打开只走 {@code onReload}（各模块已实现，语义是「重读配置」）。
	 */
	private final java.util.Set<String> loadedOnce = new java.util.LinkedHashSet<>();
	private ConfigStore store;
	private ConfigManager cfg;
	/** 命令树句柄（{@code CommandRegistrationCallback} 里记下）；模块热开关后要重发树。 */
	private CommandDispatcher<ServerCommandSource> dispatcher;

	/** 不可关闭的模块：关了货币就没了（T51 规范 §一）。 */
	public static final String ALWAYS_ON = "economy";

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

	/** 在 loadAll 之后调用：解析开关 + 依赖 + 触发 onLoad。 */
	public void bootstrap(ConfigManager cfg) {
		this.cfg = cfg;
		store = cfg.store(FILE);
		resolveStates();
		for (Map.Entry<String, Boolean> e : effective.entrySet()) {
			if (!e.getValue()) continue;
			RedtaleModule m = registry.get(e.getKey());
			try {
				m.onLoad(cfg);
				loadedOnce.add(e.getKey());
			} catch (Exception ex) {
				effective.put(e.getKey(), false);
				reasons.put(e.getKey(), "onLoad 异常：" + ex);
				RedtaleEconomy.LOGGER.error("[module] {} 初始化失败，已停用", e.getKey(), ex);
			}
		}
		logStates();
		// 加载期订阅清单（此刻 net/*Networking 还没注册，不是全量；全量在 ModuleEvents 首次收到事件时打）
		RedtaleEconomy.LOGGER.info("[events] 加载期订阅：{}", ModuleEvents.describe());
	}

	/**
	 * T51：{@code /redtale reload} 的模块侧入口。
	 *
	 * <p>重读 modules.json → 补跑新开启模块的 onLoad → 下线/上线根命令 → 重发命令树。
	 * <b>不再</b>只对「已开启」的模块调 onReload：那会让「文件里改成 true 的模块」
	 * 永远停在关闭状态（旧实现的真缺陷）。
	 */
	public void reload(ConfigManager cfg) {
		this.cfg = cfg;
		store = cfg.store(FILE);
		List<String> notes = applySwitches();
		if (!notes.isEmpty()) RedtaleEconomy.LOGGER.info("[module] reload 应用：{}", String.join("、", notes));
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
		this.dispatcher = dispatcher;
		for (Map.Entry<String, Boolean> e : effective.entrySet()) {
			if (!e.getValue()) continue;
			try {
				registry.get(e.getKey()).registerCommands(dispatcher);
			} catch (Exception ex) {
				RedtaleEconomy.LOGGER.error("[module] {} 命令注册失败", e.getKey(), ex);
			}
		}
	}

	// ------------------------------------------------------------ T51 热开关

	/** 记录命令树句柄（热开关后要重发命令树给在线玩家）。 */
	public void rememberDispatcher(CommandDispatcher<ServerCommandSource> d) {
		if (d != null) this.dispatcher = d;
	}

	public CommandDispatcher<ServerCommandSource> dispatcher() {
		return dispatcher;
	}

	/** 该 id 是否真实存在。 */
	public boolean known(String id) {
		return id != null && registry.containsKey(id);
	}

	/** 该模块是否允许被关闭（{@code economy} 不可关）。 */
	public boolean toggleable(String id) {
		return known(id) && !ALWAYS_ON.equals(id);
	}

	/**
	 * 写 {@code modules.json} 的单个开关（<b>不</b>立即应用）。
	 *
	 * <p>只写配置、不动运行时状态：调用方随后统一走 {@link #applySwitches()}，
	 * 保证「文件 + 运行时 + 命令树 + 在线玩家」在同一刻对齐 —— 分开做会出现
	 * 「配置已经 false 但命令还在」的半开半关窗口。
	 */
	public boolean writeSwitch(String id, boolean on) {
		if (store == null || !known(id)) return false;
		store.setPath("modules." + id, on);
		return store.save(false);
	}

	/**
	 * 应用当前 {@code modules.json}：解析状态 → 新开启的模块补跑 onLoad →
	 * 下线/上线根命令 → 重发命令树给在线玩家。
	 *
	 * <p>关闭的模块<b>不</b>清数据：{@code onLoad} 里建的目录/文件原样保留，
	 * 重新打开即恢复（T51 规范「数据保留」）。
	 */
	public List<String> applySwitches() {
		List<String> notes = new ArrayList<>();
		Map<String, Boolean> before = new LinkedHashMap<>(effective);
		resolveStates();
		if (cfg != null) {
			for (Map.Entry<String, Boolean> e : effective.entrySet()) {
				boolean was = Boolean.TRUE.equals(before.get(e.getKey()));
				if (!e.getValue() || was) continue;
				// 关 → 开：首次运行才补 onLoad；此前跑过的只重读配置（避免二次 onLoad 建重复定时器）
				try {
					if (loadedOnce.contains(e.getKey())) {
						registry.get(e.getKey()).onReload(cfg);
						notes.add("恢复 " + e.getKey());
					} else {
						registry.get(e.getKey()).onLoad(cfg);
						loadedOnce.add(e.getKey());
						notes.add("启动 " + e.getKey());
					}
				} catch (Exception ex) {
					effective.put(e.getKey(), false);
					reasons.put(e.getKey(), "onLoad 异常：" + ex);
					notes.add("启动失败 " + e.getKey());
					RedtaleEconomy.LOGGER.error("[module] {} 热启动失败", e.getKey(), ex);
				}
			}
		}
		syncCommands(notes);
		logStates();
		return notes;
	}

	/** 按声明表把「不该存在的根命令」摘掉、把「该存在但不在的」补注册。 */
	private void syncCommands(List<String> notes) {
		if (dispatcher == null) return;
		boolean changed = false;
		for (com.akira.redtale.economy.command.CommandTable.Entry e
				: com.akira.redtale.economy.command.CommandTable.entries()) {
			// ★只处理归属是「真实模块」的根命令★：core（/redtale /rt）、terminal 等
			// 不是模块，按 id 查 isEnabled 会一律得到 false —— 第一版就是这么把
			// /redtale 和 /rt 一起摘掉的（关 shop 连带管理员自己都没命令可用）。
			if (!known(e.owner()) || ALWAYS_ON.equals(e.owner())) continue;
			boolean want = isEnabled(e.owner());
			boolean has = CommandSync.hasRoot(dispatcher, e.name());
			if (!want && has) {
				if (CommandSync.removeRoot(dispatcher, e.name())) {
					notes.add("下线 /" + e.name());
					changed = true;
				}
			}
		}
		// 补注册在摘除之后做：registerCommands 内部是「合并」，对已存在的根无害
		for (Map.Entry<String, Boolean> e : effective.entrySet()) {
			if (!e.getValue()) continue;
			try {
				registry.get(e.getKey()).registerCommands(dispatcher);
			} catch (Exception ex) {
				RedtaleEconomy.LOGGER.error("[module] {} 命令重注册失败", e.getKey(), ex);
			}
		}
		if (changed) {
			// 只是记账：真正「摘不掉」的情况由 CommandSync 自己打 WARN，这里不重复判
			notes.add("命令树已重发");
		}
		broadcastCommandTree();
	}

	/** 命令树变了必须重发，否则客户端 Tab 补全/「未知命令」判定还是旧的（要重连才更新）。 */
	private void broadcastCommandTree() {
		var server = currentServer();
		if (server == null) return;
		try {
			for (var p : server.getPlayerManager().getPlayerList()) {
				server.getCommandManager().sendCommandTree(p);
			}
		} catch (Exception ex) {
			RedtaleEconomy.LOGGER.warn("[module] 重发命令树失败：{}", ex.toString());
		}
	}

	/** 运行中的服务端（没有则 null）：热开关只在有服时才有「重发」的对象。 */
	private static net.minecraft.server.MinecraftServer currentServer() {
		try {
			return RedtaleEconomy.server();
		} catch (Throwable t) {
			return null;
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
			// T51：economy 不可关 —— 关掉它的实例里货币/账户全都没了，而 accounts.json 还在，
			// 下次打开会读回一堆「幽灵余额」。配置写 false 也只 WARN，不照做。
			if (!on && ALWAYS_ON.equals(m.id())) {
				on = true;
				RedtaleEconomy.LOGGER.warn("[module] {} 被配置为 false，但它是不可关闭模块，已按 true 处理", m.id());
			}
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
