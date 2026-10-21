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
	/** §43.6：排行页「同步」按钮的冷却秒数（0 = 不限）。客户端本地可改。 */
	public static int rankSyncCooldownSeconds = 60;
	/** T69：商店网格档位；**0 = 按窗口自动**（能放几格放几格）。 */
	public static int shopCols = 0;
	public static int shopRows = 0;

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
			rankSyncCooldownSeconds = o.has("rankSyncCooldownSeconds")
					? Math.max(0, o.get("rankSyncCooldownSeconds").getAsInt()) : rankSyncCooldownSeconds;
			shopCols = o.has("shopCols") ? Math.max(0, Math.min(12, o.get("shopCols").getAsInt())) : shopCols;
			shopRows = o.has("shopRows") ? Math.max(0, Math.min(8, o.get("shopRows").getAsInt())) : shopRows;
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
			o.addProperty("rankSyncCooldownSeconds", rankSyncCooldownSeconds);
			o.addProperty("shopCols", shopCols);
			o.addProperty("shopRows", shopRows);
			try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
				w.write(o.toString());
			}
		} catch (Exception ignored) {
			// 写失败不影响本次会话
		}
	}

	/** 播放按钮点击音（sound=silent 时不播）。 */
	public static void click() {
		devRequestedSound = false;
		if ("silent".equals(sound)) return;
		MinecraftClient mc = MinecraftClient.getInstance();
		devRequestedSound = true;
		mc.getSoundManager().play(PositionedSoundInstance.master(SoundEvents.UI_BUTTON_CLICK, 1.0F));
	}

	/**
	 * 仅 dev 截图夹具使用：上一次 {@link #click()} 是否真的向声音系统请求了播放。
	 *
	 * <p>「静音后没有按钮音」原本只能靠耳朵判定（截图拍不到声音）。
	 * 这里把判定点前移到「有没有调用 {@code SoundManager.play}」——
	 * 静音时不调用就是真的不响，剩下的音量/听感属于原版职责，不是本模组的逻辑。
	 */
	private static boolean devRequestedSound;

	/** 仅 dev 夹具：读上一行的观测值。 */
	public static boolean devLastClickRequestedSound() {
		return devRequestedSound;
	}

	public static String themeLabel() {
		return HubTheme.label(theme);
	}

	public static String soundLabel() {
		return "silent".equals(sound) ? "关" : "开";
	}
}
