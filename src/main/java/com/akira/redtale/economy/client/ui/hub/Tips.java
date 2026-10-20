package com.akira.redtale.economy.client.ui.hub;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 底部提示轮播（U12）。
 *
 * <p>读 {@code config/redtale/tips.json}，按 weight 加权随机；{@code ad=true} 的条目用金色显示，
 * 相当于给服主留的广告位（活动公告、规则提醒都可以往这里塞）。
 *
 * <p>读不到文件时用内置默认文案，不会因为配置缺失而空白。
 */
public final class Tips {

	private static final Logger LOG = LoggerFactory.getLogger("redtale/tips");

	/** 一条提示。 */
	public record Tip(String text, int weight, boolean ad) {
	}

	private static List<Tip> tips = defaults();
	private static int intervalSeconds = 8;

	private Tips() {
	}

	private static List<Tip> defaults() {
		List<Tip> list = new ArrayList<>();
		list.add(new Tip("按 K 可以随时打开这个界面", 10, false));
		list.add(new Tip("每天 4 点重置每日任务与悬赏", 10, false));
		list.add(new Tip("连续签到第 7 天有额外奖励", 8, false));
		return list;
	}

	public static void load() {
		Path f = FabricLoader.getInstance().getConfigDir().resolve("redtale/tips.json");
		if (!Files.isRegularFile(f)) {
			tips = defaults();
			return;
		}
		try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
			JsonObject root = JsonParser.parseReader(r).getAsJsonObject();
			if (root.has("intervalSeconds")) {
				intervalSeconds = Math.max(3, root.get("intervalSeconds").getAsInt());
			}
			JsonArray arr = root.getAsJsonArray("tips");
			if (arr == null || arr.isEmpty()) {
				tips = defaults();
				return;
			}
			List<Tip> list = new ArrayList<>();
			for (JsonElement e : arr) {
				JsonObject o = e.getAsJsonObject();
				String text = o.has("text") ? o.get("text").getAsString() : "";
				if (text.isEmpty()) continue;
				int w = o.has("weight") ? Math.max(1, o.get("weight").getAsInt()) : 1;
				boolean ad = o.has("ad") && o.get("ad").getAsBoolean();
				list.add(new Tip(text, w, ad));
			}
			tips = list.isEmpty() ? defaults() : list;
		} catch (Exception ex) {
			LOG.warn("tips.json 读取失败，用默认文案：{}", ex.toString());
			tips = defaults();
		}
	}

	public static int intervalSeconds() {
		return intervalSeconds;
	}

	public static Tip next(int index) {
		if (tips.isEmpty()) return new Tip("", 1, false);
		int total = 0;
		for (Tip t : tips) total += t.weight();
		int roll = (int) (System.nanoTime() % total);
		if (roll < 0) roll = -roll;
		for (Tip t : tips) {
			roll -= t.weight();
			if (roll < 0) return t;
		}
		return tips.get(0);
	}

	public static Tip at(int index) {
		if (tips.isEmpty()) return new Tip("", 1, false);
		return tips.get(Math.floorMod(index, tips.size()));
	}
}
