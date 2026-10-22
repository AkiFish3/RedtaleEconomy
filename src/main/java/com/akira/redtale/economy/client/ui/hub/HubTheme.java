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
	/** 达成态卡片底（签到已签格）：深色主题保持墨绿，原版主题走浅绿 */
	public static int CARD_OK = 0xFF243026;
	/** 悬停行底 */
	public static int HOVER = 0xFF2A2A2A;
	/**
	 * 列表行的悬停底（比 {@link #CARD} 明显一档）。
	 *
	 * <p>深色主题里 CARD 与 HOVER 同值 ⇒ 直接用 HOVER 画行等于没有悬停反馈，
	 * 所以列表行单独走这一档。
	 */
	public static int ROW_HOVER = 0xFF333333;
	/** 标题文字 */
	public static int TITLE = 0xFFF0E6D2;
	/** 次级提示（比 MUTED 更弱，用于空态第二行、行内附注） */
	public static int HINT = 0xFF6A6A6A;
	/** 内嵌面板底（目录树列、榜单占位块、列表行底） */
	public static int INNER = 0xFF1C1C1C;
	/** 内嵌面板悬停 */
	public static int INNER_HOVER = 0xFF2E2E2E;
	/** 页内次级横条（筛选栏、底栏、面包屑头） */
	public static int BAR_SUB = 0xFF232323;
	/** 进度条/滑轨底 */
	public static int TRACK = 0xFF151515;
	/** 输入框底（搜索框、价格框）：两套主题都保持深色，和原版文本框一致 */
	public static int FIELD = 0xFF151515;
	/** 输入框内文字（深色框上必须白字，原版主题也不能用 TEXT） */
	public static int FIELD_TEXT = 0xFFE0E0E0;
	/** 筛选 chip / 小按钮的「已激活」底 */
	public static int PRIMARY_SOFT = 0xFF3A3020;
	/** 上一个的悬停态 */
	public static int PRIMARY_SOFT_HOVER = 0xFF4A3F22;
	/** 已激活 chip 上的文字（必须和 PRIMARY_SOFT 成对，否则原版主题里看不见） */
	public static int PRIMARY_SOFT_TEXT = 0xFFFFE9A8;
	/** 禁用态按钮底 */
	public static int DISABLED_BG = 0xFF2A2A2A;
	/** 禁用态文字 */
	public static int DISABLED_TEXT = 0xFF808080;
	/** 空物品格占位底 */
	public static int SLOT = 0xFF444444;
	/** 卡片/行在悬停时的描边 */
	public static int EDGE_HOVER = 0xFF5A5A5A;
	/** 物品格悬停的半透明罩 */
	public static int SLOT_HILITE = 0x66FFFFFF;
	/** 物品格悬停描边 */
	public static int SLOT_EDGE = 0xFFFFFFFF;
	/** 物品格「不可用」遮罩 */
	public static int SLOT_DIM = 0x88000000;
	/** 成功/达成（签到 ✓、里程碑已达成） */
	public static int OK = 0xFF6BD46B;
	/** 主色（按钮/选中条） */
	public static int PRIMARY = 0xFFFFAA00;
	public static int PRIMARY_HOVER = 0xFFFFC24A;
	public static int ON_PRIMARY = 0xFF201800;
	/** 危险操作 */
	public static int DANGER = 0xFFB84242;
	public static int DANGER_HOVER = 0xFFD05050;
	/** 描边 */
	public static int OUTLINE = 0xFF3A3A3A;
	/**
	 * 交互控件的常态描边（描边按钮）。
	 *
	 * <p>必须与 {@link #PRIMARY} 明确区分：金色只表示「鼠标停在这里 / 已激活」，
	 * 常态描边用中性灰。原来描边按钮常态就用 PRIMARY ⇒ 用户分不清是样式还是悬停。
	 */
	public static int EDGE_IDLE = 0xFF4E4E4E;
	/** T55：硬币图标面/描边（金）。几何绘制，不走字形也不依赖 Numismatic 贴图。 */
	public static int COIN_GOLD = 0xFFFFCC33;
	public static int COIN_GOLD_EDGE = 0xFF8A6410;
	public static int COIN_SILVER = 0xFFDCE3EC;
	public static int COIN_SILVER_EDGE = 0xFF7C8794;
	public static int COIN_COPPER = 0xFFD07A3C;
	public static int COIN_COPPER_EDGE = 0xFF7A3F1C;

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
			ROW_HOVER = 0x33FFFFFF;
			TITLE = 0xFFFFFFFF;
			HINT = 0xCCBBBBBB;
			INNER = 0x33000000;
			INNER_HOVER = 0x22FFFFFF;
			BAR_SUB = 0x33000000;
			TRACK = 0x33000000;
			FIELD = 0x55000000;
			FIELD_TEXT = 0xFFFFFFFF;
			PRIMARY_SOFT = 0x44FFD54A;
			PRIMARY_SOFT_HOVER = 0x66FFD54A;
			PRIMARY_SOFT_TEXT = 0xFFFFE68A;
			DISABLED_BG = 0x22000000;
			DISABLED_TEXT = 0x99BBBBBB;
			SLOT = 0x33FFFFFF;
			EDGE_HOVER = 0x66FFFFFF;
			OK = 0xFF7DE07D;
			CARD_OK = 0x2250C878;
			SLOT_HILITE = 0x55FFFFFF;
			SLOT_EDGE = 0xFFFFFFFF;
			SLOT_DIM = 0x66000000;
			PRIMARY = 0xFFFFD54A;
			PRIMARY_HOVER = 0xFFFFE68A;
			ON_PRIMARY = 0xFF241C00;
			DANGER = 0xFFC05A5A;
			DANGER_HOVER = 0xFFD97A7A;
			OUTLINE = 0x66FFFFFF;
			EDGE_IDLE = 0x77FFFFFF;
			COIN_GOLD = 0xFFFFD54A;
			COIN_GOLD_EDGE = 0xFF8A6410;
			COIN_SILVER = 0xFFE8EFF8;
			COIN_SILVER_EDGE = 0xFF7C8794;
			COIN_COPPER = 0xFFCE7A3E;
			COIN_COPPER_EDGE = 0xFF7A3F1C;
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
			ROW_HOVER = 0xFFB9B9B9;
			TITLE = 0xFF202020;
			HINT = 0xFF6E6E6E;
			INNER = 0xFFB4B4B4;
			INNER_HOVER = 0xFFA8A8A8;
			BAR_SUB = 0xFFBEBEBE;
			TRACK = 0xFF8B8B8B;
			FIELD = 0xFF000000;
			FIELD_TEXT = 0xFFFFFFFF;
			PRIMARY_SOFT = 0xFFB8C4E8;
			PRIMARY_SOFT_HOVER = 0xFFCBD7F4;
			PRIMARY_SOFT_TEXT = 0xFF1B2A66;
			DISABLED_BG = 0xFFB0B0B0;
			DISABLED_TEXT = 0xFF565656;
			SLOT = 0xFF8B8B8B;
			EDGE_HOVER = 0xFF6E6E6E;
			OK = 0xFF2E7D32;
			CARD_OK = 0xFFF4FAF4;
			SLOT_HILITE = 0x553A54A8;
			SLOT_EDGE = 0xFF3A54A8;
			SLOT_DIM = 0x66000000;
			PRIMARY = 0xFF2A3F8F;
			PRIMARY_HOVER = 0xFF3A54A8;
			ON_PRIMARY = 0xFFFFFFFF;
			DANGER = 0xFF8B2F2F;
			DANGER_HOVER = 0xFFA63C3C;
			OUTLINE = 0xFF7A7A7A;
			EDGE_IDLE = 0xFF8A8A8A;
			// 原版灰底上要更暗一档，否则金色发白
			COIN_GOLD = 0xFFE0A81E;
			COIN_GOLD_EDGE = 0xFF7A5A00;
			COIN_SILVER = 0xFFC8D0DA;
			COIN_SILVER_EDGE = 0xFF6E7884;
			COIN_COPPER = 0xFFB86A32;
			COIN_COPPER_EDGE = 0xFF66300F;
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
			ROW_HOVER = 0xFF333333;
			TITLE = 0xFFF0E6D2;
			HINT = 0xFF6A6A6A;
			INNER = 0xFF1C1C1C;
			INNER_HOVER = 0xFF2E2E2E;
			BAR_SUB = 0xFF232323;
			TRACK = 0xFF151515;
			FIELD = 0xFF151515;
			FIELD_TEXT = 0xFFE0E0E0;
			PRIMARY_SOFT = 0xFF3A3020;
			PRIMARY_SOFT_HOVER = 0xFF4A3F22;
			PRIMARY_SOFT_TEXT = 0xFFFFE9A8;
			DISABLED_BG = 0xFF2A2A2A;
			DISABLED_TEXT = 0xFF808080;
			SLOT = 0xFF444444;
			EDGE_HOVER = 0xFF5A5A5A;
			OK = 0xFF6BD46B;
			CARD_OK = 0xFF243026;
			SLOT_HILITE = 0x66FFFFFF;
			SLOT_EDGE = 0xFFFFFFFF;
			SLOT_DIM = 0x88000000;
			PRIMARY = 0xFFFFAA00;
			PRIMARY_HOVER = 0xFFFFC24A;
			ON_PRIMARY = 0xFF201800;
			DANGER = 0xFFB84242;
			DANGER_HOVER = 0xFFD05050;
			OUTLINE = 0xFF3A3A3A;
			EDGE_IDLE = 0xFF4E4E4E;
			COIN_GOLD = 0xFFFFCC33;
			COIN_GOLD_EDGE = 0xFF8A6410;
			COIN_SILVER = 0xFFDCE3EC;
			COIN_SILVER_EDGE = 0xFF7C8794;
			COIN_COPPER = 0xFFD07A3C;
			COIN_COPPER_EDGE = 0xFF7A3F1C;
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
