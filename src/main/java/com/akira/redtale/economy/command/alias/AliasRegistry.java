package com.akira.redtale.economy.command.alias;

import com.akira.redtale.economy.RedtaleEconomy;
import com.akira.redtale.economy.config.ConfigManager;
import com.akira.redtale.economy.config.ConfigSchema;
import com.akira.redtale.economy.config.ConfigStore;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.LiteralCommandNode;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 命令别名（spec v7.2）。
 *
 * <p>别名用 {@link CommandNode#redirect} 指向目标命令节点，因此<b>权限仍按目标命令判定</b>，
 * 不会出现「别名绕过权限」。
 *
 * <p>安全边界：
 * <ul>
 *   <li>{@code blockedCommands} 最高优先级，先于一切判断</li>
 *   <li>{@code allowNonModCommands=false} 时只允许 {@code redtale_economy:} 前缀</li>
 *   <li>循环别名（A→B→A）在注册期检测并跳过</li>
 * </ul>
 */
public final class AliasRegistry {

	public static final String FILE = "commands.json";

	private static final ConfigSchema ALIAS = ConfigSchema.obj("一条别名")
			.field("name", ConfigSchema.string("qd", "别名（不含斜杠）"))
			.field("target", ConfigSchema.string("redtale_economy:checkin",
					"目标命令，格式 ns:path，可用 a/b 表示子命令"));

	public static final ConfigSchema SCHEMA = ConfigSchema.obj("命令别名")
			.field("allowNonModCommands", ConfigSchema.bool(false,
					"是否允许指向其它模组/原版命令（默认关闭；关闭时只接受 redtale_economy: 前缀）"))
			.field("blockedCommands", ConfigSchema.stringList(
					List.of("minecraft:op", "minecraft:stop", "minecraft:deop"),
					"黑名单（优先级最高），即使 allowNonModCommands=true 也会被拦"))
			.field("maxChainDepth", ConfigSchema.integer(3, 1, 10, "别名链最大解析深度"))
			.field("aliases", ConfigSchema.list(new com.google.gson.JsonArray(), ALIAS, "别名列表"));

	private AliasRegistry() {
	}

	public static ConfigStore store() {
		return ConfigManager.get().store(FILE);
	}

	/** 在模块命令注册完成后调用（dispatcher 已包含本 mod 的命令）。 */
	public static void registerAll(CommandDispatcher<ServerCommandSource> dispatcher) {
		List<Map.Entry<String, String>> aliases = read();
		if (aliases.isEmpty()) return;

		Set<String> names = new HashSet<>();
		for (Map.Entry<String, String> e : aliases) names.add(e.getKey());

		int ok = 0;
		for (Map.Entry<String, String> e : aliases) {
			String name = e.getKey();
			String target = e.getValue();

			if (!name.matches("[a-z0-9_-]{1,32}")) {
				RedtaleEconomy.LOGGER.error("[alias] 别名 \"{}\" 不合法，跳过", name);
				continue;
			}
			if (isBlocked(target)) {
				RedtaleEconomy.LOGGER.error("[alias] 目标 {} 在黑名单内，跳过别名 {}", target, name);
				continue;
			}
			String ns = namespace(target);
			if (!"redtale_economy".equals(ns) && !allowNonMod()) {
				RedtaleEconomy.LOGGER.warn("[alias] 目标 {} 非本 mod 命令且 allowNonModCommands=false，跳过别名 {}", target, name);
				continue;
			}
			if (hasCycle(name, target, aliases)) {
				RedtaleEconomy.LOGGER.error("[alias] 别名 {} → {} 存在循环，跳过", name, target);
				continue;
			}

			CommandNode<ServerCommandSource> node = resolve(dispatcher, target);
			if (node == null) {
				RedtaleEconomy.LOGGER.warn("[alias] 找不到目标命令 {}（别名 {} 未注册）", target, name);
				continue;
			}
			if (dispatcher.getRoot().getChild(name) != null) {
				RedtaleEconomy.LOGGER.warn("[alias] 名称 {} 已被占用，跳过", name);
				continue;
			}
			LiteralCommandNode<ServerCommandSource> alias = CommandManager.literal(name).redirect(node).build();
			dispatcher.getRoot().addChild(alias);
			ok++;
		}
		RedtaleEconomy.LOGGER.info("[alias] 已注册 {} / {} 条别名", ok, aliases.size());
	}

	private static List<Map.Entry<String, String>> read() {
		List<Map.Entry<String, String>> out = new ArrayList<>();
		var root = store().root();
		if (!root.has("aliases") || !root.get("aliases").isJsonArray()) return out;
		for (var el : root.getAsJsonArray("aliases")) {
			if (!el.isJsonObject()) continue;
			var o = el.getAsJsonObject();
			if (!o.has("name") || !o.has("target")) continue;
			String name = o.get("name").getAsString().trim().toLowerCase();
			String target = o.get("target").getAsString().trim().toLowerCase();
			if (name.isEmpty() || target.isEmpty()) continue;
			out.add(Map.entry(name, target));
		}
		return out;
	}

	private static boolean allowNonMod() {
		return store().getBool("allowNonModCommands", false);
	}

	private static boolean isBlocked(String target) {
		List<String> blocked = new ArrayList<>();
		var root = store().root();
		if (root.has("blockedCommands") && root.get("blockedCommands").isJsonArray()) {
			for (var el : root.getAsJsonArray("blockedCommands")) {
				if (el.isJsonPrimitive()) blocked.add(el.getAsString().toLowerCase());
			}
		}
		if (blocked.contains(target)) return true;
		// 同时按命令路径判定，防止 "minecraft:op/xxx" 这种绕过
		for (String b : blocked) {
			if (path(target).equals(b)) return true;
			if (b.endsWith(":" + path(target))) return true;
		}
		return false;
	}

	private static String namespace(String target) {
		int idx = target.indexOf(':');
		return idx < 0 ? "minecraft" : target.substring(0, idx);
	}

	private static String path(String target) {
		int idx = target.indexOf(':');
		return idx < 0 ? target : target.substring(idx + 1);
	}

	/** 沿 target 路径找到命令节点；找不到返回 null。 */
	@SuppressWarnings("unchecked")
	private static CommandNode<ServerCommandSource> resolve(CommandDispatcher<ServerCommandSource> dispatcher, String target) {
		String p = path(target);
		if (p.isEmpty()) return null;
		CommandNode<ServerCommandSource> node = dispatcher.getRoot();
		for (String part : p.split("/")) {
			node = node.getChild(part);
			if (node == null) return null;
		}
		return node;
	}

	/**
	 * 循环检测：从 target 出发，如果别名名再次出现即成环。
	 * 只跟别名表内的名字走，目标为真实命令则终止。
	 */
	private static boolean hasCycle(String start, String target, List<Map.Entry<String, String>> aliases) {
		Map<String, String> map = new HashMap<>();
		for (Map.Entry<String, String> e : aliases) map.put(e.getKey(), e.getValue());

		int max = (int) store().getLong("maxChainDepth", 3);
		Set<String> seen = new HashSet<>();
		seen.add(start);
		String cur = path(target);
		for (int i = 0; i < max; i++) {
			if (!map.containsKey(cur)) return false;       // 到达真实命令
			if (!seen.add(cur)) return true;                // 回到已访问节点
			cur = path(map.get(cur));
		}
		return false;
	}
}
