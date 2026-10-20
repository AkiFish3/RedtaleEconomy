package com.akira.redtale.economy.client.ui;

import com.akira.redtale.economy.RedtaleEconomy;
import com.akira.redtale.economy.ui.UiLayouts;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

/**
 * 布局动作分发（R15）。
 *
 * <p>动作语法（写在布局 JSON 的 {@code "action"} 里）：
 * <ul>
 *   <li>{@code cmd:<命令>} —— 以玩家身份执行命令（不加斜杠），服务端照常校验权限</li>
 *   <li>{@code ui:<布局 id>} —— 切到另一份布局</li>
 *   <li>{@code close} —— 关闭当前界面</li>
 * </ul>
 * 其它动作只记 DEBUG，不报错 —— 布局可能是服主从别的版本拷来的。
 */
@Environment(EnvType.CLIENT)
public final class UiActions {

	private UiActions() {
	}

	public static void run(String action) {
		if (action == null || action.isBlank()) return;
		MinecraftClient mc = MinecraftClient.getInstance();
		if (action.equals("close")) {
			mc.setScreen(null);
			return;
		}
		if (action.startsWith("ui:")) {
			String id = action.substring(3).trim();
			var layout = UiLayouts.get(id);
			if (layout == null) {
				if (mc.player != null) {
					mc.player.sendMessage(Text.translatable("gui.redtale_economy.layout.missing", id), false);
				}
				return;
			}
			mc.setScreen(new LayoutScreen(layout));
			return;
		}
		if (action.startsWith("cmd:")) {
			String cmd = action.substring(4).trim();
			if (cmd.startsWith("/")) cmd = cmd.substring(1);
			if (cmd.isEmpty() || mc.player == null || mc.player.networkHandler == null) return;
			mc.player.networkHandler.sendChatCommand(cmd);
			mc.setScreen(null);
			return;
		}
		RedtaleEconomy.LOGGER.debug("[ui] 未知动作 {}", action);
	}
}
