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
                // dev/运维命令：权重数组的「格子空了就归 1」三条入口自检（B53）。
                // 用途：`EditablePackageInventory` 有三个清空格子的路径（removeStack×2 / setStack），
                // 任何一个漏了清权重都会让新拖入的物品静默继承上一件的权重（零报错）。
                // 不需要客户端、不需要 GUI，纯逻辑 ⇒ 用 RCON 就能取证。
                .then(literal("weightprobe")
                        .executes(RECommands::weightProbe)
                )
                // dev/运维命令：绑定归属判定（名字 vs UUID）自检（B57）。
                // 背景：旧实现只存名字 ⇒ 玩家改名后绑定物永久锁死；旧名字被别人抢注则**直接易主**。
                // 现在 UUID 与名字双写，有 UUID 就只认 UUID；旧档只有名字时首次交互补 UUID。
                // 纯逻辑、不需要客户端 ⇒ RCON 就能判。
                .then(literal("ownerprobe")
                        .executes(RECommands::ownerProbe)
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

    /**
     * {@code /redtale bound weightprobe}：权重数组「格子空了就归 1」的三条路径自检（B53）。
     *
     * <p>为什么需要这条命令：权重按**格子**存，不按物品存。清空格子的路径有三条 ——
     * {@code removeStack(slot, amount)}（拖出/右键删）、{@code removeStack(slot)}（部分原版分支）、
     * {@code setStack(slot, EMPTY)}（{@code quickMove} 里 shift-click 移出的写法）。
     * 任一条漏清 ⇒ 玩家 shift 移出后往同一格拖新物品，新物品静默继承旧权重
     * （界面完全看不出来，只有抽取概率异常）。纯逻辑、不需要客户端 ⇒ RCON 就能判。
     */
    private static int weightProbe(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        Item pkgItem = Registries.ITEM.get(new Identifier(LegacyInit.MOD_ID, "package_normal"));
        if (pkgItem == Items.AIR) {
            source.sendError(Text.literal("[dev][wprobe] 找不到 package_normal（物品未注册）"));
            return 0;
        }

        int fail = 0;
        // ① 拖出：Slot.onTakeItem → Inventory.removeStack(slot, amount)
        {
            com.akira.redtale.economy.legacy.misc.EditablePackageInventory inv = newProbeInventory(pkgItem);
            inv.setStack(0, new ItemStack(Items.DIAMOND, 1));
            inv.setWeight(0, 42);
            inv.removeStack(0, 1);
            fail += expect(source, "removeStack(slot,amount)", 1, inv.getWeight(0));
        }
        // ② removeStack(slot) 单参重载
        {
            com.akira.redtale.economy.legacy.misc.EditablePackageInventory inv = newProbeInventory(pkgItem);
            inv.setStack(0, new ItemStack(Items.DIAMOND, 1));
            inv.setWeight(0, 42);
            inv.removeStack(0);
            fail += expect(source, "removeStack(slot)", 1, inv.getWeight(0));
        }
        // ③ shift-click 移出：ScreenHandler.quickMove 里 `slot.setStack(ItemStack.EMPTY)`
        //    链路 Slot.setStack → setStackNoCallbacks → Inventory.setStack（不经过 removeStack）
        {
            com.akira.redtale.economy.legacy.misc.EditablePackageInventory inv = newProbeInventory(pkgItem);
            inv.setStack(0, new ItemStack(Items.DIAMOND, 1));
            inv.setWeight(0, 42);
            inv.setStack(0, ItemStack.EMPTY);
            fail += expect(source, "setStack(EMPTY)", 1, inv.getWeight(0));
        }
        // ④ 负对照：格子仍有物品时**不许**重置（原位换物品 = 权重跟格子走，规格 §四）
        {
            com.akira.redtale.economy.legacy.misc.EditablePackageInventory inv = newProbeInventory(pkgItem);
            inv.setStack(0, new ItemStack(Items.DIAMOND, 1));
            inv.setWeight(0, 42);
            inv.setStack(0, new ItemStack(Items.APPLE, 1));
            fail += expect(source, "setStack(non-empty)", 42, inv.getWeight(0));
        }
        // ⑤ 权重能真落进 NBT 并读回（前四条都靠内存数组，若不落盘则编辑器白改）
        {
            com.akira.redtale.economy.legacy.misc.EditablePackageInventory inv = newProbeInventory(pkgItem);
            inv.setStack(0, new ItemStack(Items.DIAMOND, 1));
            inv.setWeight(0, 77);
            inv.saveToNbt();
            com.akira.redtale.economy.legacy.misc.EditablePackageInventory reread =
                    new com.akira.redtale.economy.legacy.misc.EditablePackageInventory(inv.boundStack());
            fail += expect(source, "nbt-roundtrip", 77, reread.getWeight(0));
        }

        // ⑥ ALL 模式不看权重：weight=0 的条目**照样要发**（规格 §三「ALL = 全部给」；
        //    weight 只在 RANDOM 里表示「跳过」）。旧实现两处 ALL 分支都带 `weight() > 0`
        //    ⇒ 编辑器把某格调到 0 再切成 ALL，那件物品静默不发（条目还在 NBT 里，界面看不出来）。
        //    这里按「三条目 ⇒ 应发 2 件」判，比单条断言更贴近真实发放循环（旧实现给 1 件）。
        {
            java.util.List<PackageData.PackageItem> allItems = java.util.List.of(
                    new PackageData.PackageItem(new ItemStack(Items.DIAMOND, 1), 0),
                    new PackageData.PackageItem(new ItemStack(Items.APPLE, 1), 5),
                    new PackageData.PackageItem(ItemStack.EMPTY, 9));
            int granted = 0;
            for (PackageData.PackageItem it : allItems) {
                if (PackageEntity.shouldGiveInAll(it)) granted++;
            }
            fail += expect(source, "all-mode 发放件数(0/5/空)", 2, granted);
        }
        // ⑦ 负对照：空物品即使权重很高也不能发（否则 ALL 包会发放"空气"并占一个发放节拍）
        {
            PackageData.PackageItem emptyItem =
                    new PackageData.PackageItem(ItemStack.EMPTY, 5);
            fail += expectBool(source, "all-mode 空物品不发", false,
                    PackageEntity.shouldGiveInAll(emptyItem));
        }

        // ⑧ 裸 id 包裹（无 NBT）＝ mode 默认 ALL + items 空表 ⇒ 必须判成「没有可发条目」。
        //    旧链路：右键 → 刷实体 → 一次都不发 → 80 tick 消失，物品扣了、日志 0 行。
        //    ±⑨ 反证这条判据不是恒 false（真实包裹必须判 true，否则会把正常包裹全拒了）。
        {
            PackageData bare = PackageData.fromNbt(null);
            fail += expectBool(source, "空包裹(null NBT) 不可发", false, bare.hasGiveableItem());
        }
        {
            java.util.List<PackageData.PackageItem> mixed = java.util.List.of(
                    new PackageData.PackageItem(ItemStack.EMPTY, 3),
                    new PackageData.PackageItem(ItemStack.EMPTY, 1),
                    new PackageData.PackageItem(new ItemStack(Items.DIAMOND, 1), 0));
            PackageData data = new PackageData(PackageData.PackageMode.ALL, mixed);
            fail += expectBool(source, "有 1 条非空即可发", true, data.hasGiveableItem());
        }

        final int failures = fail;
        LegacyInit.LOGGER.info("[dev][wprobe] done fail={} / 9", failures);
        source.sendFeedback(() -> Text.literal("[dev][wprobe] fail=" + failures + " / 9"), false);
        return failures == 0 ? 1 : 0;
    }

    /**
     * {@code /redtale bound ownerprobe}：归属判定「名字 / UUID」自检（B57）。
     *
     * <p>判据全部走 {@link com.akira.redtale.economy.legacy.bound.BoundItemHandler#ownerMatches}
     * （生产代码同一个方法），因此能证伪「UUID 写了但判定不看」这种假修复。</p>
     */
    private static int ownerProbe(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        java.util.UUID alice = java.util.UUID.fromString("00000000-0000-4000-8000-0000000000a1");
        java.util.UUID bob = java.util.UUID.fromString("00000000-0000-4000-8000-0000000000b2");
        int[] c = new int[]{0, 0}; // [fail, total]

        // ① 绑定必须双写（旧实现只写名字）
        ItemStack bound = new ItemStack(Items.DIAMOND);
        com.akira.redtale.economy.legacy.bound.BoundItemHandler.writeOwner(bound, alice, "Alice");
        check(source, c, "绑定后 hasOwner", true,
                com.akira.redtale.economy.legacy.bound.BoundItemHandler.hasOwner(bound));
        check(source, c, "绑定后 uuid=Alice", true,
                alice.equals(com.akira.redtale.economy.legacy.bound.BoundItemHandler.getOwnerUuid(bound)));
        check(source, c, "绑定后 name=Alice", true,
                "Alice".equals(com.akira.redtale.economy.legacy.bound.BoundItemHandler.getOwnerName(bound)));

        // ② 正常路径：本人 / 改名（UUID 不变）
        check(source, c, "本人可用", true, matches(bound, alice, "Alice"));
        check(source, c, "改名后仍可用", true, matches(bound, alice, "Alice2"));

        // ③ ★核心负样本★ 同名但 UUID 不同 ⇒ 必须拒绝
        //    旧实现按名字判 ⇒ 这里会 true（旧名字被抢注即可易主，改名则本人永久锁死）
        check(source, c, "同名异UUID被拒", false, matches(bound, bob, "Alice"));

        // ④ 旧档（只有名字）：仍要能用，且迁移后升级成 UUID
        ItemStack legacy = new ItemStack(Items.DIAMOND);
        legacy.getOrCreateNbt().putString("BoundOwnerName", "Alice");
        check(source, c, "旧档(仅名字)本人可用", true, matches(legacy, alice, "Alice"));
        // 旧档没有 UUID，名字是唯一钥匙 ⇒ 这里必须换**不同的名字**才算「他人」；
        // 用「同名不同 UUID」测旧档永远是 true（旧档压根没有 UUID 可比），那会把判据本身写错。
        check(source, c, "旧档(仅名字)他人被拒", false, matches(legacy, bob, "Bob"));
        com.akira.redtale.economy.legacy.bound.BoundItemHandler.adoptOwner(legacy, alice);
        check(source, c, "迁移后 uuid 已写入", true,
                alice.equals(com.akira.redtale.economy.legacy.bound.BoundItemHandler.getOwnerUuid(legacy)));
        check(source, c, "迁移后同名异UUID被拒", false, matches(legacy, bob, "Alice"));

        // ⑤ 无主 = 公共；只有 UUID 没有名字也照样按 UUID 判
        ItemStack free = new ItemStack(Items.DIAMOND);
        check(source, c, "无主=公共", true, matches(free, bob, "Bob"));
        ItemStack uuidOnly = new ItemStack(Items.DIAMOND);
        uuidOnly.getOrCreateNbt().putUuid("BoundOwnerUuid", alice);
        check(source, c, "仅UUID本人可用", true, matches(uuidOnly, alice, "Whatever"));
        check(source, c, "仅UUID他人被拒", false, matches(uuidOnly, bob, "Alice"));

        // ⑥ 损坏的 UUID 不能变成永久锁死 ⇒ 回退名字
        ItemStack broken = new ItemStack(Items.DIAMOND);
        NbtCompound btag = broken.getOrCreateNbt();
        btag.putString("BoundOwnerName", "Alice");
        btag.putString("BoundOwnerUuid", "not-a-uuid");
        check(source, c, "UUID损坏回退名字", true, matches(broken, alice, "Alice"));

        final int failures = c[0];
        final int total = c[1];
        LegacyInit.LOGGER.info("[dev][oprobe] done fail={} / {}", failures, total);
        source.sendFeedback(() -> Text.literal("[dev][oprobe] fail=" + failures + " / " + total), false);
        return failures == 0 ? 1 : 0;
    }

    private static boolean matches(ItemStack stack, java.util.UUID uuid, String name) {
        return com.akira.redtale.economy.legacy.bound.BoundItemHandler.ownerMatches(stack, uuid, name);
    }

    /** 计数版判据：分母自动累加（R113：别写死分母）。 */
    private static void check(ServerCommandSource source, int[] c, String name, boolean want, boolean got) {
        c[1]++;
        boolean ok = want == got;
        if (!ok) c[0]++;
        LegacyInit.LOGGER.info("[dev][oprobe] {} expect={} actual={} {}", name, want, got, ok ? "PASS" : "FAIL");
        source.sendFeedback(() -> Text.literal("[dev][oprobe] " + name + " expect=" + want
                + " actual=" + got + (want == got ? " PASS" : " FAIL")), false);
    }

    /** 造一件带固定内容的探针包裹（RANDOM 模式、一条 items）。 */    private static com.akira.redtale.economy.legacy.misc.EditablePackageInventory newProbeInventory(Item pkgItem) {
        ItemStack stack = new ItemStack(pkgItem);
        NbtCompound nbt = new NbtCompound();
        nbt.putString("mode", PackageData.PackageMode.RANDOM.name());
        NbtList items = new NbtList();
        items.add(probeItem("B53ProbeA"));
        nbt.put("items", items);
        stack.setNbt(nbt);
        return new com.akira.redtale.economy.legacy.misc.EditablePackageInventory(stack);
    }

    /** 打印一条判据，返回失败计数（0/1）。 */
    private static int expect(ServerCommandSource source, String name, int want, int got) {
        boolean ok = want == got;
        LegacyInit.LOGGER.info("[dev][wprobe] {} expect={} actual={} {}", name, want, got, ok ? "PASS" : "FAIL");
        source.sendFeedback(() -> Text.literal("[dev][wprobe] " + name + " expect=" + want
                + " actual=" + got + (want == got ? " PASS" : " FAIL")), false);
        return ok ? 0 : 1;
    }

    /** 布尔版判据（⑥⑦ 用），日志口径与 {@link #expect} 一致。 */
    private static int expectBool(ServerCommandSource source, String name, boolean want, boolean got) {
        boolean ok = want == got;
        LegacyInit.LOGGER.info("[dev][wprobe] {} expect={} actual={} {}", name, want, got, ok ? "PASS" : "FAIL");
        source.sendFeedback(() -> Text.literal("[dev][wprobe] " + name + " expect=" + want
                + " actual=" + got + (ok ? " PASS" : " FAIL")), false);
        return ok ? 0 : 1;
    }
}