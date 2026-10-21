package com.akira.redtale.economy.client.ui.hub;

import com.akira.redtale.economy.net.ClientSend;
import com.akira.redtale.economy.net.PacketIds;
import com.akira.redtale.economy.net.TaskProtocol;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.network.PacketByteBuf;

/**
 * T56：Hub 任务页的客户端收包（只存状态，<b>不打开任何界面</b>）。
 *
 * <p>和 {@code client/tasks/TaskClientNetworking} 的区别：那条 {@code S2C_TASKS} 的语义是
 * 「弹出独立任务面板」，在 Hub 里领奖会盖住 Hub。这里走 {@code S2C_TASKS_HUB}，
 * 收包只更新 {@link #LAST} 让页面重画。
 */
@Environment(EnvType.CLIENT)
public final class TasksHubClientNetworking {

	private TasksHubClientNetworking() {
	}

	/** 最近一份快照（页面直接读，不做本地缓存/推算）。 */
	public static volatile TaskProtocol.Snapshot LAST = TaskProtocol.Snapshot.empty();
	public static volatile long LAST_AT;

	/** 上一次告诉服务端的「是否在 Hub 任务页」，用来避免每次切页都发一个包。 */
	private static boolean lastHub;

	public static void register() {
		ClientPlayNetworking.registerGlobalReceiver(PacketIds.S2C_TASKS_HUB,
				(client, handler, buf, sender) -> {
					TaskProtocol.Snapshot snap = TaskProtocol.read(buf);
					client.execute(() -> {
						LAST = snap;
						LAST_AT = System.currentTimeMillis();
					});
				});
	}

	/** 页面切换时同步「Hub 模式」标记（只在真变化时发包）。 */
	public static void sync(HubPage page) {
		boolean hub = page == HubPage.TASKS;
		if (hub == lastHub) return;
		lastHub = hub;
		request(hub);
	}

	/** 置位/清除 Hub 模式；{@code hub=true} 时服务端会立刻回一份快照。 */
	public static void request(boolean hub) {
		lastHub = hub;
		if (!ClientSend.ready()) return;
		PacketByteBuf buf = new PacketByteBuf(io.netty.buffer.Unpooled.buffer());
		buf.writeBoolean(hub);
		ClientSend.send(PacketIds.C2S_TASKS_HUB_REQ, buf);
	}
}
