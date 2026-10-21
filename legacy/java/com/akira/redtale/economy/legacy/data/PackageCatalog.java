package com.akira.redtale.economy.legacy.data;

import com.akira.redtale.economy.legacy.LegacyInit;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.util.Rarity;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 包裹注册表：`assets/redtale_economy/packages.json` 的运行时镜像。
 *
 * <p>规则（见 `_PACKAGE_EXTEND.md`）：
 * <ul>
 *   <li>非 special 档位 × 每个系列 ⇒ 物品 id {@code <series>_<tier>}（3×6=18）</li>
 *   <li>special 档位（如 {@code surprise}）**不随系列展开**，全局单件，id 就是档位 id</li>
 *   <li>geo / 动画按系列共用一份，档位只换贴图</li>
 * </ul>
 *
 * <p>三个旧 id（{@code normal_package} / {@code rare_package} / {@code legendary_package}）
 * 作为**别名**保留注册，指向新档位贴图（老存档 / 商店里已经存在的物品不会变成空气）。
 *
 * <p>★解析失败必须回落到内置默认表★：这是一条初始化链（{@link com.akira.redtale.economy.legacy.init.REItems}），
 * 任何异常都会让整批物品注册被跳过（KubeJS/Fabric 同族静默失败）。
 */
public final class PackageCatalog {

    /** 一个外观系列（共用一份 geo/动画）。 */
    public record Series(String id, String nameZh, String nameEn, String geo, String anim,
                         String animItem, String iconDir) {
        /** 物品图标 / 实体贴图路径：{@code textures/<iconDir>/<tier>.png}。 */
        public String texturePath(String tierId) {
            return iconDir + "/" + tierId + ".png";
        }
    }

    /** 一个档位。{@code color} 是 0xRRGGBB，{@code particle} 是粒子倍率 k。 */
    public record Tier(String id, String nameZh, String nameEn, int color, double particle,
                       Rarity rarity, SpecialForm specialForm) {
        /** 是否 special 单件（不随系列展开）。保留方法名：调用方一直写 {@code t.special()}。 */
        public boolean special() {
            return specialForm != null;
        }
    }

    /**
     * special 档位的**独立形态**（T74：surprise 是彩蛋，不复用包裹/宝箱剪影）。
     *
     * <p>{@code geo/anim/animItem/iconDir} 与 {@link Series} 同构 ⇒ 渲染侧可以走同一条解析路径，
     * 不用为 special 分裂出第二套 if。
     */
    public record SpecialForm(String form, String geo, String anim, String animItem, String iconDir) {
        public String texturePath(String tierId) {
            return iconDir + "/" + tierId + ".png";
        }
    }

    /**
     * 一个皮肤要用的渲染资源。{@code texturePath} 是**相对 {@code textures/}** 的路径
     * （如 {@code item/surprise/surprise.png}）。
     *
     * <p>系列与 special 都在这里收口：模型类只要调 {@link #renderOf(String)}，不再各自判 series==null。
     */
    public record RenderSpec(String geo, String anim, String animItem, String texturePath) {
    }

    /** 一个要注册的物品。{@code legacy=true} 表示旧 id 别名。 */
    public record ItemDef(String id, Series series, Tier tier, boolean legacy) {
    }

    private static final List<Series> SERIES = new ArrayList<>();
    private static final List<Tier> TIERS = new ArrayList<>();
    private static final List<ItemDef> ITEMS = new ArrayList<>();
    /** itemPath → {seriesId, tierId}。 */
    private static final Map<String, String[]> SKIN = new LinkedHashMap<>();

    static {
        try {
            load();
        } catch (Throwable t) {
            // 兜底：注册链不能因为一个 JSON 挂掉
            SERIES.clear();
            TIERS.clear();
            ITEMS.clear();
            SKIN.clear();
            LegacyInit.LOGGER.warn("[legacy] packages.json 解析失败，回落内置默认表：{}", t.toString());
            builtinDefaults();
        }
    }

