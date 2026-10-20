package com.akira.redtale.economy.shop;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 商品显示名解析（U11 收尾：显示名搜索）。
 *
 * <p>为什么不用 {@code ItemStack.getName()} 一把梭：专用服务端<b>不加载 assets</b>，
 * 原版 {@code Language} 只有 en_us，中文名全靠客户端解析。所以按两级取：
 * <ol>
 *   <li><b>模组 jar 里的 {@code assets/<ns>/lang/zh_cn.json}</b> —— 专用服务端也能读
 *       （{@link FabricLoader#getModContainer} + {@link ModContainer#findPath}），
 *       coc / minia 这类家具商品就是靠这条拿到中文名</li>
 *   <li>取不到再退回服务端语言（一般是 en_us）；若返回的是裸翻译键（{@code item.xxx.yyy}）说明
 *       语言没加载，按「无显示名」处理，绝不用键污染搜索</li>
 * </ol>
 *
 * <p>只缓存 {@code item.*} / {@code block.*} 两种键，别的键（UI 文案）不占内存。
 */
public final class ItemLang {

	private static final Map<String, Map<String, String>> LANGS = new ConcurrentHashMap<>();
	private static final Map<String, String> CACHE = new ConcurrentHashMap<>();

	private ItemLang() {
	}

	/** 商品显示名；解析不到返回空串（调用方按「无显示名」处理）。 */
	public static String displayName(String itemId) {
		if (itemId == null || itemId.isBlank()) return "";
		String cached = CACHE.get(itemId);
		if (cached != null) return cached;
		String out = resolve(itemId);
		if (CACHE.size() > 16384) CACHE.clear();
		CACHE.put(itemId, out);
		return out;
	}

	private static String resolve(String itemId) {
		int colon = itemId.indexOf(':');
		if (colon <= 0) return "";
		String ns = itemId.substring(0, colon);
		String path = itemId.substring(colon + 1);
		Map<String, String> zh = lang(ns);
		String v = zh.get("item." + ns + "." + path);
		if (v == null) v = zh.get("block." + ns + "." + path);
		if (v != null && !v.isBlank()) return v;
		return serverName(itemId);
	}

	/** 模组 jar 里的 zh_cn 表（只收 item./block.）。取不到返回空表。 */
	private static Map<String, String> lang(String ns) {
		Map<String, String> m = LANGS.get(ns);
		if (m != null) return m;
		Map<String, String> loaded = new LinkedHashMap<>();
		try {
			Optional<ModContainer> mc = FabricLoader.getInstance().getModContainer(ns);
			if (mc.isPresent()) {
				Optional<Path> p = mc.get().findPath("assets/" + ns + "/lang/zh_cn.json");
				if (p.isPresent() && Files.isRegularFile(p.get())) {
					try (Reader r = Files.newBufferedReader(p.get(), StandardCharsets.UTF_8)) {
						JsonElement el = JsonParser.parseReader(r);
						if (el != null && el.isJsonObject()) {
							JsonObject o = el.getAsJsonObject();
							for (Map.Entry<String, JsonElement> e : o.entrySet()) {
								String k = e.getKey();
								if (!k.startsWith("item.") && !k.startsWith("block.")) continue;
								if (!e.getValue().isJsonPrimitive()) continue;
								loaded.put(k, e.getValue().getAsString());
							}
						}
					}
				}
			}
		} catch (Throwable ignored) {
			// 加载器不在 / 文件坏 → 空表，走服务端语言兜底
		}
		LANGS.put(ns, loaded);
		return loaded;
	}

	/** 服务端语言兜底；返回裸翻译键时视为没有。 */
	private static String serverName(String itemId) {
		try {
			Identifier id = Identifier.tryParse(itemId);
			if (id == null || !Registries.ITEM.containsId(id)) return "";
			ItemStack stack = new ItemStack(Registries.ITEM.get(id));
			String name = stack.getName().getString();
			if (name == null || name.isBlank()) return "";
			String key = Registries.ITEM.get(id).getTranslationKey();
			String lower = name.toLowerCase(Locale.ROOT);
			if (name.equals(key) || lower.startsWith("item.") || lower.startsWith("block.")
					|| lower.startsWith("tile.")) {
				return "";
			}
			return name;
		} catch (Throwable ignored) {
			return "";
		}
	}

	/** 重载配置后清缓存（语言不会变，但商品集合会）。 */
	public static void clearCache() {
		CACHE.clear();
	}
}
