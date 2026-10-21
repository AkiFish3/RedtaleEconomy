package com.akira.redtale.economy.legacy.init;

import com.akira.redtale.economy.legacy.LegacyInit;
import com.akira.redtale.economy.legacy.item.PackageItem;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroups;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;
import net.minecraft.util.Rarity;

public class REItems {
    public static final Item NORMAL_PACKAGE = registerItem("normal_package",
            new PackageItem(new Item.Settings()
                    .maxCount(16)
                    .rarity(Rarity.COMMON)));

    public static final Item RARE_PACKAGE = registerItem("rare_package",
            new PackageItem(new Item.Settings()
                    .maxCount(16)
                    .rarity(Rarity.UNCOMMON)));

    public static final Item LEGENDARY_PACKAGE = registerItem("legendary_package",
            new PackageItem(new Item.Settings()
                    .maxCount(16)
                    .rarity(Rarity.EPIC)));

    private static Item registerItem(String name, Item item) {
        return Registry.register(Registries.ITEM, new Identifier(LegacyInit.MOD_ID, name), item);
    }

    // ✅ 方法名改为 register，与 LegacyInit.java 中调用一致
    public static void register() {
        // 注册到功能物品组
        ItemGroupEvents.modifyEntriesEvent(ItemGroups.FUNCTIONAL).register(entries -> {
            entries.add(NORMAL_PACKAGE);
            entries.add(RARE_PACKAGE);
            entries.add(LEGENDARY_PACKAGE);
        });

        LegacyInit.LOGGER.info("✅ Registered LegacyInit items");
    }
}