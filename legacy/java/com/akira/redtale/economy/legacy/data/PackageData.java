// PackageData.java
package com.akira.redtale.economy.legacy.data;

import com.google.common.collect.ImmutableList;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.util.math.random.Random;

import java.util.ArrayList;
import java.util.List;

public class PackageData {
    public enum PackageMode {
        ALL, CHOOSE, RANDOM;

        public static final Codec<PackageMode> CODEC = Codec.STRING.xmap(
                str -> {
                    try { return valueOf(str.toUpperCase()); } catch (IllegalArgumentException e) { return ALL; }
                },
                Enum::name
        );
    }

    public static final Codec<PackageData> CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    PackageMode.CODEC.fieldOf("mode").forGetter(PackageData::mode),
                    PackageItem.CODEC.listOf().fieldOf("items").forGetter(PackageData::items)
            ).apply(instance, PackageData::new)
    );

    public record PackageItem(ItemStack item, int weight) {
        public static final Codec<PackageItem> CODEC = RecordCodecBuilder.create(inst ->
                inst.group(
                        ItemStack.CODEC.fieldOf("item").forGetter(PackageItem::item),
                        Codec.INT.fieldOf("weight").forGetter(PackageItem::weight)
                ).apply(inst, PackageItem::new)
        );

        public static PackageItem fromNbt(NbtCompound nbt) {
            ItemStack item = ItemStack.fromNbt(nbt.getCompound("item"));
            int weight = nbt.getInt("weight");
            return new PackageItem(item, weight);
        }

        public NbtCompound toNbt() {
            NbtCompound nbt = new NbtCompound();
            nbt.put("item", item.writeNbt(new NbtCompound()));
            nbt.putInt("weight", weight);
            return nbt;
        }
    }

    private final PackageMode mode;
    private final List<PackageItem> items;

    public PackageData(PackageMode mode, List<PackageItem> items) {
        this.mode = mode;
        this.items = ImmutableList.copyOf(items);
    }

    public PackageMode mode() { return mode; }
    public List<PackageItem> items() { return items; }

    /**
     * 是否至少有一条**能真正发出去**的条目（物品非空）。
     *
     * <p>★为什么需要这条判据★：{@link #fromNbt} 在没有 {@code mode} 键时默认 {@code ALL}，
     * 而「裸 id 包裹」（{@code /give redtale_economy:chest_rare}、命令/商店/任务发出来的
     * 没有 NBT 的那种）items 是**空表**。旧实现里 ALL + 空表 ⇒ 发放循环一次都不进、
     * {@code rewardGiven = true}、80 tick 后实体静默消失：
     * 物品被消耗、玩家什么都没拿到、**日志一行没有**（RANDOM 分支至少还有一句 "no valid items"）。
     * 现在右键时先过这条判据（不消耗物品），实体侧也补一句 warn 兜住旧存档里已经在飞的空包裹。
     */
    public boolean hasGiveableItem() {
        for (PackageItem item : items) {
            if (item != null && !item.item().isEmpty()) return true;
        }
        return false;
    }

    // 随机抽取一个物品（根据权重）
    public PackageItem getRandomItem(Random random) { // ✅ 参数类型已更新
        if (items.isEmpty()) {
            return null;
        }

        // 过滤有效物品（权重 > 0 且物品非空）
        List<PackageItem> validItems = new ArrayList<>();
        int totalWeight = 0;
        for (PackageItem item : items) {
            if (item.weight > 0 && !item.item.isEmpty()) {
                validItems.add(item);
                totalWeight += item.weight;
            }
        }

        // 无有效物品
        if (validItems.isEmpty() || totalWeight <= 0) {
            return null;
        }

        // 加权随机选择
        int roll = random.nextInt(totalWeight); // ✅ Minecraft's Random 有 nextInt(int)
        int current = 0;
        for (PackageItem item : validItems) {
            current += item.weight;
            if (roll < current) {
                return item;
            }
        }

        // fallback（理论上不会走到这里）
        return validItems.get(validItems.size() - 1);
    }

    // 从 NBT 读取
    public static PackageData fromNbt(NbtCompound nbt) {
        if (nbt == null) return new PackageData(PackageMode.RANDOM, List.of());

        String modeStr = nbt.getString("mode");
        PackageMode mode = PackageMode.ALL; // 默认
        try {
            mode = PackageMode.valueOf(modeStr.toUpperCase());
        } catch (Exception ignored) {}

        NbtList itemsList = nbt.getList("items", NbtElement.COMPOUND_TYPE);
        ImmutableList.Builder<PackageItem> items = ImmutableList.builder();
        for (NbtElement elem : itemsList) {
            if (elem instanceof NbtCompound compound) {
                items.add(PackageItem.fromNbt(compound));
            }
        }

        return new PackageData(mode, items.build());
    }

    // 写入 NBT
    public NbtCompound toNbt() {
        NbtCompound nbt = new NbtCompound();
        nbt.putString("mode", mode.name());
        NbtList itemsList = new NbtList();
        for (PackageItem item : items) {
            itemsList.add(item.toNbt());
        }
        nbt.put("items", itemsList);
        return nbt;
    }
}