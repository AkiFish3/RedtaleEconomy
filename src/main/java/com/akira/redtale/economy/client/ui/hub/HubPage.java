package com.akira.redtale.economy.client.ui.hub;

/**
 * Hub 的页面枚举（U4）。顺序即侧边栏顺序。
 *
 * <p>{@code adminOnly} 的页面只有具备管理权限的玩家才看到。
 */
public enum HubPage {

	CHECKIN("checkin", "签到", false, "minecraft:clock", "checkin"),
	/** T56：每日任务（原来只有独立面板，Hub 里没有入口）。 */
	TASKS("tasks", "任务", false, "minecraft:writable_book", "tasks"),
	SHOP("shop", "商店", false, "minecraft:chest", "shop"),
	MARKET("market", "市场", false, "minecraft:emerald_block", "market"),
	STORAGE("storage", "仓储", false, "minecraft:barrel", "warehouse"),
	BOUNTY("bounty", "悬赏", false, "minecraft:iron_sword", "bounty"),
	BOARD("board", "委托", false, "minecraft:oak_sign", "board"),
	PASS("pass", "战令", false, "minecraft:nether_star", "battlepass"),
	GUILD("guild", "协会", false, "minecraft:emerald", "guild"),
	MAIL("mail", "邮件", false, "minecraft:paper", "mail"),
	RANK("rank", "排行", false, "minecraft:gold_ingot", "economy"),
	PAY("pay", "转账", false, "minecraft:emerald", "economy"),
	TITLES("titles", "称号", false, "minecraft:name_tag", "title"),
	SETTINGS("settings", "设置", false, "minecraft:comparator", null),
	LEDGER("ledger", "流水", false, "minecraft:book", "economy"),
	/** T28：管理员控制台（只读概览）。★永远加在末尾★：侧栏下标被夹具与点击共用。 */
	ADMIN("admin", "控制台", true, "minecraft:command_block", null);

	public final String id;
	public final String label;
	public final boolean adminOnly;
	public final String icon;
	/**
	 * T51：这一页属于哪个模块（{@code null} = 恒可见）。
	 *
	 * <p>模块关闭后入口必须消失（不是点了进空页），所以侧栏过滤和「深链接」校验
	 * 都读同一个字段；id 必须与 {@code RedtaleModule.id()} 一致。
	 */
	public final String module;

	HubPage(String id, String label, boolean adminOnly, String icon, String module) {
		this.id = id;
		this.label = label;
		this.adminOnly = adminOnly;
		this.icon = icon;
		this.module = module;
	}

	/** 本页所属模块是否开启（{@code null} = 恒开）。 */
	public boolean moduleOn() {
		return module == null || ModulesClientNetworking.isEnabled(module);
	}

	/** 本机玩家是否能看管理员页（服务端仍会独立判权限，这里只决定「侧栏显示不显示」）。 */
	public static boolean canAdmin() {
		var mc = net.minecraft.client.MinecraftClient.getInstance();
		return mc != null && mc.player != null && mc.player.hasPermissionLevel(4);
	}

	/**
	 * 实际可见的页面（顺序 = 侧栏顺序 = 点击下标）。
	 *
	 * <p>★侧栏渲染、行命中、滚动上限、点击映射必须都用它★：任何一处改回 {@code values()}
	 * 都会在「非管理员少一行」时错位（点第 3 行打开第 4 页）——这正是 {@code adminOnly}
	 * 之前形同虚设的原因（声明了但没人读）。
	 */
	public static HubPage[] visible() {
		HubPage[] all = values();
		if (canAdmin()) return all;
		HubPage[] out = new HubPage[all.length];
		int n = 0;
		for (HubPage p : all) {
			// T51：模块关闭 ⇒ 侧栏不显示（数据仍在服务端，重开即恢复）
			if (!p.adminOnly && p.moduleOn()) out[n++] = p;
		}
		HubPage[] trimmed = new HubPage[n];
		System.arraycopy(out, 0, trimmed, 0, n);
		return trimmed;
	}

	/** 该页当前是否可进（管理员页额外要求权限）。深链接也必须过这一关。 */
	public static boolean accessible(HubPage p) {
		if (p == null) return false;
		if (p.adminOnly && !canAdmin()) return false;
		return p.moduleOn();
	}

	public static HubPage byId(String id) {
		for (HubPage p : values()) {
			if (p.id.equals(id)) return p;
		}
		return CHECKIN;
	}
}
