package com.akira.redtale.economy.config;

import com.akira.redtale.economy.config.ConfigSchema;
import com.akira.redtale.economy.config.ConfigStore;
import com.akira.redtale.economy.config.ConfigManager;

import java.util.List;

/** {@code config/redtale/core.json} —— 全局：语言、时区、权限、文案前缀、备份份数。 */
public final class CoreConfig {

	public static final String FILE = "core.json";

	public static final ConfigSchema SCHEMA = ConfigSchema.obj("全局设置")
			.field("language", ConfigSchema.choice("zh_cn", List.of("zh_cn", "en_us"),
					"界面/提示语言；对应 config/redtale/lang/<code>.json"))
			.field("timezone", ConfigSchema.string("server",
					"时区：server=跟随服务器系统时区，或填 IANA 名字（Asia/Shanghai）"))
			.field("dateFormat", ConfigSchema.string("yyyy-MM-dd",
					"日期字符串格式（签到/任务/流水分文件都用它）"))
			.field("resetHour", ConfigSchema.integer(0, 0, 23,
					"跨天重置时刻（0-23）：R38b 的定时器与登录兜底都按它判定新的一天"))
			.field("debug", ConfigSchema.bool(false, "调试日志：打印每次余额变动与包收发"))
			.field("commands", ConfigSchema.obj("命令权限")
					.field("reloadPermissionLevel", ConfigSchema.integer(4, 0, 4,
							"/redtale reload 需要的最低权限等级（0=所有人，4=OP）"))
					.field("adminPermissionLevel", ConfigSchema.integer(4, 0, 4,
							"/redtale admin 类子命令的最低权限等级"))
					.field("balOtherPermissionLevel", ConfigSchema.integer(0, 0, 4,
							"/bal <玩家> 查别人余额的最低权限等级")))
			.field("permissions", ConfigSchema.obj("权限（FTB Ranks / LuckPerms / OP）")
					.field("provider", ConfigSchema.choice("auto", List.of("auto", "ftbranks", "luckperms", "op"),
							"auto = 按 ftbranks → luckperms → op 顺序探测"))
					.field("fallbackToOp", ConfigSchema.bool(true,
							"节点未配置时退回 OP 等级判断（FTB Ranks 的 MISSING）"))
					.field("failClosed", ConfigSchema.bool(false,
							"权限插件调用失败时是否一律拒绝（true=宁可不给权限；false=退回 OP 判断）"))
					.field("levels", ConfigSchema.obj("节点 → OP 等级（仅 op 模式与 fallback 用）")
							.field("use", ConfigSchema.integer(0, 0, 4, "玩家类命令最低 OP 等级"))
							.field("admin", ConfigSchema.integer(2, 0, 4, "/shop admin 等管理命令"))
							.field("owner", ConfigSchema.integer(4, 0, 4, "/redtale reload 等服主命令"))
							.field("premiumGive", ConfigSchema.integer(4, 0, 4, "/premium give"))
							.field("default", ConfigSchema.integer(0, 0, 4, "未在上面列出的节点默认等级"))
							.field("nodes", ConfigSchema.obj("节点名（改这里可对接已有权限组）")
									.field("use", ConfigSchema.string("redtale.use", "玩家类命令节点"))
									.field("admin", ConfigSchema.string("redtale.admin", "管理命令节点"))
									.field("owner", ConfigSchema.string("redtale.owner", "服主命令节点"))
									.field("premiumGive", ConfigSchema.string("redtale.premium.give",
											"赞助货币发放节点")))))
			.field("messages", ConfigSchema.obj("提示文本")
					.field("prefix", ConfigSchema.string("§7[§6Redtale§7] §r", "聊天提示前缀（支持 § 颜色码）"))
					.field("notifyOpsOnConfigError", ConfigSchema.bool(true, "配置加载失败时在聊天栏提醒在线 OP")))
			.field("confirm", ConfigSchema.obj("危险操作二次确认（R25）")
					.field("dangerousOps", ConfigSchema.bool(false,
							"是否要求 /shop admin remove|removeshop 先出票据再确认；关闭=保持旧行为直接执行"))
					.field("ttlSeconds", ConfigSchema.integer(300, 10, 3600, "票据有效期（秒）")))
			.field("backup", ConfigSchema.obj("配置备份")
					.field("maxBackups", ConfigSchema.integer(20, 1, 200, "config/redtale/backup 下最多保留几个时间戳目录")))
			.field("alerts", ConfigSchema.obj("外部告警 Webhook（T15，默认关闭）")
					.field("enabled", ConfigSchema.bool(false, "总开关；关闭时不发任何请求"))
					.field("url", ConfigSchema.string("", "http/https 端点；QQ 机器人填其上报地址（可含 access_token）"))
					.field("format", ConfigSchema.choice("onebot", List.of("onebot", "qq", "json", "plain"),
							"onebot={\"message\":…}；qq={\"content\":…,\"msg_type\":0}；json={event,text}；plain=纯文本"))
					.field("timeoutMs", ConfigSchema.integer(3000, 500, 30000, "单次请求超时（毫秒）"))
					.field("perMinute", ConfigSchema.integer(20, 1, 600, "每分钟最多发几条（超出直接丢，不排队）"))
					.field("largeShopAmount", ConfigSchema.integer(10000, 0, 1_000_000_000L,
							"商店单笔合计 ≥ 该金额才告警（0=不按金额告警）"))
					.field("events", ConfigSchema.obj("事件开关")
							.field("largeShop", ConfigSchema.bool(true, "大额商店成交"))
							.field("altGuard", ConfigSchema.bool(true, "防小号拦截"))
							.field("configError", ConfigSchema.bool(false, "配置加载失败"))))
			.field("calendar", ConfigSchema.rawList(new com.google.gson.JsonArray(),
					"经济事件日历（T27）：[{id,name,from,to,multiplier,scopes:[battlepass,checkin,shop]}]；"
							+ "from/to 支持 yyyy-MM-dd（含当天）或 ISO 时间；重叠取最大倍率，不叠乘"));

