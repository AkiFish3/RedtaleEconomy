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
	LEDGER("ledger", "流水", false, "minecraft:book");

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

	public static HubPage byId(String id) {
		for (HubPage p : values()) {
			if (p.id.equals(id)) return p;
		}
		return CHECKIN;
	}
}
