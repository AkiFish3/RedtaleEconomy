package com.akira.redtale.economy.client.ui.hub;

/**
 * Hub 配色主题（U10）：深色 / 亮色 / 原版。
 *
 * <p>原版模式用经典 GUI 的灰底 + 白边，和箱子界面观感一致；
 * 其余两种是我们自己的深浅两套。切换即时生效，不需要重载。
 */
public final class HubTheme {

	/** 面板底 */
	public static int PANEL = 0xFF202020;
	/** 侧边栏/顶栏/底栏底 */
	public static int BAR = 0xFF1B1B1B;
	/** 分隔线 */
	public static int LINE = 0xFF3A3A3A;
	/** 正文 */
	public static int TEXT = 0xFFE0E0E0;
	/** 次要文字 */
	public static int MUTED = 0xFF9A9A9A;
	/** 强调色 */
	public static int GOLD = 0xFFFFAA00;
	/** 选中行底 */
	public static int SELECT = 0xFF2B2618;
	/** 卡片底 */
	public static int CARD = 0xFF2A2A2A;
	/** 悬停行底 */
	public static int HOVER = 0xFF2A2A2A;
	/** 标题文字 */
	public static int TITLE = 0xFFF0E6D2;
	/** 主色（按钮/选中条） */
	public static int PRIMARY = 0xFFFFAA00;
	public static int PRIMARY_HOVER = 0xFFFFC24A;
	public static int ON_PRIMARY = 0xFF201800;
	/** 危险操作 */
	public static int DANGER = 0xFFB84242;
	public static int DANGER_HOVER = 0xFFD05050;
	/** 描边 */
	public static int OUTLINE = 0xFF3A3A3A;

	public static boolean vanilla;

	private HubTheme() {
	}

	public static void apply(String name) {
		vanilla = "vanilla".equals(name);
		if ("transparent".equals(name)) {
			// 透明：只留极淡的底和描边，能看见后面的世界
			PANEL = 0x40000000;
			BAR = 0x40000000;
			LINE = 0x60FFFFFF;
			TEXT = 0xFFFFFFFF;
			MUTED = 0xCCDDDDDD;
			GOLD = 0xFFFFD54A;
			SELECT = 0x55FFD54A;
			CARD = 0x33000000;
			HOVER = 0x22FFFFFF;
			TITLE = 0xFFFFFFFF;
			PRIMARY = 0xFFFFD54A;
			PRIMARY_HOVER = 0xFFFFE68A;
			ON_PRIMARY = 0xFF241C00;
			DANGER = 0xFFC05A5A;
			DANGER_HOVER = 0xFFD97A7A;
			OUTLINE = 0x66FFFFFF;
		} else if ("vanilla".equals(name)) {
			// 经典 GUI：灰底 + 亮边
			PANEL = 0xFFC6C6C6;
			BAR = 0xFFB0B0B0;
			LINE = 0xFF8B8B8B;
			TEXT = 0xFF404040;
			MUTED = 0xFF707070;
			GOLD = 0xFF2A3F8F;   // 原版灰底上金色看不清，改深蓝
			SELECT = 0xFFFFF8C0;
			CARD = 0xFFD6D6D6;
			HOVER = 0xFFBEBEBE;
			TITLE = 0xFF202020;
			PRIMARY = 0xFF2A3F8F;
			PRIMARY_HOVER = 0xFF3A54A8;
			ON_PRIMARY = 0xFFFFFFFF;
			DANGER = 0xFF8B2F2F;
			DANGER_HOVER = 0xFFA63C3C;
			OUTLINE = 0xFF7A7A7A;
		} else {
			PANEL = 0xFF202020;
			BAR = 0xFF1B1B1B;
			LINE = 0xFF3A3A3A;
			TEXT = 0xFFE0E0E0;
			MUTED = 0xFF9A9A9A;
			GOLD = 0xFFFFAA00;
			SELECT = 0xFF2B2618;
			CARD = 0xFF2A2A2A;
			HOVER = 0xFF2A2A2A;
			TITLE = 0xFFF0E6D2;
			PRIMARY = 0xFFFFAA00;
			PRIMARY_HOVER = 0xFFFFC24A;
			ON_PRIMARY = 0xFF201800;
			DANGER = 0xFFB84242;
			DANGER_HOVER = 0xFFD05050;
			OUTLINE = 0xFF3A3A3A;
		}
	}

	public static String label(String name) {
		return switch (name) {
			case "transparent" -> "透明";
			case "vanilla" -> "原版";
			default -> "深色";
		};
	}

	public static String next(String name) {
		return switch (name) {
			case "dark" -> "transparent";
			case "transparent" -> "vanilla";
			default -> "dark";
		};
	}
}
