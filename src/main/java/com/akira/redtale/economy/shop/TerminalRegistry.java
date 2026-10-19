package com.akira.redtale.economy.shop;

import com.akira.redtale.economy.RedtaleEconomy;
import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;

/**
 * 商店终端方块注册（R24）。
 *
 * <p>两个 id：{@code shop_terminal}（站地）与 {@code shop_terminal_wall}（墙面），
 * 共用同一个 BlockEntityType，所以 {@code /shop admin bind} 对两者都有效。
 * 方块本身不写死任何商店 id —— 绑定信息在方块实体的 NBT 里。
 */
public final class TerminalRegistry {

	private static Block terminal;
	private static Block wallTerminal;
	private static BlockEntityType<ShopTerminalBlockEntity> type;

	private TerminalRegistry() {
	}

	public static void register() {
		Block.Settings settings = AbstractBlock.Settings.create().strength(1.5f).nonOpaque();
		terminal = new ShopTerminalBlock(settings);
		wallTerminal = new ShopTerminalBlock(settings);
		Registry.register(Registries.BLOCK, RedtaleEconomy.id("shop_terminal"), terminal);
		Registry.register(Registries.BLOCK, RedtaleEconomy.id("shop_terminal_wall"), wallTerminal);
		Registry.register(Registries.ITEM, RedtaleEconomy.id("shop_terminal"),
				new BlockItem(terminal, new Item.Settings()));
		Registry.register(Registries.ITEM, RedtaleEconomy.id("shop_terminal_wall"),
				new BlockItem(wallTerminal, new Item.Settings()));
		type = FabricBlockEntityTypeBuilder.create(ShopTerminalBlockEntity::new, terminal, wallTerminal).build();
		Registry.register(Registries.BLOCK_ENTITY_TYPE, RedtaleEconomy.id("shop_terminal"), type);
		RedtaleEconomy.LOGGER.info("[terminal] 已注册商店终端方块与方块实体");
	}

	public static Block terminal() {
		return terminal;
	}

	public static Block wallTerminal() {
		return wallTerminal;
	}

	public static BlockEntityType<ShopTerminalBlockEntity> terminalType() {
		return type;
	}

	public static boolean isTerminal(Block block) {
		return block == terminal || block == wallTerminal;
	}

	/** {@code /shopterminal bind <shopId>}：准星对准终端方块，把商店 id 写进它的方块实体。 */
	public static void registerCommands(com.mojang.brigadier.CommandDispatcher<net.minecraft.server.command.ServerCommandSource> d) {
		d.register(net.minecraft.server.command.CommandManager.literal("shopterminal")
				.requires(src -> com.akira.redtale.economy.perm.Permissions.has(src,
						com.akira.redtale.economy.perm.Permissions.NODE_ADMIN))
				.then(net.minecraft.server.command.CommandManager.literal("bind")
						.then(net.minecraft.server.command.CommandManager.argument("shopId",
										com.mojang.brigadier.arguments.StringArgumentType.word())
								.executes(ctx -> {
									var src = ctx.getSource();
									var p = src.getPlayer();
									if (p == null) {
										src.sendError(net.minecraft.text.Text.literal("§c该命令只能由玩家执行"));
										return 0;
									}
									String shopId = com.mojang.brigadier.arguments.StringArgumentType
											.getString(ctx, "shopId");
									var hit = (net.minecraft.util.hit.BlockHitResult) p.raycast(6.0, 0, false);
									var be = p.getWorld().getBlockEntity(hit.getBlockPos());
									if (!(be instanceof ShopTerminalBlockEntity terminal)) {
										src.sendError(net.minecraft.text.Text.literal(
												"§c准星没有对准商店终端方块（6 格内）"));
										return 0;
									}
									terminal.setShopId(shopId);
									src.sendFeedback(() -> net.minecraft.text.Text.literal(
											"§a已把该终端绑定到商店 §f" + shopId), true);
									return 1;
								}))));
	}
}
