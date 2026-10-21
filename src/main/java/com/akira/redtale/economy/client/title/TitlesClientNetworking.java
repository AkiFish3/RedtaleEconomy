package com.akira.redtale.economy.client.title;

import com.akira.redtale.economy.net.PacketIds;
import com.akira.redtale.economy.net.ClientSend;
import com.akira.redtale.economy.net.TitleProtocol;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.network.PacketByteBuf;

@Environment(EnvType.CLIENT)
public final class TitlesClientNetworking {

	public static volatile TitleProtocol.Snapshot LAST = TitleProtocol.Snapshot.empty();

	private TitlesClientNetworking() {
	}

	public static void register() {
		ClientPlayNetworking.registerGlobalReceiver(PacketIds.S2C_TITLES, (client, handler, buf, sender) -> {
			TitleProtocol.Snapshot s = TitleProtocol.read(buf);
			client.execute(() -> LAST = s);
		});
	}

	public static void request() {
		ClientSend.send(PacketIds.C2S_TITLE_REQ,
				new PacketByteBuf(io.netty.buffer.Unpooled.buffer()));
	}

	public static void select(String title) {
		select(title, -1);
	}

	/** T60：装备到指定槽（{@code slot<0} = 追加）。 */
	public static void select(String title, int slot) {
		PacketByteBuf buf = new PacketByteBuf(io.netty.buffer.Unpooled.buffer());
		buf.writeString(title, 128);
		buf.writeVarInt(slot);
		ClientSend.send(PacketIds.C2S_TITLE_SELECT, buf);
	}

	/** T60：卸下第 {@code slot} 槽。 */
	public static void unequipSlot(int slot) {
		PacketByteBuf buf = new PacketByteBuf(io.netty.buffer.Unpooled.buffer());
		buf.writeVarInt(slot);
		ClientSend.send(PacketIds.C2S_TITLE_UNEQUIP, buf);
	}

	public static void remove(String title) {
		PacketByteBuf buf = new PacketByteBuf(io.netty.buffer.Unpooled.buffer());
		buf.writeString(title, 128);
		ClientSend.send(PacketIds.C2S_TITLE_REMOVE, buf);
	}

	public static void clear() {
		ClientSend.send(PacketIds.C2S_TITLE_CLEAR,
				new PacketByteBuf(io.netty.buffer.Unpooled.buffer()));
	}
}