    private PackageCatalog() {
    }

    public static List<Series> series() {
        return Collections.unmodifiableList(SERIES);
    }

    public static List<Tier> tiers() {
        return Collections.unmodifiableList(TIERS);
    }

    public static List<ItemDef> items() {
        return Collections.unmodifiableList(ITEMS);
    }

    /** 贴图 / 皮肤解析结果；未知 id 返回 null（渲染层自己回退到 base）。 */
    public static String[] skinOf(String itemPath) {
        String[] s = SKIN.get(itemPath);
        return s == null ? null : s.clone();
    }

    /** itemPath → tier id；未知返回 null。T71 档位高亮用。 */
    public static String tierOf(String itemPath) {
        String[] s = SKIN.get(itemPath);
        return s == null ? null : s[1];
    }

    /**
     * 一个物品 id 的渲染资源（geo / 实体动画 / 物品动画 / 贴图）。
     *
     * <p>★special 与系列走同一个出口★：{@code surprise} 的 skin[0] 是 null，以前所有模型类都
     * {@code return DEFAULT} ⇒ 彩蛋永远画成普通包裹。现在 special 形态在 packages.json 里自带
     * geo/动画/贴图目录，解析结果与系列同构。
     *
     * <p>未知 id / 数据不全返回 null，调用方各自回落到默认资源。
     */
    public static RenderSpec renderOf(String itemPath) {
        String[] skin = SKIN.get(itemPath);
        if (skin == null || skin[1] == null) return null;
        String tierId = skin[1];
        if (skin[0] != null) {
            Series s = seriesById(skin[0]);
            return s == null ? null : new RenderSpec(s.geo(), s.anim(), s.animItem(), s.texturePath(tierId));
        }
        Tier t = tierById(tierId);
        if (t == null || t.specialForm() == null) return null;
        SpecialForm f = t.specialForm();
        return new RenderSpec(f.geo(), f.anim(), f.animItem(), f.texturePath(tierId));
    }

    /** special 单件是否参与「开箱随机档位」（T74）。 */
    public static boolean isSpecial(String itemPath) {
        Tier t = tierById(tierOf(itemPath));
        return t != null && t.special();
    }

    /**
     * 同系列换档：给定当前物品路径与目标 tier，返回目标物品 id（不含命名空间）；找不到返回 null。
     *
     * <p>★只走非 legacy 的正式 id★：旧别名（{@code normal_package} 等）只用来兼容老存档，
     * 换档后应落到 {@code package_xxx}，否则会一直在别名之间横跳、创造栏里也找不到对应条目。
     * special 档位（{@code surprise}）不随系列展开 ⇒ 从普通档换不进去（返回 null）。
     */
    public static String itemIdForTier(String currentPath, String targetTierId) {
        String[] skin = SKIN.get(currentPath);
        if (skin == null || targetTierId == null) return null;
        String seriesId = skin[0];
        for (ItemDef def : ITEMS) {
            if (def.legacy()) continue;
            if (def.series() == null || !def.series().id().equals(seriesId)) continue;
            if (def.tier().id().equals(targetTierId)) return def.id();
        }
        return null;
    }

    public static Series seriesById(String id) {
        for (Series s : SERIES) if (s.id().equals(id)) return s;
        return SERIES.isEmpty() ? null : SERIES.get(0);
    }

    public static Tier tierById(String id) {
        for (Tier t : TIERS) if (t.id().equals(id)) return t;
        return null;
    }

    /** 档位 → 粒子倍率；未知返回 1.0。 */
    public static double particleMultiplier(String tierId) {
        Tier t = tierById(tierId);
        return t == null ? 1.0 : t.particle();
    }

    /** 档位 → 0xRRGGBB 颜色；未知返回米黄。 */
    public static int tierColor(String tierId) {
        Tier t = tierById(tierId);
        return t == null ? 0xD8CBB0 : t.color();
    }