	private CoreConfig() {
	}

	public static ConfigStore store() {
		return ConfigManager.get().store(FILE);
	}

	public static String language() {
		return store().getString("language", "zh_cn");
	}

	public static String timezone() {
		return store().getString("timezone", "server");
	}

	public static String dateFormat() {
		return store().getString("dateFormat", "yyyy-MM-dd");
	}

	/** R38b：业务日切换时刻（0-23）。 */
	public static int resetHour() {
		return (int) store().getLong("resetHour", 0);
	}

	public static boolean debug() {
		return store().getBool("debug", false);
	}

	public static int perm(String path, int def) {
		return (int) store().getPathLong("commands." + path, def);
	}

	public static String prefix() {
		return store().getPathString("messages.prefix", "§7[§6Redtale§7] §r");
	}

	/**
	 * 配置加载失败时是否在聊天栏提醒在线 OP（{@code messages.notifyOpsOnConfigError}）。
	 *
	 * <p>★这个键自 v0.13 起就声明在 schema 里，但一直没有读取方★ ⇒ 服主把它关掉、或者
	 * 打开都毫无区别（典型的「键在、语义没落地」）。现在接到 {@code /redtale reload} 的
	 * 出错分支上：执行者本身已经看到完整错误列表，其他 OP 只收一行摘要。
	 */
	public static boolean notifyOpsOnConfigError() {
		return store().getPathBool("messages.notifyOpsOnConfigError", true);
	}

	public static int maxBackups() {
		return (int) store().getPathLong("backup.maxBackups", 20);
	}

	// ---------------------------------------------------------------- 权限（v0.13）

	public static String permissionsProvider() {
		return store().getString("permissions.provider", "auto");
	}

	public static boolean fallbackToOp() {
		return store().getBool("permissions.fallbackToOp", true);
	}

	/** 权限插件异常时 fail-closed（Q5）：true = 拒绝，false = 退回 OP 等级。 */
	public static boolean failClosed() {
		return store().getBool("permissions.failClosed", false);
	}

	/** 完整节点名，例如 admin → redtale.admin。 */
	public static String nodeName(String key) {
		String def = "redtale." + ("premiumGive".equals(key) ? "premium.give" : key);
		return store().getString("permissions.levels.nodes." + key, def);
	}

	/** 节点对应的 OP 等级：优先取 levels.<key>，未配置回落 default。 */
	public static int nodeLevel(String key, int def) {
		long fallback = store().getPathLong("permissions.levels.default", 0);
		return (int) store().getPathLong("permissions.levels." + key, def > 0 ? def : fallback);
	}
}
