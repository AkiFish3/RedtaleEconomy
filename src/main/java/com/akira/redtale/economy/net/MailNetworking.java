package com.akira.redtale.economy.net;

import com.akira.redtale.economy.RedtaleEconomy;
import com.akira.redtale.economy.integration.ContactMail;
import com.akira.redtale.economy.mail.MailQueue;
import com.akira.redtale.economy.mail.MailService;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.item.ItemStack;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.ArrayList;
import java.util.List;

/**
 * T62：邮件页服务端（左列表 + 右详情）。
 *
 * <p>数据只有两个来源：{@link MailQueue}（待领取货币 / 未投出的物品）与
 * {@link ContactMail}（只问「装没装、信箱满没满」，不搬信件本体）。
 *
 * <p>★领取必须服务端做★：金额与物品都在服务端的队列里，客户端只有一个下标；
 * 按下标直接改队列（而不是按名字/金额匹配），避免「同一件物品两条记录」时重复领取。
 */
public final class MailNetworking {

	private static boolean registered;

	private MailNetworking() {
	}

	/** 由 {@code MailModule.onLoad} 调用；重复调用无害。 */
	public static void register() {
		if (registered) return;
		registered = true;

		ServerPlayNetworking.registerGlobalReceiver(PacketIds.C2S_MAIL_REQ,
				(server, player, h, buf, sender) -> server.execute(() -> sendSnapshot(player)));

		ServerPlayNetworking.registerGlobalReceiver(PacketIds.C2S_MAIL_CLAIM,
				(server, player, h, buf, sender) -> {
					int kind;
					int index;
					try {
						kind = buf.readVarInt();
						index = buf.readVarInt();
					} catch (Exception e) {
						RedtaleEconomy.LOGGER.warn("[mail] 领取包解析失败（{}），已忽略", e.getMessage());
						return;
					}
					server.execute(() -> {
						claim(player, kind, index);
						// 无论成功失败都重发：客户端按钮态必须和服务端真实队列一致
						sendSnapshot(player);
					});
				});
	}

	/** 组装并下发邮件页快照。 */
	public static void sendSnapshot(ServerPlayerEntity player) {
		if (player == null) return;
		try {
			MailQueue q = MailQueue.of(player.getUuid());
			List<MailProtocol.Entry> entries = new ArrayList<>();

			if (q.pendingMoney > 0) {
				entries.add(new MailProtocol.Entry(-1, MailProtocol.KIND_MONEY,
						"待领取货币", "系统", q.pendingMoney, List.of()));
			}
			for (int i = 0; i < q.items.size() && entries.size() < 63; i++) {
				ItemStack st = q.items.get(i);
				if (st == null || st.isEmpty()) continue;
				String name = st.getName().getString();
				String id = net.minecraft.registry.Registries.ITEM.getId(st.getItem()).toString();
				entries.add(new MailProtocol.Entry(i, MailProtocol.KIND_ITEM, name, "系统", 0,
						List.of(new MailProtocol.Part(id, st.getCount(), name))));
			}

			boolean contact = ContactMail.available();
			MailProtocol.Snapshot snap = new MailProtocol.Snapshot(
					contact ? "contact" : "queue",
					ContactMail.isFull(RedtaleEconomy.server(), player.getUuid()),
					q.pendingMoney, q.items.size(), entries);

			PacketByteBuf buf = new PacketByteBuf(io.netty.buffer.Unpooled.buffer());
			MailProtocol.write(buf, snap);
			ServerPlayNetworking.send(player, PacketIds.S2C_MAIL, buf);
		} catch (Exception e) {
			RedtaleEconomy.LOGGER.error("[mail] 下发邮件快照失败：{}", e.getMessage());
		}
	}

	/** 领取一条：货币走 {@link MailService#claimMoney}（带流水），物品进背包。 */
	static void claim(ServerPlayerEntity player, int kind, int index) {
		if (player == null) return;
		// ★模块总开关★：收包点在 MailModule.onLoad 里注册，关掉模块后不会被注销 ⇒ 必须在此重判，
		// 否则「邮件关了、老界面还在」时仍能把待领货币提出来（同 shop/warehouse 一族）。
		if (!com.akira.redtale.economy.module.impl.MailModule.enabled()) return;
		try {
			if (kind == MailProtocol.KIND_MONEY) {
				long got = MailService.claimMoney(player.getUuid());
				if (got > 0) {
					player.sendMessage(net.minecraft.text.Text.literal(
							"已领取 " + com.akira.redtale.economy.economy.BalanceService.format(got)), true);
				}
				return;
			}
			if (kind == MailProtocol.KIND_ITEM) {
				int r = claimItem(player, index);
				if (r == 1) {
					player.sendMessage(net.minecraft.text.Text.literal("已取出邮件物品"), true);
				} else if (r == 0) {
					player.sendMessage(net.minecraft.text.Text.literal("背包已满，物品留在待领取里"), true);
				}
			}
		} catch (Exception e) {
			RedtaleEconomy.LOGGER.error("[mail] 领取失败：{}", e.getMessage());
		}
	}

	/**
	 * 把队列第 {@code index} 件物品塞进背包。
	 *
	 * @return 1=取出成功；0=背包满（物品原样留在队列）；-1=下标无效
	 */
	static int claimItem(ServerPlayerEntity player, int index) {
		MailQueue q = MailQueue.of(player.getUuid());
		if (index < 0 || index >= q.items.size()) return -1;
		ItemStack st = q.items.get(index);
		if (st == null || st.isEmpty()) {
			q.items.remove(index);
			q.dirty = true;
			q.save();
			return -1;
		}
		// insertStack 会把没放下的余量留在传入的栈里 ⇒ 全没放下就原样塞回队列
		ItemStack remain = st.copy();
		boolean all = player.getInventory().insertStack(remain);
		if (!all && !remain.isEmpty()) {
			q.items.set(index, remain);
			q.dirty = true;
			q.save();
			return 0;
		}
		q.items.remove(index);
		q.dirty = true;
		q.save();
		return 1;
	}
}
