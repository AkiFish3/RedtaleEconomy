package com.akira.redtale.economy.legacy.bound;

import com.akira.redtale.economy.config.ConfigManager;
import com.akira.redtale.economy.legacy.LegacyInit;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 绑定物品名单（旧模 {@code redtale} 的 bound_items.json）并入经济侧配置根之后的读取入口。
 *
 * <p>三条约束：
 * <ol>
 *   <li><b>同一份文件</b>：路径取 {@link ConfigManager#baseDir()}（{@code config/redtale/}），
 *       与旧版 {@code FabricLoader#getConfigDir()/redtale} 是同一目录 ⇒ 老存档的配置不用迁移。</li>
 *   <li><b>跟随 {@code /redtale reload}</b>：缓存用 {@link ConfigManager#lastReloadAt()} 做版本号，
 *       经济侧任何一次 reloadAll 都会让本表在下次访问时重新读盘，不需要额外 tick 轮询。</li>
 *   <li><b>两种格式都能读</b>：新版 {@code {"items":[...], "bindOnPickup":true, "blockNonOwnerInSlot":true}}；
 *       旧版裸数组 {@code ["minecraft:diamond"]} 仍然认，写回时统一升成对象形式。</li>
 * </ol>
 */
public final class BoundItemsConfig {
    static final String FILE = "bound_items.json";
    /** 旧版无对象外壳时的默认值：与旧行为一致（拾取即绑、非主人在槽位里被拦）。 */
    private static final boolean DEFAULT_BIND_ON_PICKUP = true;
    private static final boolean DEFAULT_BLOCK_IN_SLOT = true;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static final Set<String> boundItems = new LinkedHashSet<>();
    private static boolean bindOnPickup = DEFAULT_BIND_ON_PICKUP;
    private static boolean blockNonOwnerInSlot = DEFAULT_BLOCK_IN_SLOT;

    /** 缓存版本号：换过 = 需要重新读盘。 */
    private static long loadedAt = Long.MIN_VALUE;
    private static long loadedMtime = Long.MIN_VALUE;
    /** mtime 探测节流：拾取判定每 tick 每个掉落物都会走一次，不能每次 stat（500ms 足够快）。 */
    private static long lastFreshCheckMs = 0L;
    private static final long FRESH_CHECK_INTERVAL_MS = 500L;

    private BoundItemsConfig() {
    }

    // ---------------------------------------------------------------- 路径

    /** 配置文件路径。ConfigManager 尚未初始化时（单元测试/极早调用）退回 Fabric 配置目录，指向同一处。 */
    public static Path file() {
        ConfigManager cm = ConfigManager.get();
        Path dir = cm != null ? cm.baseDir()
                : FabricLoader.getInstance().getConfigDir().resolve("redtale");
        return dir.resolve(FILE);
    }

    private static long reloadStamp() {
        ConfigManager cm = ConfigManager.get();
        return cm == null ? Long.MIN_VALUE : cm.lastReloadAt();
    }

    private static long mtimeOf(Path f) {
        try {
            return Files.isRegularFile(f) ? Files.getLastModifiedTime(f).toMillis() : -1L;
        } catch (IOException e) {
            return -1L;
        }
    }

    // ---------------------------------------------------------------- 读

    /** 强制重读。{@code /redtale bound reload} 直接调它。 */
    public static void load() {
        Path f = file();
        long stamp = reloadStamp();
        long mtime = mtimeOf(f);
        boundItems.clear();
        bindOnPickup = DEFAULT_BIND_ON_PICKUP;
        blockNonOwnerInSlot = DEFAULT_BLOCK_IN_SLOT;
        try {
            if (!Files.isRegularFile(f)) {
                saveDefault(f);
            } else {
                parse(Files.readString(f, StandardCharsets.UTF_8), f);
            }
            LegacyInit.LOGGER.info("[legacy] bound_items 载入 {} 条（bindOnPickup={} blockInSlot={}）",
                    boundItems.size(), bindOnPickup, blockNonOwnerInSlot);
        } catch (Exception e) {
            // 配置坏档不许让绑定功能静默变"全部不绑定"：记 error 但保留默认值
            LegacyInit.LOGGER.error("[legacy] bound_items 读取失败，按空名单处理", e);
            boundItems.clear();
        }
        loadedAt = stamp;
        loadedMtime = mtimeOf(f);
    }

    /** 缓存过期就重读。文件被手改（mtime 变）或经济侧 reload（版本号变）都会触发。 */
    private static void ensureFresh() {
        // reload 版本号变化要立刻生效（/redtale reload 之后玩家的下一次点击就该用新表）
        long stamp = reloadStamp();
        if (loadedAt != stamp) {
            load();
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastFreshCheckMs < FRESH_CHECK_INTERVAL_MS) return;
        lastFreshCheckMs = now;
        Path f = file();
        if (loadedMtime != mtimeOf(f)) {
            load();
        }
    }

    private static void parse(String text, Path f) throws IOException {
        if (text == null || text.isBlank()) return;
        JsonElement root = JsonParser.parseString(text);
        if (root == null || root.isJsonNull()) return;
        if (root.isJsonArray()) {           // 旧版裸数组
            readItems(root.getAsJsonArray(), f);
            return;
        }
        if (!root.isJsonObject()) return;
        JsonObject obj = root.getAsJsonObject();
        JsonElement items = obj.get("items");
        if (items != null && items.isJsonArray()) {
            readItems(items.getAsJsonArray(), f);
        } else {                            // 兼容 {"minecraft:diamond":true}
            for (String key : obj.keySet()) {
                if ("bindOnPickup".equals(key) || "blockNonOwnerInSlot".equals(key)) continue;
                JsonElement v = obj.get(key);
                if (v != null && v.isJsonPrimitive() && v.getAsJsonPrimitive().isBoolean() && v.getAsBoolean()) {
                    addNormalized(key, f);
                }
            }
        }
        bindOnPickup = bool(obj, "bindOnPickup", DEFAULT_BIND_ON_PICKUP);
        blockNonOwnerInSlot = bool(obj, "blockNonOwnerInSlot", DEFAULT_BLOCK_IN_SLOT);
    }

    private static boolean bool(JsonObject obj, String key, boolean def) {
        JsonElement el = obj.get(key);
        if (el == null || !el.isJsonPrimitive() || !el.getAsJsonPrimitive().isBoolean()) return def;
        return el.getAsBoolean();
    }

    private static void readItems(JsonArray arr, Path f) {
        for (JsonElement el : arr) {
            if (el == null || !el.isJsonPrimitive()) continue;
            addNormalized(el.getAsString(), f);
        }
    }

    /** 空串/非法 id 直接丢弃，避免"配置里有个空字符串就等于全物品绑定"。 */
    private static void addNormalized(String raw, Path f) {
        if (raw == null) return;
        String id = raw.trim();
        if (id.isEmpty()) return;
        if (!id.contains(":")) id = "minecraft:" + id;
        Identifier key = Identifier.tryParse(id);
        if (key == null || Registries.ITEM.get(key) == Items.AIR) {
            LegacyInit.LOGGER.warn("[legacy] bound_items 里的 {} 不是已注册物品 id（{}）", id, f.getFileName());
        }
        boundItems.add(id);
    }

    // ---------------------------------------------------------------- 写

    private static void saveDefault(Path f) {
        boundItems.add("minecraft:diamond");
        boundItems.add("minecraft:netherite_ingot");
        write(f);
    }

    /** 把内存状态写盘（原子替换）。写失败只记日志，不回滚内存 —— 避免"命令成功但值丢了"。 */
    public static void save() {
        write(file());
    }

    private static void write(Path f) {
        JsonObject root = new JsonObject();
        JsonArray items = new JsonArray();
        for (String id : boundItems) items.add(id);
        root.add("items", items);
        root.addProperty("bindOnPickup", bindOnPickup);
        root.addProperty("blockNonOwnerInSlot", blockNonOwnerInSlot);
        try {
            Files.createDirectories(f.getParent());
            Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
            Files.writeString(tmp, GSON.toJson(root), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicFailed) {
                Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
            }
            loadedMtime = mtimeOf(f);
            loadedAt = reloadStamp();
        } catch (IOException e) {
            LegacyInit.LOGGER.error("[legacy] bound_items 写入失败", e);
        }
    }

    // ---------------------------------------------------------------- 查询

    public static boolean isBoundItem(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        ensureFresh();
        if (boundItems.isEmpty()) return false;
        String id = Registries.ITEM.getId(stack.getItem()).toString();
        return boundItems.contains(id);
    }

    public static Set<String> getBoundItems() {
        ensureFresh();
        return new LinkedHashSet<>(boundItems);
    }

    public static boolean bindOnPickup() {
        ensureFresh();
        return bindOnPickup;
    }

    public static boolean blockNonOwnerInSlot() {
        ensureFresh();
        return blockNonOwnerInSlot;
    }

    public static void addBoundItem(String itemId) {
        ensureFresh();
        String id = itemId == null ? "" : itemId.trim();
        if (id.isEmpty()) return;
        if (!id.contains(":")) id = "minecraft:" + id;
        if (boundItems.add(id)) save();
    }

    public static void removeBoundItem(String itemId) {
        ensureFresh();
        String id = itemId == null ? "" : itemId.trim();
        if (!id.contains(":")) id = "minecraft:" + id;
        if (boundItems.remove(id)) save();
    }
}
