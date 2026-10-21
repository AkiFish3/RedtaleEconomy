package com.akira.redtale.economy.client.ui.hub;

/**
 * Hub 的页面枚举（U4）。顺序即侧边栏顺序。
 *
 * <p>{@code adminOnly} 的页面只有具备管理权限的玩家才看到。
 */
public enum HubPage {

	CHECKIN("checkin", "签到", false, "minecraft:clock"),
	SHOP("shop", "商店", false, "minecraft:chest"),
	MARKET("market", "市场", false, "minecraft:emerald_block"),
	STORAGE("storage", "仓储", false, "minecraft:barrel"),
	BOUNTY("bounty", "悬赏", false, "minecraft:iron_sword"),
	BOARD("board", "委托", false, "minecraft:oak_sign"),
	PASS("pass", "战令", false, "minecraft:nether_star"),
	GUILD("guild", "协会", false, "minecraft:emerald"),
	MAIL("mail", "邮件", false, "minecraft:paper"),
	RANK("rank", "排行", false, "minecraft:gold_ingot"),
	PAY("pay", "转账", false, "minecraft:emerald"),
	TITLES("titles", "称号", false, "minecraft:name_tag"),
	SETTINGS("settings", "设置", false, "minecraft:comparator"),
	LEDGER("ledger", "流水", false, "minecraft:book"),
	/** T28：管理员控制台（只读概览）。★永远加在末尾★：侧栏下标被夹具与点击共用。 */
	ADMIN("admin", "控制台", true, "minecraft:command_block");

	public final String id;
	public final String label;
	public final boolean adminOnly;
	public final String icon;

	HubPage(String id, String label, boolean adminOnly, String icon) {
		this.id = id;
		this.label = label;
		this.adminOnly = adminOnly;
		this.icon = icon;
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
			if (!p.adminOnly) out[n++] = p;
		}
		HubPage[] trimmed = new HubPage[n];
		System.arraycopy(out, 0, trimmed, 0, n);
		return trimmed;
	}

	public static HubPage byId(String id) {
		for (HubPage p : values()) {
			if (p.id.equals(id)) return p;
		}
		return CHECKIN;
	}
}
