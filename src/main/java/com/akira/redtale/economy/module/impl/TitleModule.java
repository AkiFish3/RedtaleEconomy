package com.akira.redtale.economy.module.impl;

import com.akira.redtale.economy.module.ModuleEvents;
import com.akira.redtale.economy.RedtaleEconomy;
import com.akira.redtale.economy.command.TitleCommands;
import com.akira.redtale.economy.config.ConfigManager;
import com.akira.redtale.economy.config.TitleConfig;
import com.akira.redtale.economy.module.RedtaleModule;
import com.akira.redtale.economy.util.DataRoot;
import com.akira.redtale.economy.util.PathResolver;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.mojang.brigadier.CommandDispatcher;
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
		/** 兼容 v1/v2：等价的 {@code equipped[0]}（写入时同步，读取时用于迁移）。 */
		public String title = "";
		/** T60：已装备的称号（有序，下标 0 最靠前，最多 {@link TitleSlots#MAX_SLOTS} 个）。 */
		public java.util.List<String> equipped = new java.util.ArrayList<>();
		/** T60：该玩家的槽位数；{@code <=0} = 用配置 {@code slots.default}。 */
		public int slots = -1;
		/** T66：当前生效的强制称号 id（不占槽位，由管理员激活）。 */
		public java.util.List<String> forced = new java.util.ArrayList<>();
		/** T23：后缀（可与前缀同时存在）。 */
		public String suffix = "";
		/** T23：起始色（0xRRGGBB）。 */
		public int color1 = TitleStyle.GOLD;
		/** T23：渐变终止色。 */
		public int color2 = TitleStyle.VIOLET;
		/** T23：是否渐变（false=纯起始色）。 */
		public boolean gradient = false;
		public String originalDisplayName = "";
		public String updatedAt = "";
		/** 已拥有的称号（去重、保序）。当前生效的那些一定在里面。 */
		public java.util.List<String> owned = new java.util.ArrayList<>();

		/** 让 {@code title} 与 {@code equipped[0]} 保持一致（v2 兼容字段）。 */
		void syncLegacy() {
			equipped = TitleSlots.compact(equipped);
			title = equipped.isEmpty() ? "" : equipped.get(0);
			for (String t : equipped) {
				if (!owned.contains(t)) owned.add(t);
			}
		}

		String toJson() {
			syncLegacy();
			JsonObject o = new JsonObject();
			o.addProperty("_version", 3);
			o.addProperty("title", title);
			com.google.gson.JsonArray eq = new com.google.gson.JsonArray();
			for (String t : equipped) eq.add(t);
			o.add("equipped", eq);
			o.addProperty("slots", slots);
			com.google.gson.JsonArray fo = new com.google.gson.JsonArray();
			for (String t : forced) fo.add(t);
			o.add("forced", fo);
			o.addProperty("suffix", suffix);
			o.addProperty("color1", TitleStyle.hex(color1));
			o.addProperty("color2", TitleStyle.hex(color2));
			o.addProperty("gradient", gradient);
			o.addProperty("originalDisplayName", originalDisplayName);
			o.addProperty("updatedAt", updatedAt);
			com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
			for (String t : owned) arr.add(t);
			o.add("owned", arr);
			return GSON.toJson(o);
		}

		static TitleData from(String json) {
			TitleData d = new TitleData();
			try {
				JsonObject o = GSON.fromJson(json, JsonObject.class);
				if (o == null) return d;
				d.title = o.has("title") ? o.get("title").getAsString() : "";
				if (o.has("equipped") && o.get("equipped").isJsonArray()) {
					for (var e : o.getAsJsonArray("equipped")) {
						if (e.isJsonPrimitive()) {
							String t = e.getAsString();
							if (!t.isBlank() && !d.equipped.contains(t)) d.equipped.add(t);
						}
					}
				}
				// v2 迁移：老存档只有 title ⇒ 当成唯一已装备槽
				if (d.equipped.isEmpty() && !d.title.isBlank()) d.equipped.add(d.title);
				d.slots = o.has("slots") && o.get("slots").isJsonPrimitive() ? o.get("slots").getAsInt() : -1;
				if (o.has("forced") && o.get("forced").isJsonArray()) {
					for (var e : o.getAsJsonArray("forced")) {
						if (e.isJsonPrimitive()) {
							String t = e.getAsString();
							if (!t.isBlank() && !d.forced.contains(t)) d.forced.add(t);
						}
					}
				}
				d.suffix = o.has("suffix") ? o.get("suffix").getAsString() : "";
				d.color1 = TitleStyle.parse(o.has("color1") ? o.get("color1").getAsString() : null,
						TitleStyle.GOLD);
				d.color2 = TitleStyle.parse(o.has("color2") ? o.get("color2").getAsString() : null,
						TitleStyle.VIOLET);
				d.gradient = o.has("gradient") && o.get("gradient").isJsonPrimitive()
						&& o.get("gradient").getAsJsonPrimitive().isBoolean()
						&& o.get("gradient").getAsBoolean();
				d.originalDisplayName = o.has("originalDisplayName") ? o.get("originalDisplayName").getAsString() : "";
				d.updatedAt = o.has("updatedAt") ? o.get("updatedAt").getAsString() : "";
				if (o.has("owned") && o.get("owned").isJsonArray()) {
					for (var e : o.getAsJsonArray("owned")) {
						if (e.isJsonPrimitive()) {
							String t = e.getAsString();
							if (!t.isBlank() && !d.owned.contains(t)) d.owned.add(t);
						}
					}
				}
				for (String t : d.equipped) {
					if (!d.owned.contains(t)) d.owned.add(t);
				}
				if (!d.title.isBlank() && !d.owned.contains(d.title)) d.owned.add(d.title);
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

	/** 已拥有称号列表（含当前生效的）。 */
	public static java.util.List<String> owned(UUID uuid) {
		TitleData d = data(uuid);
		if (!d.title.isBlank() && !d.owned.contains(d.title)) d.owned.add(d.title);
		return java.util.List.copyOf(d.owned);
	}

	/** 授予称号：加入拥有列表，并立即生效。 */
	public static synchronized boolean grant(ServerPlayerEntity player, String title) {
		if (player == null || !enabled()) return false;
		String clean = title == null ? "" : title.trim();
		if (clean.isEmpty()) return false;
		TitleData d = data(player.getUuid());
		if (!d.owned.contains(clean)) d.owned.add(clean);
		save(player.getUuid(), d);
		return setTitle(player, clean);
	}

	/** 从拥有列表移除（正在装备的槽位一起卸下）。 */
	public static synchronized void revoke(ServerPlayerEntity player, String title) {
		if (player == null) return;
		TitleData d = data(player.getUuid());
		d.owned.remove(title);
		boolean wasEquipped = d.equipped.contains(title);
		d.equipped = TitleSlots.unequip(d.equipped, title);
		d.syncLegacy();
		save(player.getUuid(), d);
		if (wasEquipped) apply(player);
	}

	// ---------------------------------------------------------------- 设置 / 还原

	/** 一段待渲染的称号（T60 多槽位 / T66 强制称号统一成这个形态）。 */
	public record Part(String text, boolean gradient, int c1, int c2) {
	}

	/** 该玩家实际可用的槽位数（T60）。 */
	public static int slotsOf(TitleData d) {
		int n = d == null ? -1 : d.slots;
		int max = TitleConfig.slotMax();
		if (n <= 0) return Math.min(TitleConfig.slotDefault(), max);
		return Math.min(n, max);
	}

	/** 已装备（归一化后）。 */
	public static java.util.List<String> equipped(UUID uuid) {
		TitleData d = data(uuid);
		return TitleSlots.normalize(d.equipped, TitleSlots.MAX_SLOTS);
	}

	/**
	 * T66：把记录展开成「实际要显示的若干段」。
	 *
	 * <p>强制称号按 {@code order} 排在前面，**不占玩家槽位**；任一条 {@code override=true}
	 * 就整段顶替玩家自选的那几槽。
	 */
	public static java.util.List<Part> effectiveParts(TitleData d) {
		java.util.List<Part> out = new java.util.ArrayList<>();
		if (d == null) return out;
		boolean override = false;
		java.util.List<TitleConfig.Forced> active = new java.util.ArrayList<>();
		for (String id : d.forced) {
			TitleConfig.Forced f = TitleConfig.forcedById(id);
			if (f == null) continue;
			active.add(f);
			if (f.override()) override = true;
		}
		active.sort(java.util.Comparator.comparingInt(TitleConfig.Forced::order));
		for (TitleConfig.Forced f : active) {
			out.add(new Part(f.text(), f.gradient(), f.color1(), f.color2()));
		}
		if (!override) {
			for (String t : TitleSlots.normalize(d.equipped, slotsOf(d))) {
				out.add(new Part(t, d.gradient, d.color1, d.color2));
			}
		}
		return out;
	}

	/** 渲染 display_name（旧签名，纯文本前缀 + 默认配色；命令/物品仍走它）。 */
	public static String render(String title, String playerName) {
		return render(title, "", playerName, false, TitleStyle.GOLD, TitleStyle.VIOLET);
	}

	/**
	 * 渲染 display_name（T23，单段）。
	 *
	 * <p>{@code {title}} 注入带颜色标签的前缀，{@code {suffix}} 注入「[后缀]」块（没有则空串）。
	 * {@code styling.enabled=false} 时两者都注入纯文本 ⇒ 聊天里看不出样式，但界面预览仍有颜色。
	 */
	public static String render(String prefix, String suffix, String playerName,
			boolean gradient, int color1, int color2) {
		return render(java.util.List.of(new Part(prefix == null ? "" : prefix, gradient, color1, color2)),
				suffix, playerName);
	}

	/**
	 * 渲染 display_name（T60/T66：多段 + 强制）。
	 *
	 * @param parts 有序的若干段（{@link #effectiveParts} 的产物），用 {@code slots.separator} 拼在同一对
	 *              {@code []} 里 —— 模板只需要一个 {@code {title}} 占位符
	 */
	public static String render(java.util.List<Part> parts, String suffix, String playerName) {
		boolean chat = TitleConfig.stylingEnabled();
		StringBuilder inner = new StringBuilder();
		int c1 = TitleStyle.GOLD;
		int c2 = TitleStyle.VIOLET;
		boolean grad = false;
		boolean first = true;
		if (parts != null) {
			for (Part p : parts) {
				if (p == null || p.text() == null || p.text().isEmpty()) continue;
				if (first) {
					grad = p.gradient();
					c1 = p.c1();
					c2 = p.c2();
					first = false;
				}
				if (inner.length() > 0) {
					inner.append("<white>").append(TitleConfig.slotSeparator()).append("</white>");
				}
				inner.append(chat ? TitleStyle.chat(p.text(), p.gradient() && TitleConfig.chatGradient(),
						p.c1(), p.c2()) : TitleStyle.escape(p.text()));
			}
		}
		boolean g2 = grad && TitleConfig.chatGradient();
		String s = suffix == null ? "" : suffix;
		String suffixBlock = s.isEmpty() ? ""
				: " <white>[</white>" + (chat ? TitleStyle.chat(s, g2, c1, c2) : TitleStyle.escape(s))
						+ "<white>]</white>";
		return TitleConfig.displayNameTemplate()
				.replace("{title}", inner.toString())
				.replace("{suffix}", suffixBlock)
				.replace("{player}", playerName == null ? "" : playerName);
	}

	/** 用记录里的样式渲染（没有可显示的段时返回空串，由调用方决定还原）。 */
	public static String render(TitleData d, String playerName) {
		java.util.List<Part> parts = effectiveParts(d);
		if (parts.isEmpty()) return "";
		return render(parts, d == null ? "" : d.suffix, playerName);
	}

	/** 把当前记录重新写进聊天名（没有可显示的段 ⇒ 还原）。 */
	public static synchronized void apply(ServerPlayerEntity player) {
		if (player == null) return;
		TitleData d = data(player.getUuid());
		d.syncLegacy();
		if (effectiveParts(d).isEmpty()) {
			clearTitle(player);
			return;
		}
		d.updatedAt = now();
		save(player.getUuid(), d);
		if (!appliesToChat()) return;
		runStyledChat(player.getGameProfile().getName(), render(d, player.getGameProfile().getName()));
	}

	/** 设置后缀（空串 = 清除）；前缀未装备时不改聊天（只存）。 */
	public static synchronized boolean setSuffix(ServerPlayerEntity player, String suffix) {
		if (player == null || !enabled()) return false;
		String clean = suffix == null ? "" : suffix.trim();
		int max = TitleConfig.maxSuffixLength();
		if (clean.length() > max) clean = clean.substring(0, max);
		TitleData d = data(player.getUuid());
		d.suffix = clean;
		save(player.getUuid(), d);
		if (d.title.isBlank()) return false;
		apply(player);
		return appliesToChat();
	}

	/** 设置颜色（gradient=true 时 c2 生效）。 */
	public static synchronized boolean setStyle(ServerPlayerEntity player,
			boolean gradient, int color1, int color2) {
		if (player == null || !enabled()) return false;
		TitleData d = data(player.getUuid());
		d.gradient = gradient;
		d.color1 = color1 & 0xFFFFFF;
		d.color2 = color2 & 0xFFFFFF;
		save(player.getUuid(), d);
		if (d.title.isBlank()) return false;
		apply(player);
		return appliesToChat();
	}

	private static String now() {
		return java.time.LocalDateTime.now()
				.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
	}

	/** 给玩家上称号（单槽语义：清空所有槽后只装这一个）；返回是否真的改了聊天。 */
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
		d.equipped.clear();
		if (!clean.isEmpty()) d.equipped.add(clean);
		d.syncLegacy();
		d.updatedAt = now();
		save(player.getUuid(), d);
		if (!appliesToChat()) return false;
		return runStyledChat(player.getGameProfile().getName(), render(d, player.getGameProfile().getName()));
	}

	// ---------------------------------------------------------------- T60 多槽位

	/**
	 * 装备到某个槽位（{@code slot<0} = 追加）。
	 *
	 * <p>槽位满时替换最后一个；槽位数由 {@link #slotsOf} 决定，未解锁的槽装了也显示不出来
	 * ⇒ 这里直接按可用槽位截断，避免「界面上点了、聊天里没有」。
	 */
	public static synchronized boolean equip(ServerPlayerEntity player, String title, int slot) {
		if (player == null || !enabled()) return false;
		String clean = title == null ? "" : title.trim();
		if (clean.isEmpty()) return false;
		if (clean.length() > TitleConfig.maxLength()) clean = clean.substring(0, TitleConfig.maxLength());
		TitleData d = data(player.getUuid());
		if (!d.owned.contains(clean)) d.owned.add(clean);
		int n = slotsOf(d);
		int useSlot = slot < 0 ? -1 : Math.max(0, Math.min(slot, n - 1));
		d.equipped = TitleSlots.normalize(TitleSlots.equip(d.equipped, clean, useSlot), n);
		d.syncLegacy();
		save(player.getUuid(), d);
		apply(player);
		return appliesToChat();
	}

	/** 卸下第 {@code index} 个槽（越界无操作）。 */
	public static synchronized boolean unequipSlot(ServerPlayerEntity player, int index) {
		if (player == null || !enabled()) return false;
		TitleData d = data(player.getUuid());
		d.equipped = TitleSlots.unequip(d.equipped, index);
		d.syncLegacy();
		save(player.getUuid(), d);
		apply(player);
		return appliesToChat();
	}

	/** 卸下某个称号（不论在哪个槽）。 */
	public static synchronized boolean unequip(ServerPlayerEntity player, String title) {
		if (player == null || !enabled() || title == null) return false;
		TitleData d = data(player.getUuid());
		d.equipped = TitleSlots.unequip(d.equipped, title);
		d.syncLegacy();
		save(player.getUuid(), d);
		apply(player);
		return appliesToChat();
	}

	/** 设置该玩家的槽位数（1..slots.max），{@code <=0} = 回到配置默认。 */
	public static synchronized int setSlots(ServerPlayerEntity player, int n) {
		if (player == null || !enabled()) return 0;
		TitleData d = data(player.getUuid());
		d.slots = n <= 0 ? -1 : Math.min(n, TitleConfig.slotMax());
		d.equipped = TitleSlots.normalize(d.equipped, slotsOf(d));
		d.syncLegacy();
		save(player.getUuid(), d);
		apply(player);
		return slotsOf(d);
	}

	// ---------------------------------------------------------------- T66 强制称号

	/** 激活/取消一条强制称号（{@code id=null|"-"} = 全部取消）。返回实际生效的 id 列表。 */
	public static synchronized java.util.List<String> setForced(ServerPlayerEntity player, String id) {
		if (player == null || !enabled()) return java.util.List.of();
		TitleData d = data(player.getUuid());
		if (id == null || id.isBlank() || "-".equals(id.trim())) {
			d.forced.clear();
		} else {
			String clean = id.trim();
			if (TitleConfig.forcedById(clean) == null) return java.util.List.copyOf(d.forced);
			if (!d.forced.contains(clean)) d.forced.add(clean);
		}
		save(player.getUuid(), d);
		apply(player);
		return java.util.List.copyOf(d.forced);
	}

	/** 清除称号（所有槽位 + 强制）并还原 display_name。 */
	public static synchronized void clearTitle(ServerPlayerEntity player) {
		if (player == null) return;
		TitleData d = data(player.getUuid());
		String restore = d.originalDisplayName.isEmpty() ? TitleConfig.restoreTemplate() : d.originalDisplayName;
		d.equipped.clear();
		d.forced.clear();
		d.title = "";
		d.suffix = "";
		d.updatedAt = now();
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
		ModuleEvents.onQuit("title", p -> {
			// 只清缓存，不改 display_name：玩家下次进来称号还在
			CACHE.remove(p.getUuid());
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
