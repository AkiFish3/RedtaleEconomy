package com.akira.redtale.economy.shop;

import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.math.BlockPos;

/**
 * 商店终端方块实体（R24）：只存一个 {@code shopId}。
 *
 * <p>没有渲染、没有 tick、不需要客户端同步——玩家右键时服务端读它决定打开哪个商店。
 */
public final class ShopTerminalBlockEntity extends BlockEntity {

	private String shopId = "";

	/** 注册用的工厂构造器（类型在注册完成后由 {@link TerminalRegistry#terminalType()} 提供）。 */
	public ShopTerminalBlockEntity(BlockPos pos, BlockState state) {
		super(TerminalRegistry.terminalType(), pos, state);
	}

	public ShopTerminalBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
		super(type, pos, state);
	}

	public String shopId() {
		return shopId;
	}

	public void setShopId(String id) {
		this.shopId = id == null ? "" : id.trim();
		markDirty();
	}

	@Override
	public void readNbt(NbtCompound nbt) {
		super.readNbt(nbt);
		shopId = nbt.contains("shopId") ? nbt.getString("shopId") : "";
	}

	@Override
	protected void writeNbt(NbtCompound nbt) {
		super.writeNbt(nbt);
		nbt.putString("shopId", shopId);
	}
}
