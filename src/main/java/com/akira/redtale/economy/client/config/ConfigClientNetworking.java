package com.akira.redtale.economy.client.config;

import com.akira.redtale.economy.RedtaleEconomy;
import com.akira.redtale.economy.net.ConfigProtocol;
import com.akira.redtale.economy.net.PacketIds;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

/** 配置编辑器的客户端网络层（R14 V1）。 */
@Environment(EnvType.CLIENT)
public final class ConfigClientNetworking {

	private ConfigClientNetworking() {
	}

	public static void register() {
		ClientPlayNetworking.registerGlobalReceiver(PacketIds.S2C_CONFIG_OPEN, (client, handler, buf, sender) -> {
			String[] file = {""};
			List<ConfigProtocol.Entry> entries = new ArrayList<>();
			ConfigProtocol.readEntries(buf, file, entries);
			client.execute(() -> client.setScreen(new ConfigEditorScreen(file[0], entries)));
		});

		ClientPlayNetworking.registerGlobalReceiver(PacketIds.S2C_CONFIG_RESULT, (client, handler, buf, sender) -> {
			boolean ok = buf.readBoolean();
			String msg = buf.readString(512);
			client.execute(() -> {
				if (client.player != null) client.player.sendMessage(Text.literal(msg), false);
				if (ok && client.currentScreen instanceof ConfigEditorScreen) client.setScreen(null);
			});
		});

		RedtaleEconomy.LOGGER.info("[edit] 配置编辑器接收器已注册");
	}

	public static void save(String file, List<ConfigProtocol.Entry> entries) {
		if (MinecraftClient.getInstance().getNetworkHandler() == null) return;
		PacketByteBuf buf = new PacketByteBuf(io.netty.buffer.Unpooled.buffer());
		ConfigProtocol.writeSave(buf, file, entries);
		ClientPlayNetworking.send(PacketIds.C2S_CONFIG_SAVE, buf);
	}
}
