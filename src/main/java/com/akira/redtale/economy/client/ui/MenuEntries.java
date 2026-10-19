package com.akira.redtale.economy.client.ui;

import java.util.ArrayList;
import java.util.List;

/**
 * 主面板入口表（U4）。
 *
 * <p>纯数据、不依赖 Minecraft，便于单测校验「每个入口都指向一个真实命令」。
 * {@code ready=false} 表示对应模块（邮件 R21 / 战令 R20）还没上线，按钮置灰。
 */
public final class MenuEntries {

	/**
	 * @param key     lang 键后缀：gui.redtale_economy.menu.&lt;key&gt;
	 * @param command 要执行的玩家命令，不带斜杠
	 * @param ready   是否已可用
	 */
	public record Entry(String key, String command, boolean ready) {
	}

	private static final List<Entry> ALL = List.of(
			new Entry("balance", "bal", true),
			new Entry("checkin", "checkin", true),
			new Entry("tasks", "tasks", true),
			new Entry("shop", "shop", true),
			new Entry("mail", "mail", false),
			new Entry("season", "season", false));

	private MenuEntries() {
	}

	public static List<Entry> all() {
		return ALL;
	}

	public static List<Entry> ready() {
		List<Entry> out = new ArrayList<>();
		for (Entry e : ALL) {
			if (e.ready()) out.add(e);
		}
		return out;
	}

	/** 键位动作名：1 个 menu + 每个入口 1 个，共 7 个（U5）。 */
	public static List<String> keybindNames() {
		List<String> out = new ArrayList<>();
		out.add("menu");
		for (Entry e : ALL) out.add(e.key());
		return out;
	}

	public static Entry byKey(String key) {
		for (Entry e : ALL) {
			if (e.key().equals(key)) return e;
		}
		return null;
	}
}
