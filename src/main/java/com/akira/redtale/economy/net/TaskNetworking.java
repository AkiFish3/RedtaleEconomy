package com.akira.redtale.economy.net;

import com.akira.redtale.economy.RedtaleEconomy;
import com.akira.redtale.economy.module.impl.TaskModule;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * 任务面板的服务端侧（U2）：把今日任务打成一份快照发下去。
 *
 * <p>{@code have} 由服务端数背包得出，客户端拿到的是一个纯展示数字；
 * 真正的扣除发生在 {@code /tasks submit} 命令里，这里不发任何可写指令。
 */
public final class TaskNetworking {

	private TaskNetworking() {
	}

	/** 组装并下发快照。玩家为空或任务模块未启用时静默返回。 */
	public static void sendSnapshot(ServerPlayerEntity player) {
		if (player == null) return;
		try {
			TaskModule.Daily d = TaskModule.load(player.getUuid());
			List<TaskProtocol.Task> tasks = new ArrayList<>();
			for (TaskModule.Task t : d.tasks) {
				List<TaskProtocol.Item> items = new ArrayList<>();
				for (TaskModule.ItemCount ic : t.items) {
					items.add(new TaskProtocol.Item(ic.id, ic.count, countOf(player, ic.id)));
				}
				tasks.add(new TaskProtocol.Task(t.slot, t.reward,
						t.completion == null ? "submit" : t.completion, t.claimed, items));
			}
			TaskProtocol.Snapshot snap = new TaskProtocol.Snapshot(
					d.date == null ? "" : d.date, d.refreshesLeft, tasks);

			PacketByteBuf buf = new PacketByteBuf(io.netty.buffer.Unpooled.buffer());
			TaskProtocol.write(buf, snap);
			ServerPlayNetworking.send(player, PacketIds.S2C_TASKS, buf);
		} catch (Exception e) {
			RedtaleEconomy.LOGGER.error("[tasks] 下发任务快照失败：{}", e.getMessage());
		}
	}

	/** 与 TaskCommands 同口径的背包计数（只读）。 */
	private static int countOf(ServerPlayerEntity p, String itemId) {
		Item item;
		try {
			Identifier ident = Identifier.tryParse(itemId);
			if (ident == null || !Registries.ITEM.containsId(ident)) return 0;
			item = Registries.ITEM.get(ident);
		} catch (Exception e) {
			return 0;
		}
		int n = 0;
		for (int i = 0; i < p.getInventory().size(); i++) {
			ItemStack st = p.getInventory().getStack(i);
			if (!st.isEmpty() && st.getItem() == item) n += st.getCount();
		}
		return n;
	}
}
