package com.akira.redtale.economy.legacy.bound;

import com.akira.redtale.economy.legacy.LegacyInit;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;

import java.io.*;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

public class BoundItemsConfig {
    private static final Path CONFIG_DIR = FabricLoader.getInstance().getConfigDir().resolve("redtale");
    private static final File CONFIG_FILE = CONFIG_DIR.resolve("bound_items.json").toFile();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type TYPE = new TypeToken<Set<String>>() {}.getType();

    private static Set<String> boundItems = new HashSet<>();

    public static void load() {
        try {
            // ✅ 修复1：使用 Files.exists() 检查 Path
            if (!Files.exists(CONFIG_DIR)) {
                Files.createDirectories(CONFIG_DIR);
            }
            if (!CONFIG_FILE.exists()) {
                saveDefault();
            }
            try (FileReader reader = new FileReader(CONFIG_FILE)) {
                boundItems = GSON.fromJson(reader, TYPE);
                if (boundItems == null) boundItems = new HashSet<>();
            }
            LegacyInit.LOGGER.info("Loaded {} bound item identifiers", boundItems.size());
        } catch (Exception e) {
            LegacyInit.LOGGER.error("Failed to load bound items config", e);
            boundItems = new HashSet<>();
        }
    }

    private static void saveDefault() {
        Set<String> defaults = new HashSet<>();
        defaults.add("minecraft:diamond");
        defaults.add("minecraft:netherite_ingot");
        try (FileWriter writer = new FileWriter(CONFIG_FILE)) {
            GSON.toJson(defaults, writer);
        } catch (IOException e) {
            LegacyInit.LOGGER.error("Failed to save default bound items config", e);
        }
    }

    public static void save() {
        try (FileWriter writer = new FileWriter(CONFIG_FILE)) {
            GSON.toJson(boundItems, writer);
        } catch (IOException e) {
            LegacyInit.LOGGER.error("Failed to save bound items config", e);
        }
    }

    public static boolean isBoundItem(ItemStack stack) {
        if (stack.isEmpty()) return false;
        // ✅ 修复2：使用 Registries.ITEM 而不是 Registry.ITEM
        String id = Registries.ITEM.getId(stack.getItem()).toString();
        return boundItems.contains(id);
    }

    public static Set<String> getBoundItems() {
        return new HashSet<>(boundItems);
    }

    public static void addBoundItem(String itemId) {
        boundItems.add(itemId);
        save();
    }

    public static void removeBoundItem(String itemId) {
        boundItems.remove(itemId);
        save();
    }
}