package com.akira.redtale.economy.module.impl;

import com.akira.redtale.economy.RedtaleEconomy;
import com.akira.redtale.economy.command.TitleCommands;
import com.akira.redtale.economy.config.ConfigManager;
import com.akira.redtale.economy.config.StorageConfig;
import com.akira.redtale.economy.config.TitleConfig;
import com.akira.redtale.economy.module.RedtaleModule;
import com.akira.redtale.economy.util.DataRoot;
import com.akira.redtale.economy.util.PathResolver;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 称号（R34）+ 称号物品（R35）。
 *
 * <p>实证链路（spec §35.10）：称号就是 StyledChat 的 display_name 前缀，
 * 所以这里只做三件事：
 * <ol>
 *   <li>把模板渲染成 display_name，用服务端 4 级权限执行 {@code /styledchat set ...}</li>
 *   <li>把「原始 display_name（我们写入前的值）」和当前称号存到 {@code redtale-data/titles/<uuid>.json}，
 *       随时可以还原（{@code restoreOnDisable} / {@code /titles clear}）</li>
 *   <li>生成带 {@code ItemCommander} NBT 的物品，点一下就能把称号写到使用者身上</li>
 * </ol>
 *
 * <p>provider=internal 或没装 StyledChat 时，只记录不执行命令，聊天名字不变（不报错、不影响玩家）。
 */
public final class TitleModule implements RedtaleModule {

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
	private static final Map<UUID, TitleData> CACHE = new ConcurrentHashMap<>();
	private static Path dir;
	private static volatile boolean hooksInstalled;

	/** 单个玩家的称号记录。 */
	public static final class TitleData {
		public String title = "";
		public String originalDisplayName = "";
		public String updatedAt = "";

		String toJson() {
			JsonObject o = new JsonObject();
			o.addProperty("_version", 1);
			o.addProperty("title", title);
			o.addProperty("originalDisplayName", originalDisplayName);
			o.addProperty("updatedAt", updatedAt);
			return GSON.toJson(o);
		}

		static TitleData from(String json) {
			TitleData d = new TitleData();
			try {
				JsonObject o = GSON.fromJson(json, JsonObject.class);
				if (o == null) return d;
				d.title = o.has("title") ? o.get("title").getAsString() : "";
				d.originalDisplayName = o.has("originalDisplayName") ? o.get("originalDisplayName").getAsString() : "";
				d.updatedAt = o.has("updatedAt") ? o.get("updatedAt").getAsString() : "";
			} catch (Exception e) {
				RedtaleEconomy.LOGGER.warn("[title] 解析失败，按空记录处理：{}", e.getMessage());
			}
			return d;
		}
	}

	@Override
	public String id() {
		return "title";
	}

	@Override
	public void defineConfig(ConfigManager cfg) {
		cfg.define(TitleConfig.FILE, TitleConfig.SCHEMA);
	}

	@Override
	public void onLoad(ConfigManager cfg) {
		resolveDir();
		installHooks();
		if (!styledChatPresent() && "styledchat".equalsIgnoreCase(TitleConfig.provider())) {
			RedtaleEconomy.LOGGER.warn("[title] 未检测到 styledchat，称号只记录不写聊天（等价 provider=internal）");
		}
	}

	@Override
	public void onReload(ConfigManager cfg) {
		resolveDir();
	}

	@Override
	public void registerCommands(CommandDispatcher<ServerCommandSource> dispatcher) {
		TitleCommands.register(dispatcher);
	}

	/** R36：开服后数据目录重绑（{@code DataRoot.onWorldReady} 调用，%world% 这时才可用）。 */
	@Override
	public void rebindDataDir() {
		resolveDir();
	}

	private static void resolveDir() {
		try {
			PathResolver.Resolved r = DataRoot.resolve();
			Path d = r.path().resolve("titles");
			Files.createDirectories(d);
			dir = d;
		} catch (Exception e) {
			dir = null;
			RedtaleEconomy.LOGGER.error("[title] 目录不可用：{}", e.getMessage());
		}
	}

	public static boolean enabled() {
		return com.akira.redtale.economy.module.ModuleManager.get().isEnabled("title")
				&& TitleConfig.enabled();
	}

	public static boolean styledChatPresent() {
		return FabricLoader.getInstance().isModLoaded("styledchat");
	}

	/** 是否真的会去改聊天名。 */
	public static boolean appliesToChat() {
		return enabled() && "styledchat".equalsIgnoreCase(TitleConfig.provider()) && styledChatPresent();
	}

	// ---------------------------------------------------------------- 数据

	public static TitleData data(UUID uuid) {
		TitleData d = CACHE.get(uuid);
		if (d != null) return d;
		d = read(uuid);
		CACHE.put(uuid, d);
		return d;
	}

