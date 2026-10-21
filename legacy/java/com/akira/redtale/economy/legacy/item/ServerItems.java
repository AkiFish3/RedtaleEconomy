package com.akira.redtale.economy.legacy.item;

import com.akira.redtale.economy.legacy.LegacyInit;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.client.item.TooltipContext;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroups;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.text.Text;
import net.minecraft.util.Rarity;
import net.minecraft.util.Identifier;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * 服务器物品声明式注册基座（T42）。
 *
 * <p>这些物品<strong>只</strong>通过指令 / 任务 / 战令 / 商店 / 活动发放：
 * 不生成于世界、不掉落、不进创造栏、无配方。默认行为即如此，spec 只用于描述与自检。
 *
 * <p>放在 legacy 包内是刻意的：本类属于"旧模整合"的产物，写权限在 B 队列
 * （{@code legacy/**}），A 队列的 {@code economy/**} 一行不动。经济侧要发放物品时
 * 直接调 {@link #find(String)} 取 {@code Item} 即可。
 *
 * <pre>{@code
 * ServerItems.register("makeup_ticket", spec -> spec
 *         .stack(64)
 *         .rarity(Rarity.COMMON)
 *         .tooltip("item.redtale_economy.makeup_ticket.tooltip")
 *         .noCreativeTab()
 *         .noLoot()
 *         .noRecipe());
 * }</pre>
 */
public final class ServerItems {

    private static final Map<String, Item> REGISTERED = new LinkedHashMap<>();
    private static boolean registered = false;

    private ServerItems() {
    }

    public static void register() {
        if (registered) {
            return;
        }
        registered = true;

        // ---- 券类（消耗品，堆叠 64）----
        register("makeup_ticket", spec -> spec.stack(64).rarity(Rarity.COMMON)
                .tooltip("item.redtale_economy.makeup_ticket.tooltip"));
        register("refresh_ticket", spec -> spec.stack(64).rarity(Rarity.COMMON)
                .tooltip("item.redtale_economy.refresh_ticket.tooltip"));
        register("reroll_ticket", spec -> spec.stack(64).rarity(Rarity.COMMON)
                .tooltip("item.redtale_economy.reroll_ticket.tooltip"));
        register("rename_ticket", spec -> spec.stack(64).rarity(Rarity.UNCOMMON)
                .tooltip("item.redtale_economy.rename_ticket.tooltip"));
        register("storage_ticket", spec -> spec.stack(64).rarity(Rarity.UNCOMMON)
                .tooltip("item.redtale_economy.storage_ticket.tooltip"));
        register("pass_ticket", spec -> spec.stack(64).rarity(Rarity.UNCOMMON)
                .tooltip("item.redtale_economy.pass_ticket.tooltip"));

        // ---- 代币类（堆叠 64）----
        register("premium_token", spec -> spec.stack(64).rarity(Rarity.RARE)
                .tooltip("item.redtale_economy.premium_token.tooltip"));
        register("event_token", spec -> spec.stack(64).rarity(Rarity.COMMON)
                .tooltip("item.redtale_economy.event_token.tooltip"));
        register("guild_credit", spec -> spec.stack(64).rarity(Rarity.COMMON)
                .tooltip("item.redtale_economy.guild_credit.tooltip"));
        register("season_medal", spec -> spec.stack(64).rarity(Rarity.RARE).glow(true)
                .tooltip("item.redtale_economy.season_medal.tooltip"));

        // ---- 材料 / 稀有 ----
        register("mythic_shard", spec -> spec.stack(64).rarity(Rarity.EPIC).glow(true)
                .tooltip("item.redtale_economy.mythic_shard.tooltip"));
        register("quest_scroll", spec -> spec.stack(64).rarity(Rarity.COMMON)
                .tooltip("item.redtale_economy.quest_scroll.tooltip"));
        register("treasure_key", spec -> spec.stack(16).rarity(Rarity.UNCOMMON)
                .tooltip("item.redtale_economy.treasure_key.tooltip"));

        // ---- 礼盒（堆叠 16）----
        register("gift_small", spec -> spec.stack(16).rarity(Rarity.UNCOMMON)
                .tooltip("item.redtale_economy.gift_small.tooltip"));
        register("gift_large", spec -> spec.stack(16).rarity(Rarity.RARE)
                .tooltip("item.redtale_economy.gift_large.tooltip"));

        // ---- 旧占位兑换代币（T47 / A 的 2026-10-21 13:22 请求）----
        register("exchange_token_basic", spec -> spec.stack(64).rarity(Rarity.COMMON)
                .tooltip("item.redtale_economy.exchange_token_basic.tooltip"));
        register("exchange_token_advanced", spec -> spec.stack(64).rarity(Rarity.UNCOMMON)
                .tooltip("item.redtale_economy.exchange_token_advanced.tooltip"));

        LegacyInit.LOGGER.info("[legacy] ServerItems 注册完成，共 {} 个：{}", REGISTERED.size(), REGISTERED.keySet());
    }

    /**
     * 声明并注册一个服务器物品。
     *
     * @param id  不带命名空间的路径（{@code redtale_economy:<id>}）
     * @param cfg spec 配置回调
     */
    public static Item register(String id, Consumer<Spec> cfg) {
        Spec spec = new Spec(id);
        if (cfg != null) {
            cfg.accept(spec);
        }
        Identifier identifier = new Identifier(LegacyInit.MOD_ID, id);
        // 幂等：经济侧（RedtaleEconomy#onInitialize）已经注册过同名物品时不再重复注册。
        // 重复 register 会抛 "Attempted to register ID ... at different raw IDs" 并**中断整个 entrypoint**
        // （表现为客户端一启动就崩、且看不出是谁的锅）。以先注册者为准，这里只告警。
        if (Registries.ITEM.containsId(identifier)) {
            Item existing = Registries.ITEM.get(identifier);
            REGISTERED.put(id, existing);
            LegacyInit.LOGGER.warn("[legacy] {} 已被其它入口注册（{}），ServerItems 跳过，spec 未生效"
                    + "（stack={} / tooltip={}）", identifier, existing.getClass().getSimpleName(), spec.stack, spec.tooltipKeys.size());
            return existing;
        }
        Item item = new ServerItem(spec);
        Registry.register(Registries.ITEM, identifier, item);
        REGISTERED.put(id, item);
        if (spec.creativeTab) {
            ItemGroupEvents.modifyEntriesEvent(ItemGroups.INGREDIENTS).register(entries -> entries.add(item));
        }
        return item;
    }

    /** 按路径取物品（不带命名空间）。未注册返回 {@link Optional#empty()}。 */
    public static Optional<Item> find(String id) {
        return Optional.ofNullable(REGISTERED.get(id));
    }

    /** 已注册物品的只读快照（id → Item），顺序 = 声明顺序。 */
    public static Map<String, Item> all() {
        return Collections.unmodifiableMap(REGISTERED);
    }

    /** 声明式配置。所有默认值 = "纯服务器物品"语义。 */
    public static final class Spec {
        private final String id;
        private int stack = 64;
        private Rarity rarity = Rarity.COMMON;
        private final List<String> tooltipKeys = new ArrayList<>(2);
        private boolean glow = false;
        private boolean creativeTab = false;
        private boolean loot = false;
        private boolean recipe = false;
        private String model;

        private Spec(String id) {
            this.id = id;
        }

        public Spec stack(int maxCount) {
            this.stack = Math.max(1, Math.min(64, maxCount));
            return this;
        }

        public Spec rarity(Rarity rarity) {
            this.rarity = rarity == null ? Rarity.COMMON : rarity;
            return this;
        }

        /** 追加一行 tooltip（lang key）。可多次调用。 */
        public Spec tooltip(String translationKey) {
            if (translationKey != null && !translationKey.isBlank()) {
                this.tooltipKeys.add(translationKey);
            }
            return this;
        }

        public Spec tooltipLines(String... translationKeys) {
            if (translationKeys != null) {
                for (String key : translationKeys) {
                    tooltip(key);
                }
            }
            return this;
        }

        public Spec glow(boolean value) {
            this.glow = value;
            return this;
        }

        /** 默认不进创造栏；显式调用才进（本批 17 个都没调）。 */
        public Spec creativeTab() {
            this.creativeTab = true;
            return this;
        }

        public Spec noCreativeTab() {
            this.creativeTab = false;
            return this;
        }

        /** 声明"无掉落表"（默认）。仅用于自检语义，落地不生成 loot table。 */
        public Spec noLoot() {
            this.loot = false;
            return this;
        }

        /** 声明"无配方"（默认）。 */
        public Spec noRecipe() {
            this.recipe = false;
            return this;
        }

        public Spec model(String modelPath) {
            this.model = modelPath;
            return this;
        }

        public String id() {
            return id;
        }

        public boolean hasLootTable() {
            return loot;
        }

        public boolean hasRecipe() {
            return recipe;
        }

        public String modelPath() {
            return model == null ? "item/" + id : model;
        }
    }

    /** 带 tooltip / 附魔光效的服务端物品。 */
    private static final class ServerItem extends Item {
        private final List<String> tooltipKeys;
        private final boolean glow;

        private ServerItem(Spec spec) {
            super(new Item.Settings().maxCount(spec.stack).rarity(spec.rarity));
            this.tooltipKeys = List.copyOf(spec.tooltipKeys);
            this.glow = spec.glow;
        }

        @Override
        public void appendTooltip(ItemStack stack, @Nullable World world, List<Text> tooltip, TooltipContext context) {
            for (String key : tooltipKeys) {
                tooltip.add(Text.translatable(key).formatted(net.minecraft.util.Formatting.GRAY));
            }
        }

        @Override
        public boolean hasGlint(ItemStack stack) {
            return glow;
        }
    }
}
