package com.akira.redtale.economy.integration;

import dev.ftb.mods.ftbquests.quest.Quest;
import dev.ftb.mods.ftbquests.quest.reward.Reward;
import dev.ftb.mods.ftbquests.quest.reward.RewardType;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;

/**
 * FTB Quests 自定义奖励类型 {@code redtale_economy:bp_xp}（R22 第一优先级）。
 *
 * <p>只是「标记 + 数值」：经验在 {@code ObjectCompletedEvent.QUEST} 里统一发给队伍在线成员，
 * 所以 {@link #claim} 故意不做事，避免同一次完成被发两遍。
 */
public final class BpXpReward extends Reward {

	public static final Identifier TYPE_ID = new Identifier("redtale_economy", "bp_xp");

	private long xp;

	public BpXpReward(long id, Quest quest) {
		super(id, quest);
	}

	public long getXp() {
		return xp;
	}

	public void setXp(long xp) {
		this.xp = Math.max(0, xp);
	}

	@Override
	public RewardType getType() {
		return FtbqIntegration.bpXpType();
	}

	@Override
	public void claim(ServerPlayerEntity player, boolean notify) {
		// 经验由 ObjectCompletedEvent.QUEST 统一发放（队伍在线成员各一份），这里不重复发
	}

	@Override
	public void writeData(NbtCompound tag) {
		super.writeData(tag);
		tag.putLong("redtale_bp_xp", xp);
	}

	@Override
	public void readData(NbtCompound tag) {
		super.readData(tag);
		xp = tag.getLong("redtale_bp_xp");
	}

	@Override
	public void writeNetData(PacketByteBuf buf) {
		super.writeNetData(buf);
		buf.writeLong(xp);
	}

	@Override
	public void readNetData(PacketByteBuf buf) {
		super.readNetData(buf);
		xp = buf.readLong();
	}
}
