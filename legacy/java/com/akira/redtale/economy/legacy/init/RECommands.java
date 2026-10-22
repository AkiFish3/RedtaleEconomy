package com.akira.redtale.economy.legacy.init;

import com.akira.redtale.economy.legacy.LegacyInit;
import com.akira.redtale.economy.legacy.bound.BoundItemsConfig;
import com.akira.redtale.economy.legacy.data.PackageData;
import com.akira.redtale.economy.legacy.entity.PackageEntity;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.command.CommandSource;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.registry.Registries;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;

import java.util.Locale;

import static net.minecraft.server.command.CommandManager.literal;
import static net.minecraft.server.command.CommandManager.argument;

public class RECommands {

    private static final SuggestionProvider<ServerCommandSource> ITEM_SUGGESTIONS = (context, builder) -> {
        CommandSource.suggestIdentifiers(Registries.ITEM.getIds(), builder);
        return builder.buildFuture();
    };

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        // 挂在 /redtale bound 子树下：经济侧已有 /redtale 根与 reload 等子节点，
        // Brigadier 对同名节点做合并、保留先注册者的 executes ⇒ 直接注册旧根会被静默吞掉（T36 §2.4 / D5）。
        dispatcher.register(literal("redtale")
                .then(literal("bound")
                        .requires(source -> source.hasPermissionLevel(4))
                        .then(literal("reload")
                        .executes(context -> {
                            ServerCommandSource source = context.getSource();
                            try {
                                BoundItemsConfig.load();
                                source.sendFeedback(() -> Text.translatable("command.redtale_economy.reload.success", BoundItemsConfig.getBoundItems().toString()), true);
                                LegacyInit.LOGGER.info("Bound items config reloaded by {}", source.getName());
                                return 1;
                            } catch (Exception e) {
                                source.sendFeedback(() -> Text.translatable("command.redtale_economy.reload.fail"), true);
                                LegacyInit.LOGGER.error("Failed to reload bound items config", e);
                                return 0;
                            }
                        })
                )
                .then(literal("add")
                        // ★必须是 greedyString★：`word()` / `string()` 的非引号分支走
                        // `StringReader.readUnquotedString()`，字符集只有 `0-9A-Za-z_.+-`，
                        // **不含冒号** ⇒ `add minecraft:emerald` 直接报
                        // "Expected whitespace to end one argument, but found trailing data"。
                        // 而建议列表（ITEM_SUGGESTIONS）给的就是 `minecraft:xxx` 这种带冒号的写法，
                        // 玩家点补全 → 命令立刻解析失败。旧代码把 `word()` 换成 `string()` 只是换了个
                        // 同样不收冒号的实现（2026-10-22 实测：`add hand` 能过、`add minecraft:emerald` 不能）。
                        .then(argument("item", StringArgumentType.greedyString())
                                .suggests(ITEM_SUGGESTIONS)
                                .executes(context -> {
                                    String input = StringArgumentType.getString(context, "item");
                                    ServerCommandSource source = context.getSource();
                                    // greedyString 会把引号也吃进来（玩家照旧文档写 add "minecraft:diamond"）⇒ 先剥引号
                                    input = stripQuotes(input);
                                    // 处理 "hand" 快捷输入
                                    if ("hand".equalsIgnoreCase(input)) {
                                        // 需要获取执行者手中的物品 ID（非 OP 版本需要额外处理）
                                        // 简单实现：要求玩家执行，获取主手物品 ID
                                        if (source.getEntity() instanceof net.minecraft.entity.player.PlayerEntity player) {
                                            net.minecraft.item.ItemStack handStack = player.getMainHandStack();
                                            if (handStack.isEmpty()) {
                                                source.sendFeedback(() -> Text.translatable("command.redtale_economy.add.hand_empty"), true);
                                                return 0;
                                            }
                                            String itemId = Registries.ITEM.getId(handStack.getItem()).toString();
                                            BoundItemsConfig.addBoundItem(itemId);
                                            source.sendFeedback(() -> Text.translatable("command.redtale_economy.add.success", itemId), true);
                                            LegacyInit.LOGGER.info("Added bound item {} via 'hand' by {}", itemId, source.getName());
                                            return 1;
                                        } else {
                                            source.sendFeedback(() -> Text.translatable("command.redtale_economy.add.not_player"), true);
                                            return 0;
                                        }
                                    }
                                    // 正常处理物品 ID
                                    String itemId = input.contains(":") ? input : "minecraft:" + input;
                                    Identifier id = Identifier.tryParse(itemId);
                                    if (id == null || !Registries.ITEM.containsId(id)) {
                                        source.sendFeedback(() -> Text.translatable("command.redtale_economy.add.not_exist", itemId), true);
                                        return 0;
                                    }
                                    BoundItemsConfig.addBoundItem(itemId);
                                    source.sendFeedback(() -> Text.translatable("command.redtale_economy.add.success", itemId), true);
                                    LegacyInit.LOGGER.info("Added bound item {} by {}", itemId, source.getName());
                                    return 1;
                                })
                        )
                )
                .then(literal("remove")
                        // 同上：不收冒号 ⇒ 必须 greedyString，否则 `remove minecraft:diamond` 解析失败
                        .then(argument("item", StringArgumentType.greedyString())
                                .suggests((context, builder) -> {
                                    for (String id : BoundItemsConfig.getBoundItems()) {
                                        builder.suggest(id);
                                    }
                                    return builder.buildFuture();
                                })
                                .executes(context -> {
                                    String raw = StringArgumentType.getString(context, "item");
                                    // greedyString 会连引号一起吃进来，与 add 同口径先剥引号
                                    raw = stripQuotes(raw);
                                    // 与 addBoundItem 同口径归一（否则 remove diamond 永远匹配不到 minecraft:diamond）
                                    String itemId = raw.contains(":") ? raw : "minecraft:" + raw;
                                    ServerCommandSource source = context.getSource();
                                    if (!BoundItemsConfig.getBoundItems().contains(itemId)) {
                                        source.sendFeedback(() -> Text.translatable("command.redtale_economy.remove.not_bound", itemId), true);
                                        return 0;
                                    }
                                    BoundItemsConfig.removeBoundItem(itemId);
                                    source.sendFeedback(() -> Text.translatable("command.redtale_economy.remove.success", itemId), true);
                                    LegacyInit.LOGGER.info("Removed bound item {} by {}", itemId, source.getName());
                                    return 1;
                                })
                        )
                )
                .then(literal("list")
                        .executes(context -> {
                            ServerCommandSource source = context.getSource();
                            String list = BoundItemsConfig.getBoundItems().toString();
                            source.sendFeedback(() -> Text.translatable("command.redtale_economy.list.header", list), false);
                            return 1;
                        })
                )
                // dev/运维命令：在指定位置刷一个包裹实体，内容固定为两件带名字的纸（B20ProbeA/B）。
                // 用途：验证「实体 → 存档 → 读档 → 继续发奖」链路（NBT 往返），旧实现丢 PackageStack 时静默无奖励。
                // 权限同 bound 子树（level 4）。
                .then(literal("spawnpkg")
                        .then(argument("tier", StringArgumentType.word())
                                .suggests((context, builder) -> {
                                    // 补全直接来自 packages.json（加系列/档位不用改这里）
                                    for (com.akira.redtale.economy.legacy.data.PackageCatalog.ItemDef def
                                            : com.akira.redtale.economy.legacy.data.PackageCatalog.items()) {
                                        builder.suggest(def.id());
                                    }
                                    return builder.buildFuture();
                                })
                                .then(argument("mode", StringArgumentType.word())
                                        .suggests((context, builder) -> {
                                            builder.suggest("all");
                                            builder.suggest("choose");
                                            builder.suggest("random");
                                            return builder.buildFuture();
                                        })
                                        .executes(RECommands::spawnProbePackage)
                                )
                        )
                )
                )
        );
    }

    /** 剥掉一层成对的引号：{@code greedyString()} 不解析引号，会把它们当普通字符收下。 */
    private static String stripQuotes(String s) {
        if (s == null) return null;
        String t = s.trim();
        if (t.length() >= 2) {
            char first = t.charAt(0);
            char last = t.charAt(t.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                t = t.substring(1, t.length() - 1).trim();
            }
        }
        return t;
    }

    /** {@code /redtale bound spawnpkg <tier> <mode>}：在命令源位置刷一个内容固定的包裹实体。 */
    private static int spawnProbePackage(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        String tier = StringArgumentType.getString(context, "tier").toLowerCase(Locale.ROOT);
        String modeArg = StringArgumentType.getString(context, "mode").toLowerCase(Locale.ROOT);

        // 数据驱动后 id 有 19 个：`<series>_<tier>` / `surprise` / 旧别名。
        // 参数先按完整 id 查，查不到再补 `package_` 前缀（旧用法 spawnpkg rare 继续可用）。
        Item item = Registries.ITEM.get(new Identifier(LegacyInit.MOD_ID, tier));
        if (item == Items.AIR) {
            item = Registries.ITEM.get(new Identifier(LegacyInit.MOD_ID, "package_" + tier));
        }
        if (item == Items.AIR) {
            source.sendError(Text.literal("未知包裹 id：" + tier + "（例：package_rare / chest_legend / surprise）"));
            return 0;
        }
        PackageData.PackageMode mode = switch (modeArg) {
            case "choose" -> PackageData.PackageMode.CHOOSE;
            case "random" -> PackageData.PackageMode.RANDOM;
            default -> PackageData.PackageMode.ALL;
        };

        ItemStack stack = new ItemStack(item);
        NbtCompound data = new NbtCompound();
        data.putString("mode", mode.name());
        NbtList items = new NbtList();
        items.add(probeItem("B20ProbeA"));
        items.add(probeItem("B20ProbeB"));
        data.put("items", items);
        stack.setNbt(data);

        Vec3d pos = source.getPosition();
        ServerWorld world = source.getWorld();
        boolean choose = mode == PackageData.PackageMode.CHOOSE;
        PackageEntity entity = new PackageEntity(world, pos.x, pos.y, pos.z, stack, choose ? "idle" : "open", choose);
        if (source.getEntity() instanceof ServerPlayerEntity player) {
            entity.setOwnerUuid(player.getUuid());
        }
        world.spawnEntity(entity);

        String uuid = entity.getUuidAsString();
        // 皮肤 → 档位解析结果必须打出来：旧别名（legendary_package）与 surprise 的档位
        // 是一条纯查表链，错了零报错（粒子颜色/倍率静默回落到 normal）⇒ 只有日志能证伪。
        LegacyInit.LOGGER.info("[dev] spawnpkg arg={} item={} skin={} tier={} effective={} mode={} uuid={} at {} {} {}",
                tier, item == Items.AIR ? "air" : Registries.ITEM.getId(item).getPath(),
                entity.getPackageSkin(), entity.getPackageTier(), entity.getEffectiveTier(),
                mode, uuid, pos.x, pos.y, pos.z);
        source.sendFeedback(() -> Text.literal("[dev] spawnpkg " + tier + " " + mode + " " + uuid), false);
        return 1;
    }

    /** 探测物品：一张带自定义名的纸，用于在背包/掉落物里唯一识别。 */
    private static NbtCompound probeItem(String name) {
        ItemStack probe = new ItemStack(Items.PAPER, 1);
        probe.setCustomName(Text.literal(name));
        NbtCompound wrapper = new NbtCompound();
        wrapper.put("item", probe.writeNbt(new NbtCompound()));
        wrapper.putInt("weight", 1);
        return wrapper;
    }
}