package com.akira.redtale.economy.legacy.init;

import com.akira.redtale.economy.legacy.LegacyInit;
import com.akira.redtale.economy.legacy.data.PackageCatalog;
import com.akira.redtale.economy.legacy.item.PackageItem;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroups;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * 包裹物品注册（数据驱动）。
 *
 * <p>数量由 {@code packages.json} 决定：series × tiers（跳过 special）+ special 单件 + 3 个旧 id 别名
 * ⇒ 3×6 + 1 + 3 = 22 个。**不要在这里手写 id**，加系列/档位只改 JSON（见 `_PACKAGE_EXTEND.md`）。
 */
public class REItems {

    private static final List<Item> REGISTERED = new ArrayList<>();

    private static Item registerItem(String name, Item item) {
        return Registry.register(Registries.ITEM, new Identifier(LegacyInit.MOD_ID, name), item);
    }

    /** 幂等：重复调用只注册一次（entrypoint 被跑两遍的场合不会崩）。 */
    public static void register() {
        if (!REGISTERED.isEmpty()) {
            LegacyInit.LOGGER.info("[legacy] 包裹物品已注册（{} 个），跳过", REGISTERED.size());
            return;
        }

        for (PackageCatalog.ItemDef def : PackageCatalog.items()) {
            try {
                Item item = registerItem(def.id(), new PackageItem(new Item.Settings()
                        .maxCount(16)
                        .rarity(def.tier().rarity())));
                REGISTERED.add(item);
                if (def.legacy()) {
                    LegacyInit.LOGGER.info("[legacy] 包裹别名 {} → {}/{}",
                            def.id(), def.series().id(), def.tier().id());
                }
            } catch (Throwable t) {
                // ★单个失败必须吞掉★：注册链里抛异常会让同事件后续全部跳过（批量注册静默丢）
                LegacyInit.LOGGER.warn("[legacy] 包裹 {} 注册失败：{}", def.id(), t.toString());
            }
        }

        if (!REGISTERED.isEmpty()) {
            ItemGroupEvents.modifyEntriesEvent(ItemGroups.FUNCTIONAL).register(entries -> {
                for (Item item : REGISTERED) entries.add(item);
            });
        }

        LegacyInit.LOGGER.info("[legacy] ✅ 包裹物品 {} 个（{} 系列 × {} 档位 + special + 别名）",
                REGISTERED.size(), PackageCatalog.series().size(), PackageCatalog.tiers().size());
    }

    /** 全部已注册包裹（命令 / 编辑器下拉用）。 */
    public static List<Item> all() {
        return List.copyOf(REGISTERED);
    }
}
