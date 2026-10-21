package com.akira.redtale.economy.net;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.util.Identifier;

/**
 * 客户端发包的唯一出口（崩溃修复）。
 *
 * <p>原版 {@code ClientPlayNetworking.send} 在 {@code client.getNetworkHandler() == null}
 * 时<b>直接抛 IllegalStateException("Cannot send packets when not in game!")</b>，
 * 而 Hub 的侧栏点击、页面刷新这类动作在「还没进世界 / 正在断线」时照样会被触发 ——
 * 一次点击就能把整个客户端炸掉（见 {@code crash-2026-10-21_19.09.10-client.txt}：
 * HubScreen.handleClick → MarketClientNetworking.request）。
 *
 * <p>所以：所有 UI 发包都必须过这里。不在局内就<b>静默丢弃并释放 buf</b>
 * （不是报错，也不是排队重发 —— 重发会让界面拿到上一个连接的数据）。
 */
@Environment(EnvType.CLIENT)
public final class ClientSend {

	private ClientSend() {
	}

	/** 当前是否处于「可以发包」的状态（进过世界且连接还在）。 */
	public static boolean ready() {
		MinecraftClient mc = MinecraftClient.getInstance();
		return mc != null && mc.getNetworkHandler() != null && mc.player != null;
	}

	/**
	 * 发包；不在局内时静默丢弃。
	 *
	 * @return 真的发出去了才返回 true（调用方一般不用管，仅诊断用）
	 */
	public static boolean send(Identifier channel, PacketByteBuf buf) {
		if (!ready()) {
			// 未发送的 buf 必须释放：它是 Unpooled 直接缓冲，不释放就是 ByteBuf 泄漏
			buf.release();
			return false;
		}
		ClientPlayNetworking.send(channel, buf);
		return true;
	}
}
