package com.akira.redtale.economy.client.ui.hub;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.sound.SoundEvents;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 客户端 UI 偏好（U10）：主题、音效、侧边栏是否显示文字、tip 是否启用。
 *
 * <p>存在 {@code config/redtale/client-ui.json}（**客户端本地**，不随服务器同步），
 * 改完立即生效，不需要重载。这样以后加换肤只是多一个 theme 值，不用改界面代码。
 */
public final class ClientUiConfig {

	public static String theme = "dark";      // vanilla | custom
	public static String sound = "vanilla";      // vanilla | silent
	public static boolean showLabels = true;     // 侧边栏是否显示文字
	public static boolean tipsEnabled = true;    // 底部 tip 轮播
	public static boolean useHub = true;         // 命令打开界面时走 Hub（而不是旧界面）

	private static Path file;

	private ClientUiConfig() {
	}

	public static void load() {
		file = FabricLoader.getInstance().getConfigDir().resolve("redtale/client-ui.json");
		if (!Files.isRegularFile(file)) {
			save();
			return;
		}
		try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			JsonObject o = JsonParser.parseReader(r).getAsJsonObject();
			theme = o.has("theme") ? o.get("theme").getAsString() : theme;
			sound = o.has("sound") ? o.get("sound").getAsString() : sound;
			showLabels = !o.has("showLabels") || o.get("showLabels").getAsBoolean();
			tipsEnabled = !o.has("tipsEnabled") || o.get("tipsEnabled").getAsBoolean();
			useHub = !o.has("useHub") || o.get("useHub").getAsBoolean();
		} catch (Exception ignored) {
			// 配置坏了就用默认值，不阻塞界面
		}
	}

	public static void save() {
		try {
			if (file == null) {
				file = FabricLoader.getInstance().getConfigDir().resolve("redtale/client-ui.json");
			}
			Files.createDirectories(file.getParent());
			JsonObject o = new JsonObject();
			o.addProperty("theme", theme);
			o.addProperty("sound", sound);
			o.addProperty("showLabels", showLabels);
			o.addProperty("tipsEnabled", tipsEnabled);
			o.addProperty("useHub", useHub);
			try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
				w.write(o.toString());
			}
		} catch (Exception ignored) {
			// 写失败不影响本次会话
		}
	}

	/** 播放按钮点击音（sound=silent 时不播）。 */
	public static void click() {
		if ("silent".equals(sound)) return;
		MinecraftClient mc = MinecraftClient.getInstance();
		mc.getSoundManager().play(PositionedSoundInstance.master(SoundEvents.UI_BUTTON_CLICK, 1.0F));
	}

	public static String themeLabel() {
		return HubTheme.label(theme);
	}

	public static String soundLabel() {
		return "silent".equals(sound) ? "关" : "开";
	}
}
