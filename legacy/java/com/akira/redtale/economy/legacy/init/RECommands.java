package com.akira.redtale.economy.legacy.init;

import com.akira.redtale.economy.legacy.LegacyInit;
import com.akira.redtale.economy.legacy.bound.BoundItemsConfig;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.command.CommandSource;
import net.minecraft.registry.Registries;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import static net.minecraft.server.command.CommandManager.literal;
import static net.minecraft.server.command.CommandManager.argument;

public class RECommands {

    private static final SuggestionProvider<ServerCommandSource> ITEM_SUGGESTIONS = (context, builder) -> {
        CommandSource.suggestIdentifiers(Registries.ITEM.getIds(), builder);
        return builder.buildFuture();
    };

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(literal("redtale")
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
                        .then(argument("item", StringArgumentType.word())
                                .suggests(ITEM_SUGGESTIONS)
                                .executes(context -> {
                                    String input = StringArgumentType.getString(context, "item");
                                    ServerCommandSource source = context.getSource();
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
                        .then(argument("item", StringArgumentType.word())
                                .suggests((context, builder) -> {
                                    for (String id : BoundItemsConfig.getBoundItems()) {
                                        builder.suggest(id);
                                    }
                                    return builder.buildFuture();
                                })
                                .executes(context -> {
                                    String itemId = StringArgumentType.getString(context, "item");
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
        );
    }
}