	private static TitleData read(UUID uuid) {
		Path f = file(uuid);
		if (f == null || !Files.isRegularFile(f)) return new TitleData();
		try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
			JsonObject o = GSON.fromJson(r, JsonObject.class);
			return o == null ? new TitleData() : TitleData.from(GSON.toJson(o));
		} catch (Exception e) {
			RedtaleEconomy.LOGGER.error("[title] 读取 {} 失败：{}", uuid, e.getMessage());
			return new TitleData();
		}
	}

	public static void save(UUID uuid, TitleData d) {
		CACHE.put(uuid, d);
		Path f = file(uuid);
		if (f == null) return;
		Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
		try {
			Files.createDirectories(f.getParent());
			try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
				w.write(d.toJson());
			}
			try {
				Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (IOException atomicFail) {
				Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (IOException e) {
			RedtaleEconomy.LOGGER.error("[title] 写入 {} 失败：{}", uuid, e.getMessage());
		}
	}

	private static Path file(UUID uuid) {
		return dir == null ? null : dir.resolve(uuid + ".json");
	}

	public static String current(UUID uuid) {
		return data(uuid).title;
	}

	// ---------------------------------------------------------------- 设置 / 还原

	/** 渲染 display_name。 */
	public static String render(String title, String playerName) {
		return TitleConfig.displayNameTemplate()
				.replace("{title}", title == null ? "" : title)
				.replace("{player}", playerName == null ? "" : playerName);
	}

	/** 给玩家上称号；返回是否真的改了聊天（false = 只记录）。 */
	public static synchronized boolean setTitle(ServerPlayerEntity player, String title) {
		if (player == null || !enabled()) return false;
		String clean = title == null ? "" : title.trim();
		if (clean.length() > TitleConfig.maxLength()) {
			clean = clean.substring(0, TitleConfig.maxLength());
		}
		TitleData d = data(player.getUuid());
		if (d.originalDisplayName.isEmpty()) {
			// 首次设置：记下「还原时用」的默认 display_name（StyledChat 默认样式）
			d.originalDisplayName = TitleConfig.restoreTemplate();
		}
		d.title = clean;
		d.updatedAt = java.time.LocalDateTime.now()
				.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
		save(player.getUuid(), d);
		if (!appliesToChat()) return false;
		return runStyledChat(player.getGameProfile().getName(), render(clean, player.getGameProfile().getName()));
	}

	/** 清除称号并还原 display_name。 */
	public static synchronized void clearTitle(ServerPlayerEntity player) {
		if (player == null) return;
		TitleData d = data(player.getUuid());
		String restore = d.originalDisplayName.isEmpty() ? TitleConfig.restoreTemplate() : d.originalDisplayName;
		d.title = "";
		d.updatedAt = java.time.LocalDateTime.now()
				.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
		save(player.getUuid(), d);
		if (!appliesToChat()) return;
		runStyledChat(player.getGameProfile().getName(), restore);
	}

	/** 以服务端 4 级权限执行命令；失败只记日志，不抛给调用方。 */
	private static boolean runStyledChat(String playerName, String value) {
		MinecraftServer server = RedtaleEconomy.server();
		if (server == null) return false;
		String cmd = TitleConfig.commandTemplate()
				.replace("{player}", playerName)
				.replace("{value}", value);
		try {
			ServerCommandSource src = server.getCommandSource().withLevel(4).withSilent();
			server.getCommandManager().executeWithPrefix(src, cmd);
			return true;
		} catch (Exception e) {
			RedtaleEconomy.LOGGER.error("[title] 执行称号命令失败：{}", e.getMessage());
			return false;
		}
	}

	// ---------------------------------------------------------------- 称号物品（R35）

	/** 生成一枚绑定该称号的 ItemCommander 物品；失败返回 ItemStack.EMPTY。 */
	public static ItemStack makeItem(String title, String playerName) {
		if (!enabled() || !TitleConfig.itemEnabled()) return ItemStack.EMPTY;
		Item item = resolveItem(TitleConfig.itemId());
		if (item == null) return ItemStack.EMPTY;
		String clean = title == null ? "" : title.trim();
		if (clean.isEmpty()) return ItemStack.EMPTY;
		String value = render(clean, playerName == null ? "@p" : playerName);
		String cmd = TitleConfig.itemCommandTemplate().replace("{value}", value);

		ItemStack stack = new ItemStack(item);
		stack.setCustomName(Text.literal(TitleConfig.itemNamePrefix() + "[" + clean + "]"));
		NbtCompound commander = new NbtCompound();
		commander.putInt("Action", 1);
		commander.putString("Command", cmd);
		commander.putInt("Cooldown", TitleConfig.itemCooldown());
		commander.putInt("Source", TitleConfig.itemSource());
		NbtCompound tag = stack.getOrCreateNbt();
		tag.put("ItemCommander", commander);
		return stack;
	}

	private static Item resolveItem(String id) {
		try {
			Identifier ident = Identifier.tryParse(id);
			if (ident == null || !Registries.ITEM.containsId(ident)) return null;
			return Registries.ITEM.get(ident);
		} catch (Exception e) {
			return null;
		}
	}

	/** 关模块 / 关服时按配置还原在线玩家（避免留下半套 display_name）。 */
	private static void installHooks() {
		if (hooksInstalled) return;
		hooksInstalled = true;
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			// 只清缓存，不改 display_name：玩家下次进来称号还在
			CACHE.remove(handler.getPlayer().getUuid());
		});
	}

	public static void restoreAll(net.minecraft.server.MinecraftServer server) {
		if (!TitleConfig.restoreOnDisable() || server == null) return;
		for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
			try {
				TitleData d = data(p.getUuid());
				if (d.title != null && !d.title.isEmpty()) clearTitle(p);
			} catch (Exception ignored) {
				// 单个失败不影响其余
			}
		}
	}
}
