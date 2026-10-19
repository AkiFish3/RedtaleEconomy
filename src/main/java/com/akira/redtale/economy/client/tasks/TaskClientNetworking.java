package com.akira.redtale.economy.client.tasks;

import com.akira.redtale.economy.RedtaleEconomy;
import com.akira.redtale.economy.net.PacketIds;
import com.akira.redtale.economy.net.TaskProtocol;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

/** 任务面板的客户端收包（U2）。 */
@Environment(EnvType.CLIENT)
public final class TaskClientNetworking {

	private TaskClientNetworking() {
	}

	public static void register() {
		ClientPlayNetworking.registerGlobalReceiver(PacketIds.S2C_TASKS, (client, handler, buf, sender) -> {
			TaskProtocol.Snapshot snap = TaskProtocol.read(buf);
			client.execute(() -> client.setScreen(new TaskScreen(snap)));
		});
		RedtaleEconomy.LOGGER.info("[tasks] 任务面板接收器已注册");
	}
}
