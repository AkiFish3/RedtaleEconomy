package com.akira.redtale.economy.config;

import java.util.List;

/**
 * {@code config/redtale/mail.json} —— 邮件系统（R21，spec v0.15 §20）。
 *
 * <p>物品走 Contact 信件（唯一真值），纯货币走我们自己的「待领取」队列
 * （余额是数据，塞硬币物品会和余额打架）。
 */
public final class MailConfig {

	public static final String FILE = "mail.json";

	public static final ConfigSchema SCHEMA = ConfigSchema.obj("邮件")
			.field("enabled", ConfigSchema.bool(true, "是否启用邮件（关掉后 /mail 不注册）"))
			.field("senderName", ConfigSchema.string("赤物语", "Contact 信件的发件人显示名"))
			.field("maxQueueMoney", ConfigSchema.integer(1_000_000_000, 1, 9_000_000_000_000L,
					"单次入队货币上限（超过直接拒绝，防手滑）"))
			.field("retryIntervalSeconds", ConfigSchema.integer(60, 5, 3600,
					"待领取的物品多久尝试重投 Contact 一次"))
			.field("openCommand", ConfigSchema.stringList(List.of("mail"), "打开邮件的命令名"))
			.field("notifyOnReceive", ConfigSchema.bool(true, "收到邮件时是否动作栏提示"));

	private MailConfig() {
	}

	public static ConfigStore store() {
		return ConfigManager.get().store(FILE);
	}

	public static boolean enabled() {
		return store().getBool("enabled", true);
	}

	public static String senderName() {
		String s = store().getString("senderName", "赤物语");
		return s == null || s.isBlank() ? "赤物语" : s;
	}

	public static long maxQueueMoney() {
		return store().getLong("maxQueueMoney", 1_000_000_000L);
	}

	public static int retryIntervalSeconds() {
		return (int) store().getLong("retryIntervalSeconds", 60);
	}

	public static List<String> openCommands() {
		var el = store().getPath("openCommand");
		java.util.List<String> out = new java.util.ArrayList<>();
		if (el != null && el.isJsonArray()) {
			for (var e : el.getAsJsonArray()) {
				if (e.isJsonPrimitive() && !e.getAsString().isBlank()) out.add(e.getAsString().trim());
			}
		}
		return out.isEmpty() ? List.of("mail") : out;
	}

	public static boolean notifyOnReceive() {
		return store().getBool("notifyOnReceive", true);
	}
}
