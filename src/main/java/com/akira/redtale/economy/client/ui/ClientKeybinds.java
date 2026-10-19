package com.akira.redtale.economy.client.ui;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 客户端键位（U5）。
 *
 * <p>7 个动作全部注册进原版「选项 → 控制」，<b>默认不绑定任何键</b>（{@link GLFW#GLFW_KEY_UNKNOWN}），
 * 不抢任何模组/原版的键。
 *
 * <p>动作 = 1 个「打开主面板」+ 6 个直达入口（余额 / 签到 / 任务 / 商店 / 邮件 / 战令），
 * 入口数据来自 {@link MenuEntries}，所以主面板和键位永远同步，不会一边有、一边没有。
 */
@Environment(EnvType.CLIENT)
public final class ClientKeybinds {

	public static final String CATEGORY = "category.redtale_economy";

	/** 动作名 → 键位。key = lang 键后缀，与 {@link MenuEntries} 一致。 */
	private static final Map<String, KeyBinding> ACTIONS = new LinkedHashMap<>();

	/** 打开主入口面板。 */
	public static final KeyBinding MENU = register("menu");

	static {
		for (MenuEntries.Entry e : MenuEntries.all()) {
			ACTIONS.put(e.key(), register(e.key()));
		}
	}

	private ClientKeybinds() {
	}

	private static KeyBinding register(String name) {
		return KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.redtale_economy." + name, InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, CATEGORY));
	}

	public static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (MENU.wasPressed()) {
				openMenu(client);
			}
			for (Map.Entry<String, KeyBinding> e : ACTIONS.entrySet()) {
				while (e.getValue().wasPressed()) {
					trigger(client, e.getKey());
				}
			}
		});
	}

	/** 按下直达键：已就绪的发命令，未就绪的在动作栏提示，不留给玩家一个「按了没反应」的键。 */
	private static void trigger(MinecraftClient client, String key) {
		MenuEntries.Entry entry = MenuEntries.byKey(key);
		if (entry == null) return;
		if (!entry.ready()) {
			if (client.player != null) {
				client.player.sendMessage(Text.translatable("gui.redtale_economy.menu.soon"), true);
			}
			return;
		}
		if (client.player == null || client.getNetworkHandler() == null) return;
		client.getNetworkHandler().sendChatCommand(entry.command());
	}

	private static void openMenu(MinecraftClient client) {
		// 只在游戏内、且没有其它界面时打开，避免和箱子/聊天冲突
		if (client.player != null && client.currentScreen == null) {
			client.setScreen(new MainMenuScreen());
		}
	}

	/** 已注册的动作名（与 MenuEntries 同源，供自检）。 */
	public static java.util.List<String> actionNames() {
		return MenuEntries.keybindNames();
	}
}
