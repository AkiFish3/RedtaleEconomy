package com.akira.redtale.economy.shop;

import com.akira.redtale.economy.lang.Lang;
import com.akira.redtale.economy.net.ShopNetworking;
import net.minecraft.block.Block;
import net.minecraft.block.BlockEntityProvider;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * 商店终端方块（R24）：右键打开绑定的商店。
 *
 * <p>同一个类同时用于站地与墙面两个 id，区别只在放置/模型；绑定命令是
 * {@code /shop admin bind <shopId>}（准星对准方块）。
 */
public class ShopTerminalBlock extends Block implements BlockEntityProvider {

	public ShopTerminalBlock(Settings settings) {
		super(settings);
	}

	@Override
	public BlockEntity createBlockEntity(BlockPos pos, BlockState state) {
		return new ShopTerminalBlockEntity(TerminalRegistry.terminalType(), pos, state);
	}

	@Override
	public ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, Hand hand, BlockHitResult hit) {
		if (world.isClient() || hand != Hand.MAIN_HAND) return ActionResult.SUCCESS;
		if (!(player instanceof ServerPlayerEntity sp)) return ActionResult.SUCCESS;
		BlockEntity be = world.getBlockEntity(pos);
		if (!(be instanceof ShopTerminalBlockEntity terminal)) return ActionResult.SUCCESS;
		String shopId = terminal.shopId();
		if (shopId == null || shopId.isEmpty()) {
			sp.sendMessage(Text.literal(Lang.msg("terminal.unbound")), false);
			return ActionResult.SUCCESS;
		}
		ShopNetworking.sendOpen(sp, shopId);
		return ActionResult.SUCCESS;
	}
}