    public static Rarity rarityOf(String name) {
        if (name == null) return Rarity.COMMON;
        return switch (name.toLowerCase()) {
            case "uncommon" -> Rarity.UNCOMMON;
            case "rare" -> Rarity.RARE;
            case "epic" -> Rarity.EPIC;
            default -> Rarity.COMMON;
        };
    }

    // ------------------------------------------------------------------ 解析

    private static void load() throws Exception {
        JsonObject root = readJson();
        JsonArray seriesArr = root.getAsJsonArray("series");
        JsonArray tierArr = root.getAsJsonArray("tiers");
        if (seriesArr == null || tierArr == null) {
            throw new IllegalStateException("packages.json 缺 series / tiers");
        }

        Set<String> seriesIds = new LinkedHashSet<>();
        for (JsonElement e : seriesArr) {
            JsonObject o = e.getAsJsonObject();
            String id = req(o, "id");
            if (!seriesIds.add(id)) throw new IllegalStateException("series id 重复：" + id);
            String iconDir = opt(o, "iconDir", "item/" + id);
            SERIES.add(new Series(id,
                    opt(o, "nameZh", id),
                    opt(o, "nameEn", id),
                    opt(o, "geo", "redtale_economy:geo/package.geo.json"),
                    opt(o, "anim", "redtale_economy:animations/package.animation.json"),
                    opt(o, "animItem", "redtale_economy:animations/package_item.animation.json"),
                    iconDir));
        }

        Set<String> tierIds = new LinkedHashSet<>();
        for (JsonElement e : tierArr) {
            JsonObject o = e.getAsJsonObject();
            String id = req(o, "id");
            if (!tierIds.add(id)) throw new IllegalStateException("tier id 重复：" + id);
            TIERS.add(new Tier(id,
                    opt(o, "nameZh", id),
                    opt(o, "nameEn", id),
                    parseColor(opt(o, "color", "#D8CBB0")),
                    o.has("p") ? o.get("p").getAsDouble() : 1.0,
                    rarityOf(opt(o, "rarity", "common")),
                    parseSpecial(o.get("special"))));
        }

        // series × tiers（跳过 special）
        for (Series s : SERIES) {
            for (Tier t : TIERS) {
                if (t.special()) continue;
                String id = s.id() + "_" + t.id();
                if (SKIN.containsKey(id)) {
                    LegacyInit.LOGGER.warn("[legacy] 包裹 id 重复，已跳过：{}", id);
                    continue;
                }
                ITEMS.add(new ItemDef(id, s, t, false));
                SKIN.put(id, new String[]{s.id(), t.id()});
            }
        }
        // special 单件（不随系列展开）
        for (Tier t : TIERS) {
            if (!t.special()) continue;
            if (SKIN.containsKey(t.id())) {
                LegacyInit.LOGGER.warn("[legacy] 包裹 id 重复，已跳过：{}", t.id());
                continue;
            }
            ITEMS.add(new ItemDef(t.id(), null, t, false));
            SKIN.put(t.id(), new String[]{null, t.id()});
        }
        // 旧 id 别名（老存档 / 已售商品），指向新档位贴图
        alias("normal_package", "package", "normal");
        alias("rare_package", "package", "rare");
        alias("legendary_package", "package", "legend");
    }

    /**
     * 解析 {@code tiers[].special}。
     *
     * <p>两种写法都吃：对象（T74 起，带独立 geo/动画/贴图目录）与字符串（旧格式，只表达
     * "这是 special"）。字符串按 {@code <id>} 推出默认资源路径，保证旧 json 不炸。
     */
    private static SpecialForm parseSpecial(JsonElement el) {
        if (el == null || el.isJsonNull()) return null;
        if (el.isJsonPrimitive()) {
            String form = el.getAsString();
            return new SpecialForm(form,
                    "redtale_economy:geo/" + form + ".geo.json",
                    "redtale_economy:animations/" + form + ".animation.json",
                    "redtale_economy:animations/" + form + "_item.animation.json",
                    "item/" + form);
        }
        if (!el.isJsonObject()) return null;
        JsonObject o = el.getAsJsonObject();
        String form = opt(o, "form", "egg");
        return new SpecialForm(form,
                opt(o, "geo", "redtale_economy:geo/" + form + ".geo.json"),
                opt(o, "anim", "redtale_economy:animations/" + form + ".animation.json"),
                opt(o, "animItem", "redtale_economy:animations/" + form + "_item.animation.json"),
                opt(o, "iconDir", "item/" + form));
    }

