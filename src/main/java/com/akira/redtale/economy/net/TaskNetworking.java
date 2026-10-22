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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 任务面板的服务端侧（U2）：把今日任务打成一份快照发下去。
 *
 * <p>{@code have} 由服务端数背包得出，客户端拿到的是一个纯展示数字；
 * 真正的扣除发生在 {@code /tasks submit} 命令里，这里不发任何可写指令。
 *
 * <p>T56：加了「Hub 模式」。Hub 的任务页和老的独立面板（{@code TaskScreen}）用同一份数据，
 * 但呈现互斥 —— 玩家在 Hub 里领奖励时不能再弹一个全屏面板盖住 Hub。
 * 客户端进/退任务页时发一个布尔标记（{@link PacketIds#C2S_TASKS_HUB_REQ}），服务端按标记选通道：
 * <ul>
 *   <li>hub=true ⇒ 走 {@link PacketIds#S2C_TASKS_HUB}（只刷新页面）</li>
 *   <li>hub=false ⇒ 走 {@link PacketIds#S2C_TASKS}（老行为：打开独立面板）</li>
 * </ul>
 * 标记是玩家级的，断线时客户端会补发 false（{@code HubScreen.removed()}）。
 */
public final class TaskNetworking {

	/** 当前停在 Hub 任务页的玩家（决定快照走哪条通道）。 */
	private static final Set<UUID> HUB_MODE = ConcurrentHashMap.newKeySet();
	/** 注册只做一次：任务模块 onLoad 与热重载都会调进来。 */
	private static boolean registered;

	private TaskNetworking() {
	}

	/** 服务端收包注册（由 {@code TaskModule.onLoad} 调用，重复调用无害）。 */
	public static void register() {
		if (registered) return;
		registered = true;
		ServerPlayNetworking.registerGlobalReceiver(PacketIds.C2S_TASKS_HUB_REQ,
				(server, player, h, buf, sender) -> {
					boolean hub;
					try {
						hub = buf.readBoolean();
					} catch (Exception e) {
						RedtaleEconomy.LOGGER.warn("[tasks] Hub 请求包解析失败（{}），已忽略", e.getMessage());
						return;
					}
					server.execute(() -> {
						setHubMode(player.getUuid(), hub);
						if (hub) sendHub(player);
					});
				});
	}

	public static void setHubMode(UUID uuid, boolean hub) {
		if (uuid == null) return;
		if (hub) HUB_MODE.add(uuid);
		else HUB_MODE.remove(uuid);
	}

	public static boolean hubMode(UUID uuid) {
		return uuid != null && HUB_MODE.contains(uuid);
	}

	public static void clearHubMode(UUID uuid) {
		if (uuid != null) HUB_MODE.remove(uuid);
	}

	/** 组装并下发快照。玩家为空时静默返回；Hub 模式下自动改走 Hub 通道。 */
	public static void sendSnapshot(ServerPlayerEntity player) {
		if (player == null) return;
		if (hubMode(player.getUuid())) {
			sendHub(player);
			return;
		}
		try {
			TaskProtocol.Snapshot snap = build(player);
			PacketByteBuf buf = new PacketByteBuf(io.netty.buffer.Unpooled.buffer());
			TaskProtocol.write(buf, snap);
			ServerPlayNetworking.send(player, PacketIds.S2C_TASKS, buf);
		} catch (Exception e) {
			RedtaleEconomy.LOGGER.error("[tasks] 下发任务快照失败：{}", e.getMessage());
		}
	}

	/** T56：只刷新 Hub 任务页，不打开任何界面。 */
	public static void sendHub(ServerPlayerEntity player) {
		if (player == null) return;
		if (!ServerPlayNetworking.canSend(player, PacketIds.S2C_TASKS_HUB)) return;
		try {
			TaskProtocol.Snapshot snap = build(player);
			PacketByteBuf buf = new PacketByteBuf(io.netty.buffer.Unpooled.buffer());
			TaskProtocol.write(buf, snap);
			ServerPlayNetworking.send(player, PacketIds.S2C_TASKS_HUB, buf);
		} catch (Exception e) {
			RedtaleEconomy.LOGGER.error("[tasks] 下发 Hub 任务页失败：{}", e.getMessage());
		}
	}

	private static TaskProtocol.Snapshot build(ServerPlayerEntity player) {
		// T06：日 + 周两套任务合并成一份快照下发（各自带 period，界面按它分栏）。
		TaskModule.Daily daily = TaskModule.load(player.getUuid());
		List<TaskProtocol.Task> tasks = new ArrayList<>();
		collect(player, daily, tasks);
		TaskModule.Daily weekly = TaskModule.loadWeekly(player.getUuid());
		collect(player, weekly, tasks);
		return new TaskProtocol.Snapshot(daily.date == null ? "" : daily.date, daily.refreshesLeft, tasks,
				weekly.date == null ? "" : weekly.date);
	}

	private static void collect(ServerPlayerEntity player, TaskModule.Daily d, List<TaskProtocol.Task> out) {
		for (TaskModule.Task t : d.tasks) {
			List<TaskProtocol.Item> items = new ArrayList<>();
			// T05：事件型任务（kill/mine/travel）的进度在服务端记账，不数背包
			int evHave = TaskModule.eventHave(player.getUuid(), d, t);
			for (TaskModule.ItemCount ic : t.items) {
				int have = evHave >= 0 ? evHave : countOf(player, ic.id);
				items.add(new TaskProtocol.Item(ic.id, ic.count, have));
			}
			// 标题优先用任务自带的 label：事件型任务没有 pool（pool=""），
			// 原来只发 labelOf(pool) ⇒ 界面上「本周挖矿」变成内部槽号 #100。
			String label = t.label == null || t.label.isBlank() ? TaskModule.labelOf(t.pool) : t.label;
			out.add(new TaskProtocol.Task(t.slot, t.reward,
					t.completion == null ? "submit" : t.completion, t.claimed, items,
					label, d.period));
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
