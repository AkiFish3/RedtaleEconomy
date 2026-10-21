package com.akira.redtale.economy.client.ui.hub;

import com.akira.redtale.economy.RedtaleEconomy;
import com.akira.redtale.economy.net.ClientSend;
import com.akira.redtale.economy.net.ModulesProtocol;
import com.akira.redtale.economy.net.PacketIds;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.network.PacketByteBuf;

import java.util.HashMap;
import java.util.Map;

/**
 * T51 客户端模块状态缓存。
 *
 * <p>侧栏可见性（{@link HubPage#visible()}）每帧都会问一次「这个模块开没开」，
 * 所以这里只放一个不可变快照 + O(1) 查询，<b>不在渲染期发请求</b>。
 *
 * <p>★默认全开★：还没收到服务端快照时（刚进服第一帧）不能把侧栏清空 ——
 * 那会让界面闪一下再长出来。真正的权限边界在服务端。
 */
@Environment(EnvType.CLIENT)
public final class ModulesClientNetworking {

	private static volatile ModulesProtocol.Snapshot LAST = ModulesProtocol.Snapshot.empty();
	private static volatile boolean received;
	private static volatile long receivedAt;

	private ModulesClientNetworking() {
	}

	public static void register() {
		ClientPlayNetworking.registerGlobalReceiver(PacketIds.S2C_MODULES, (client, handler, buf, sender) -> {
			ModulesProtocol.Snapshot snap;
			try {
				snap = ModulesProtocol.read(buf);
			} catch (Exception e) {
				RedtaleEconomy.LOGGER.warn("[modules] 状态解析失败：{}", e.getMessage());
				return;
			}
			LAST = snap;
			received = true;
			receivedAt = System.currentTimeMillis();
			client.execute(() -> {
				if (client.currentScreen instanceof HubScreen hub) {
					// 当前页被关掉了（管理员在本机切换，或别人改了配置）⇒ 退回设置页
					if (!isEnabled(hub.pageId())) {
						hub.setPage(HubPage.SETTINGS);
						hub.setStatus("该模块已关闭，已退回设置");
					}
					hub.clampPageToVisible();
				}
			});
		});
		RedtaleEconomy.LOGGER.info("[modules] 模块状态接收器已注册");
	}

	/** 模块是否开启。未收到快照 / id 不在快照里 ⇒ 视为开启（侧栏不缩水）。 */
	public static boolean isEnabled(String moduleId) {
		if (moduleId == null || moduleId.isEmpty()) return true;
		if (!received) return true;
		ModulesProtocol.Snapshot s = LAST;
		for (ModulesProtocol.ModuleView v : s.modules()) {
			if (v.id().equals(moduleId)) return v.on();
		}
		return true;
	}

	public static boolean received() {
		return received;
	}

	public static long receivedAt() {
		return receivedAt;
	}

	public static ModulesProtocol.Snapshot snapshot() {
		return LAST;
	}

	/** 请求一次（打开 Hub / 进入设置页时调）。 */
	public static void request() {
		try {
			PacketByteBuf buf = new PacketByteBuf(io.netty.buffer.Unpooled.buffer());
			ModulesProtocol.writeQuery(buf, ModulesProtocol.Query.current());
			ClientSend.send(PacketIds.C2S_MODULES_REQ, buf);
		} catch (Exception e) {
			RedtaleEconomy.LOGGER.warn("[modules] 请求模块状态失败：{}", e.getMessage());
		}
	}

	/** 切换一个模块（服务端判权限并回快照）。 */
	public static void set(String moduleId, boolean on) {
		if (moduleId == null || moduleId.isBlank()) return;
		try {
			PacketByteBuf buf = new PacketByteBuf(io.netty.buffer.Unpooled.buffer());
			ModulesProtocol.writeSet(buf, ModulesProtocol.Set.of(moduleId, on));
			ClientSend.send(PacketIds.C2S_MODULE_SET, buf);
		} catch (Exception e) {
			RedtaleEconomy.LOGGER.warn("[modules] 切换 {} 失败：{}", moduleId, e.getMessage());
		}
	}

	/** 仅 dev 夹具：id → on 的扁平视图（写日志用）。 */
	public static Map<String, Boolean> devStates() {
		Map<String, Boolean> out = new HashMap<>();
		for (ModulesProtocol.ModuleView v : LAST.modules()) out.put(v.id(), v.on());
		return out;
	}
}