    private static void alias(String legacyId, String seriesId, String tierId) {        Series s = seriesById(seriesId);
        Tier t = tierById(tierId);
        if (s == null || t == null) return;
        ITEMS.add(new ItemDef(legacyId, s, t, true));
        SKIN.put(legacyId, new String[]{seriesId, tierId});
    }

    private static JsonObject readJson() throws Exception {
        try (InputStream in = PackageCatalog.class.getResourceAsStream(
                "/assets/redtale_economy/packages.json")) {
            if (in == null) throw new IllegalStateException("找不到 packages.json");
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    private static String req(JsonObject o, String key) {
        if (!o.has(key) || o.get(key).isJsonNull()) {
            throw new IllegalStateException("缺字段 " + key);
        }
        return o.get(key).getAsString();
    }

    private static String opt(JsonObject o, String key, String def) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : def;
    }

    private static int parseColor(String hex) {
        String s = hex.startsWith("#") ? hex.substring(1) : hex;
        try {
            return Integer.parseInt(s, 16) & 0xFFFFFF;
        } catch (NumberFormatException e) {
            return 0xD8CBB0;
        }
    }

    /** 内置默认表：packages.json 坏了也要能注册（与 json 保持同构，改一处要改两处）。 */
    private static void builtinDefaults() {
        SERIES.add(new Series("package", "包裹", "Package",
                "redtale_economy:geo/package.geo.json",
                "redtale_economy:animations/package.animation.json",
                "redtale_economy:animations/package_item.animation.json", "item/package"));
        String[][] t = {
                {"normal", "普通", "Normal", "#D8CBB0", "1.0", "common"},
                {"rare", "稀有", "Rare", "#4FA3D1", "1.2", "uncommon"},
                {"epic", "史诗", "Epic", "#9B59D0", "1.4", "rare"},
                {"legend", "传说", "Legendary", "#FFD24A", "1.6", "epic"},
                {"mythic", "神话", "Mythic", "#E04B4B", "1.8", "epic"},
                {"event", "活动", "Event", "#58D6A8", "1.3", "rare"},
        };
        for (String[] row : t) {
            TIERS.add(new Tier(row[0], row[1], row[2], parseColor(row[3]),
                    Double.parseDouble(row[4]), rarityOf(row[5]), null));
            ITEMS.add(new ItemDef("package_" + row[0], SERIES.get(0), TIERS.get(TIERS.size() - 1), false));
            SKIN.put("package_" + row[0], new String[]{"package", row[0]});
        }
        // special 单件（与 json 同构：内置表也要有，否则 json 坏掉时 surprise 不存在）
        Tier surprise = new Tier("surprise", "惊喜", "Surprise", 0xFFFFFF, 1.0, Rarity.EPIC,
                new SpecialForm("egg",
                        "redtale_economy:geo/surprise.geo.json",
                        "redtale_economy:animations/surprise.animation.json",
                        "redtale_economy:animations/surprise_item.animation.json",
                        "item/surprise"));
        TIERS.add(surprise);
        ITEMS.add(new ItemDef("surprise", null, surprise, false));
        SKIN.put("surprise", new String[]{null, "surprise"});
        alias("normal_package", "package", "normal");
        alias("rare_package", "package", "rare");
        alias("legendary_package", "package", "legend");
    }
}
