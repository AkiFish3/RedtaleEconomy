package com.akira.redtale.economy.net;

import com.akira.redtale.economy.RedtaleEconomy;
import com.akira.redtale.economy.module.impl.CheckinModule;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.ArrayList;
import java.util.List;

/**
 * 签到面板的服务端侧（U3）：把连续天数与循环内每天的状态打成快照发下去。
 *
 * <p>每天能拿多少直接问 {@link CheckinModule#tierOf(int)}，不在客户端重复一份档位表——
 * 改 {@code checkin.json} 的 tiers，面板跟着变。
 */
public final class CheckinNetworking {

	private CheckinNetworking() {
	}

	public static void sendSnapshot(ServerPlayerEntity player) {
		if (player == null) return;
		try {
			CheckinModule.State st = CheckinModule.state(player.getUuid());
			int loop = Math.max(1, CheckinModule.loopDays());
			int idx = CheckinModule.loopDay(Math.max(1, st.streak), loop);

			String today = java.time.LocalDate.now().toString();
			boolean doneToday = today.equals(st.lastDate);

			List<CheckinProtocol.Point> points = new ArrayList<>();
			for (int day = 1; day <= loop; day++) {
				CheckinModule.Tier tier = CheckinModule.tierOf(day);
				long money = tier == null ? 0 : tier.money();
				// 当前循环内「已经走过的天」算完成；今天签过则当前天也算完成
				boolean done = day < idx || (day == idx && doneToday);
				points.add(new CheckinProtocol.Point(day, money, done, day == idx));
			}

			long cost = CheckinModule.store().getPathLong("makeup.price", 0);
			int maxPerMonth = (int) CheckinModule.store().getPathLong("makeup.maxPerMonth", 0);
			int left = Math.max(0, maxPerMonth - st.makeupUsedMonth);

			// T57：里程碑带上「到达那天给多少」，客户端才能写「还差 3 天 · 200铜」。
			// 金额直接问 tierOf（配置改档位，提示跟着变），不在这里存第二份档位表。
			List<Integer> days = new ArrayList<>();
			try {
				var arr = CheckinModule.store().root().has("milestones")
						? CheckinModule.store().root().getAsJsonArray("milestones") : null;
				if (arr != null) for (var e : arr) if (e.isJsonPrimitive()) days.add(e.getAsInt());
			} catch (Exception ignored) {
				// 配置缺失用默认
			}
			if (days.isEmpty()) {
				days.add(7);
				days.add(14);
				days.add(28);
			}
			List<CheckinProtocol.Milestone> miles = new ArrayList<>();
			for (int day : days) {
				if (day <= 0) continue;
				CheckinModule.Tier tier = CheckinModule.tierOf(day);
				long money = tier == null ? 0 : tier.money();
				miles.add(new CheckinProtocol.Milestone(day, money, st.streak >= day));
			}
			CheckinProtocol.Snapshot snap = new CheckinProtocol.Snapshot(
					st.lastDate == null ? "" : st.lastDate, st.streak, loop, idx,
					st.totalDays, left, cost, points, miles);

			PacketByteBuf buf = new PacketByteBuf(io.netty.buffer.Unpooled.buffer());
			CheckinProtocol.write(buf, snap);
			ServerPlayNetworking.send(player, PacketIds.S2C_CHECKIN, buf);
		} catch (Exception e) {
			RedtaleEconomy.LOGGER.error("[checkin] 下发签到快照失败：{}", e.getMessage());
		}
	}
}
