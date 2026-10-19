package com.akira.redtale.economy.net;

import com.akira.redtale.economy.perm.Permissions;
import com.akira.redtale.economy.RedtaleEconomy;
import com.akira.redtale.economy.config.ConfigEditor;
import com.akira.redtale.economy.config.CoreConfig;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.List;

/**
 * 可视化配置编辑器的网络层（R14 V1）。
 *
 * <p>打开时服务端下发标量字段快照；保存时客户端回传全部字段，服务端再次校验权限、
 * 逐条解析并写盘。客户端提交的任何内容都当作不可信输入。
 */
public final class ConfigNetworking {

	private ConfigNetworking() {
	}

	public static void registerServer() {
		ServerPlayNetworking.registerGlobalReceiver(PacketIds.C2S_CONFIG_SAVE, (server, player, h, buf, sender) -> {
			String[] file = {""};
			List<ConfigProtocol.Entry> entries = ConfigProtocol.readSave(buf, file);
			server.execute(() -> handleSave(player, file[0], entries));
		});
	}

	private static void handleSave(ServerPlayerEntity player, String file,
			List<ConfigProtocol.Entry> entries) {
		if (!Permissions.has(player, Permissions.NODE_OWNER)) {
			sendResult(player, false, "§c没有权限修改配置");
			return;
		}
		ConfigEditor.Result r = ConfigEditor.apply(file, entries);
		String msg;
		if (r.ok()) {
			msg = "§a已保存 " + file + "：应用 " + r.applied() + " 项"
					+ (r.rejected() > 0 ? "，§e拒绝 " + r.rejected() + " 项" : "")
					+ (r.message().isEmpty() ? "" : "，§e校验修正：" + r.message());
		} else {
			msg = "§c保存失败：" + r.message();
		}
		sendResult(player, r.ok(), msg);
	}

	public static void sendOpen(ServerPlayerEntity player, String file, List<ConfigProtocol.Entry> entries) {
		PacketByteBuf buf = new PacketByteBuf(io.netty.buffer.Unpooled.buffer());
		try {
			ConfigProtocol.writeEntries(buf, file, entries);
			ServerPlayNetworking.send(player, PacketIds.S2C_CONFIG_OPEN, buf);
		} catch (Exception e) {
			RedtaleEconomy.LOGGER.error("[edit] 下发配置快照失败：{}", e.getMessage());
		}
	}

	public static void sendResult(ServerPlayerEntity player, boolean ok, String message) {
		PacketByteBuf buf = new PacketByteBuf(io.netty.buffer.Unpooled.buffer());
		try {
			buf.writeBoolean(ok);
			buf.writeString(message == null ? "" : message, 512);
			ServerPlayNetworking.send(player, PacketIds.S2C_CONFIG_RESULT, buf);
		} catch (Exception e) {
			RedtaleEconomy.LOGGER.error("[edit] 下发保存结果失败：{}", e.getMessage());
		}
	}
}
