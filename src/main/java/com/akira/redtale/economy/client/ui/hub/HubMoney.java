package com.akira.redtale.economy.client.ui.hub;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

/**
 * T55：货币显示的<b>唯一出口</b>（图标优先 + 三段分解）。
 *
 * <p>以前每个页面各写各的：{@code "12 铜"}、{@code Currency.format(raw)}、
 * 侧栏自己算金/银/铜 —— 同一个数字在界面上有三种长相。现在统一走这里：
 * <ul>
 *   <li>{@link #draw}：图标 + 数字，逐段画（金/银/铜）。<b>零值段不画</b>，用于行内单价</li>
 *   <li>{@link #drawFull}：同上但<b>三段都画</b>，用于余额（Aki 口径：余额不许隐藏零值段）</li>
 *   <li>{@link #text}：纯文字兜底，只给状态行/聊天这类「只能塞字符串」的地方</li>
 * </ul>
 *
 * <p>★图标来源两档（T70）★：优先用 <b>Numismatic 的原始硬币贴图</b>
 * （{@code numismatic-overhaul:textures/item/{gold,silver,bronze}_coin.png}，运行时读，不复制进本模组资源），
 * 模组没装 / 贴图缺失时回退 7×7 几何圆片 + 1px 描边，纵向 y+1 与 9px 字形基线对齐。
 * 可用性**缓存**：不每次绘制都查资源包；未命中时每 5 秒重试一次（资源重载后能自己恢复）。
 *
 * <p>★T67（字体）★：聊天/HUD 那种「只能塞字符串」的位置用 {@link #asText}，
 * 字体 {@code redtale_economy:coins} 存在时走 {@code \uE000-\uE002} 字形，否则退回纯文字。
 */
public final class HubMoney {

	/**
	 * 硬币图标边长（含描边）。
	 *
	 * <p>★11 不是随手放大的★（Aki 报「货币图标过小」）：原来的 8（16×16 贴图缩一半）/ 7（几何兜底）
	 * 只有相邻数字字高（9px 字体的墨迹约 7px）的 1.0~1.1 倍，读起来像数字前面的一个小点，
	 * 币种靠颜色区分而不是形状。11 = 字高 1.5 倍，与 9px 数字并排时"图标在前、数字在后"的主次清楚，
	 * 又不会把 18px 行高撑开（图标 y 上移 1px，占 y-1..y+9，仍在行内）。
	 */
	public static final int ICON = 11;
	/**
	 * 图标与数字之间的间距。
	 *
	 * <p>★保持 2 而不是跟着放大★：16×16 的硬币贴图本身四周有 1~2px 透明边，
	 * 画成 11px 后视觉留白已经够了；再加会把 3 段价钱整体推宽，行情行/商店行的右对齐栏
	 * （`maxW` 预算）就得一起改，得不偿失。
	 */
	private static final int ICON_TEXT = 2;
	/** 两个币种之间的间距（同上：段间靠币面透明边留白，不额外加宽）。 */
	private static final int SEG_GAP = 5;

	/** Numismatic 硬币贴图的实际边长（16×16，见 jar 内 textures/item/*_coin.png）。 */
	private static final int TEX_W = 16;
	private static final int TEX_H = 16;

	/** T70：Numismatic 原始贴图（顺序 = 金/银/铜，与 split() 的 [0][1][2] 对齐）。 */
	private static final Identifier[] COIN_TEX = {
			Identifier.tryParse("numismatic-overhaul:textures/item/gold_coin.png"),
			Identifier.tryParse("numismatic-overhaul:textures/item/silver_coin.png"),
			Identifier.tryParse("numismatic-overhaul:textures/item/bronze_coin.png"),
	};

	/** T70：贴图可用性缓存（null=还没查过）。命中后永不再查。 */
	private static final Boolean[] TEX_OK = new Boolean[3];
	/** 上次查询失败的时间戳：失败后 5 秒才重试，避免每帧查资源包。 */
	private static final long[] TEX_RETRY = new long[3];

	/** T67：硬币字体的字形（\uE000 金 / \uE001 银 / \uE002 铜）。 */
	private static final char[] COIN_GLYPH = {'\uE000', '\uE001', '\uE002'};
	/** T67：字体 id（B 提供 assets/redtale_economy/font/coins.json）。 */
	private static final Identifier COIN_FONT = Identifier.tryParse("redtale_economy:coins");
	/**
	 * T67：字体 json 引用的位图（{@code BitmapFont.Loader} 在 {@code textures/} 下解析）。
	 *
	 * <p>★必须一起查★：只有 json 没有 png 时字体仍会被加载器拒绝，
	 * 但 {@code font/coins.json} 依然存在 ⇒ 只查 json 会得到「字体可用」的假绿
	 * （B 队列实测踩到：字形落到 unifont 缺字方块，界面上是一串空白）。
	 */
	private static final Identifier COIN_FONT_TEX =
			Identifier.tryParse("redtale_economy:textures/font/coins.png");
	/** T67：字体可用性缓存（同上：查到就用，查不到 5 秒后重试）。 */
	private static Boolean fontOk;
	private static long fontRetry;

	private HubMoney() {
	}

	/** 拆成 {金, 银, 铜}（负数按 0 处理，与 {@code Currency.format} 同口径）。 */
	public static long[] split(long raw) {
		long copper = Math.max(0, raw);
		long gc = Math.max(1, com.akira.redtale.economy.economy.Currency.goldInCopper());
		long sc = Math.max(1, com.akira.redtale.economy.economy.Currency.silverInCopper());
		long gold = copper / gc;
		long rest = copper % gc;
		return new long[]{gold, rest / sc, rest % sc};
	}

	/** 零值段隐藏的紧凑文字：{@code 1金20银3铜}（余额为 0 时给「0铜」）。 */
	public static String text(long raw) {
		long[] p = split(raw);
		StringBuilder sb = new StringBuilder();
		if (p[0] > 0) sb.append(p[0]).append('金');
		if (p[1] > 0) sb.append(p[1]).append('银');
		if (p[2] > 0 || sb.length() == 0) sb.append(p[2]).append('铜');
		return sb.toString();
	}

	/**
	 * T67：硬币字体可用时的「字形 + 数字」文本（金 \uE000 / 银 \uE001 / 铜 \uE002）。
	 *
	 * <p>字体 {@code redtale_economy:coins} 由 B 提供（{@code assets/redtale_economy/font/coins.json}
	 * + 一张 3 格宽的位图）。**缺失时自动退回纯文字**，调用方不用判断。
	 *
	 * @param full true = 三段全显（余额），false = 零值段隐藏（单价）
	 */
	public static Text asText(long raw, boolean full) {
		return asText(raw, full, 0);
	}

	/**
	 * T67：可指定数字颜色的版本。
	 *
	 * <p>{@code color<=0} 用默认（金 0xFFD700 / 其余白）；底栏、hint 这类次要信息
	 * 传 {@link HubTheme#MUTED}，否则一串亮白数字会把整条的层级关系打乱
	 * —— 硬币字形本身带贴图颜色，不受这里影响。
	 */
	public static Text asText(long raw, boolean full, int color) {
		if (raw < 0) return Text.literal("…");
		if (!fontReady()) return Text.literal(full ? fullText(raw) : text(raw));
		long[] p = split(raw);
		net.minecraft.text.MutableText out = Text.empty();
		boolean any = false;
		for (int i = 0; i < 3; i++) {
			boolean show = full || p[i] > 0 || (i == 2 && p[0] == 0 && p[1] == 0);
			if (!show) continue;
			if (any) out.append(Text.literal(" "));
			Style st = Style.EMPTY.withFont(COIN_FONT);
			out.append(Text.literal(String.valueOf(COIN_GLYPH[i])).setStyle(st));
			int c = color > 0 ? color : (i == 0 ? 0xFFD700 : 0xFFFFFF);
			out.append(Text.literal(Long.toString(p[i])).setStyle(Style.EMPTY.withColor(c)));
			any = true;
		}
		return any ? out : Text.literal(full ? fullText(raw) : text(raw));
	}

	/** T67：硬币字体是否可用（同样缓存 + 5 秒重试）。 */
	public static boolean fontReady() {
		if (fontOk != null && fontOk) return true;
		long now = System.currentTimeMillis();
		if (fontOk != null && now - fontRetry < 5000L) return false;
		boolean found = false;
		try {
			MinecraftClient mc = MinecraftClient.getInstance();
			if (mc != null && COIN_FONT != null && COIN_FONT_TEX != null) {
				var rm = mc.getResourceManager();
				found = rm.getResource(new Identifier(COIN_FONT.getNamespace(),
						"font/" + COIN_FONT.getPath() + ".json")).isPresent()
						&& rm.getResource(COIN_FONT_TEX).isPresent();
			}
		} catch (Exception ignored) {
			found = false;
		}
		fontOk = found;
		fontRetry = now;
		return found;
	}

	/**
	 * T70/T67 开发期探针：三种硬币贴图 + 金币字体的实际可用性。
	 *
	 * <p>为什么需要探针：截图里「贴图 8px」和「几何圆片 7px」差 1 像素，肉眼判不出用的是哪条路径；
	 * 日志里给 true/false 才是可判定的证据。也顺手把「资源重载后是否自恢复」暴露出来。
	 */
	public static String texProbe() {
		boolean g = texOk(0), s = texOk(1), b = texOk(2);
		return "coins tex gold=" + g + " silver=" + s + " bronze=" + b
				+ " iconW=" + iconW() + " font=" + fontReady();
	}

	/** 三段全显的文字（余额口径）：{@code 0金0银5铜}。 */
	public static String fullText(long raw) {
		long[] p = split(raw);
		return p[0] + "金" + p[1] + "银" + p[2] + "铜";
	}

	/** 三段全显的宽（供右侧对齐/预算判断）。 */
	public static int fullWidth(TextRenderer tr, long raw) {
		long[] p = split(raw);
		int ic = iconW();
		int w = ic + ICON_TEXT + tr.getWidth(Long.toString(p[0]));
		w += SEG_GAP + ic + ICON_TEXT + tr.getWidth(Long.toString(p[1]));
		w += SEG_GAP + ic + ICON_TEXT + tr.getWidth(Long.toString(p[2]));
		return w;
	}

	/** 零值隐藏的宽。 */
	public static int width(TextRenderer tr, long raw) {
		long[] p = split(raw);
		int w = 0;
		int segs = 0;
		int ic = iconW();
		for (int i = 0; i < 3; i++) {
			boolean show = p[i] > 0 || (i == 2 && p[0] == 0 && p[1] == 0);
			if (!show) continue;
			if (segs++ > 0) w += SEG_GAP;
			w += ic + ICON_TEXT + tr.getWidth(Long.toString(p[i]));
		}
		return w;
	}

	/**
	 * 画「图标 + 数字」三段（零值段不画）。
	 *
	 * @param maxW 可用宽度（≤0 表示不限）；放不下的段直接跳过，不会溢出压到别的东西
	 * @return 实际画出的总宽
	 */
	public static int draw(DrawContext dc, TextRenderer tr, int x, int y, long raw, int maxW) {
		return drawInternal(dc, tr, x, y, raw, maxW, false);
	}

	/** 画三段全显版本（余额用）：零值段也画，读起来是「0金 100银 0铜」。 */
	public static int drawFull(DrawContext dc, TextRenderer tr, int x, int y, long raw, int maxW) {
		return drawInternal(dc, tr, x, y, raw, maxW, true);
	}

	/** 币种单位字（与 {@link #split} 的下标对齐）。 */
	private static final String[] UNIT = {"金", "银", "铜"};

	/** 币种单位字：0 金 / 1 银 / 2 铜。 */
	public static String unitOf(int kind) {
		return UNIT[kind < 0 || kind > 2 ? 0 : kind];
	}

	/**
	 * 头像卡这类「主视图」的硬币边长：<b>原生 16px，绝不缩放</b>（A 第 N+79 轮）。
	 *
	 * <p>★为什么不能缩到 11★：16×16 的硬币贴图用最近邻缩到 11×11 时，圆形边缘被啃成
	 * 菱形/锯齿，看着像「手画的宝石」；而且三段的 11px 预算（85px）本来就塞不进侧栏内宽
	 * （80px）—— 旧代码放不下就静默换成 T67 硬币字形，于是玩家看到的是<b>我们自绘的字形</b>，
	 * 不是 Numismatic 的图标。Aki 一眼就看出来了。
	 */
	public static final int ICON_BIG = 16;

	/**
	 * 画一枚硬币图标（<b>MOD 自己的图标</b>；缺失时才退回几何兜底）。
	 *
	 * <p>三档来源，按「最像背包里那枚币」排序：
	 * <ol>
	 *   <li>物品本体 {@code numismatic-overhaul:gold_coin} 走 {@code drawItem} —— 和背包/手上
	 *       一模一样（模型带原生光影），这是 Aki 要的「MOD 的图标」；</li>
	 *   <li>物品不在（模组没装）但贴图在 ⇒ 16px 原样贴图；</li>
	 *   <li>都没有 ⇒ 几何兜底（明确是近似，不是 MOD 图标）。</li>
	 * </ol>
	 *
	 * @param kind 0 金 / 1 银 / 2 铜
	 */
	public static void coinIcon(DrawContext dc, int x, int y, int kind) {
		int k = kind < 0 || kind > 2 ? 0 : kind;
		if (!coinProbeLogged) {
			coinProbeLogged = true;
			// 一次性探针：下次能从 latest.log 直接判「用的是物品模型/贴图/兜底」，不用猜色号
			com.akira.redtale.economy.RedtaleEconomy.LOGGER.info(
					"[hub] 头像卡硬币来源：{} item={}", texProbe(), coinItem(k) != null);
		}
		var item = coinItem(k);
		if (item != null) {
			dc.drawItem(new net.minecraft.item.ItemStack(item), x, y);
			return;
		}
		if (texOk(k)) {
			dc.drawTexture(COIN_TEX[k], x, y, ICON_BIG, ICON_BIG, 0.0f, 0.0f,
					TEX_W, TEX_H, TEX_W, TEX_H);
			return;
		}
		coinGeometry(dc, x, y, k, ICON_BIG);
	}

	/** 硬币物品 id（顺序 = 金/银/铜）；解析一次就缓存，模组没装时全为 null。 */
	private static final Identifier[] COIN_ITEM_ID = {
			Identifier.tryParse("numismatic-overhaul:gold_coin"),
			Identifier.tryParse("numismatic-overhaul:silver_coin"),
			Identifier.tryParse("numismatic-overhaul:bronze_coin"),
	};

	private static net.minecraft.item.Item coinItem(int kind) {
		if (!COIN_ITEM_RESOLVED[kind]) {
			COIN_ITEM_RESOLVED[kind] = true;
			net.minecraft.item.Item found = null;
			try {
				Identifier id = COIN_ITEM_ID[kind];
				if (id != null) {
					var it = net.minecraft.registry.Registries.ITEM.get(id);
					if (it != null && it != net.minecraft.item.Items.AIR) found = it;
				}
			} catch (Exception ignored) {
				found = null;
			}
			COIN_ITEM_CACHE[kind] = found;
		}
		return COIN_ITEM_CACHE[kind];
	}

	private static final net.minecraft.item.Item[] COIN_ITEM_CACHE = new net.minecraft.item.Item[3];
	private static final boolean[] COIN_ITEM_RESOLVED = new boolean[3];

	private static boolean coinProbeLogged;

	private static int drawInternal(DrawContext dc, TextRenderer tr, int x, int y, long raw,
			int maxW, boolean full) {
		if (raw < 0) {
			dc.drawText(tr, Text.literal("…"), x, y, HubTheme.MUTED, false);
			return tr.getWidth("…");
		}
		long[] p = split(raw);
		int cx = x;
		int drawn = 0;
		int ic = iconW();
		for (int i = 0; i < 3; i++) {
			boolean show = full || p[i] > 0 || (i == 2 && p[0] == 0 && p[1] == 0);
			if (!show) continue;
			String n = Long.toString(p[i]);
			int need = ic + ICON_TEXT + tr.getWidth(n);
			if (drawn > 0) need += SEG_GAP;
			if (maxW > 0 && cx - x + need > maxW) break;
			if (drawn > 0) cx += SEG_GAP;
			coin(dc, cx, y - 1, i);
			dc.drawText(tr, Text.literal(n), cx + ic + ICON_TEXT, y,
					i == 0 ? HubTheme.GOLD : HubTheme.TEXT, false);
			cx += ic + ICON_TEXT + tr.getWidth(n);
			drawn++;
		}
		return cx - x;
	}

	/**
	 * 硬币图标边长：贴图与几何兜底**同尺寸**。
	 *
	 * <p>旧实现贴图 8 / 兜底 7，差 1px 导致"装了 Numismatic"和"没装"两种环境下排版宽度不同
	 * （{@link #width}/{@link #fullWidth} 跟着变），右对齐的价钱会跳 1~3px。统一成 {@link #ICON}。
	 */
	private static int iconW() {
		return ICON;
	}

	/**
	 * T70：Numismatic 贴图是否可用（缓存 + 失败重试）。
	 *
	 * <p>★不能在每次绘制里查资源包★：一次查包在 60fps 下就是每秒 60 次。
	 * 命中后永久缓存；未命中每 5 秒重试一次，资源重载完成后能自己恢复。
	 */
	private static boolean texOk(int kind) {
		int k = kind < 0 || kind > 2 ? 0 : kind;
		Boolean ok = TEX_OK[k];
		if (ok != null && ok) return true;
		long now = System.currentTimeMillis();
		if (ok != null && now - TEX_RETRY[k] < 5000L) return false;
		boolean found = false;
		try {
			Identifier id = COIN_TEX[k];
			MinecraftClient mc = MinecraftClient.getInstance();
			found = id != null && mc != null && mc.getResourceManager().getResource(id).isPresent();
		} catch (Exception ignored) {
			found = false;
		}
		TEX_OK[k] = found;
		TEX_RETRY[k] = now;
		return found;
	}

	/**
	 * 硬币图标：优先原版贴图（缩放绘制，不改资源），缺失则几何圆片。
	 *
	 * <p>★必须用带 region 的重载★：{@code drawTexture(id,x,y,w,h,u,v,rw,rh,tw,th)}。
	 * 只用 9 参版本（{@code id,x,y,u,v,w,h,tw,th}）时 <b>w/h 同时被当作源区域</b>，
	 * 于是只从 16×16 贴图里抠出左上 8×8 画到 8×8 —— 屏幕上是一枚被切掉右下的残缺币
	 * （Aki 报的「货币图标显示不完整」就是这个）。要「整张缩放」，必须显式给 region=16。
	 */
	private static void coin(DrawContext dc, int x, int y, int kind) {
		if (texOk(kind)) {
			int s = iconW();
			dc.drawTexture(COIN_TEX[kind], x, y, s, s, 0.0f, 0.0f, TEX_W, TEX_H, TEX_W, TEX_H);
			return;
		}
		coinGeometry(dc, x, y, kind);
	}

	/** 几何硬币：1px 描边 + 实心面，边长 = {@link #ICON}，四角切掉（圆形近似）。 */
	private static void coinGeometry(DrawContext dc, int x, int y, int kind) {
		coinGeometry(dc, x, y, kind, ICON);
	}

	/** 几何硬币（指定边长；16px 时 2px 描边更接近原生贴图的厚重边）。 */
	private static void coinGeometry(DrawContext dc, int x, int y, int kind, int size) {
		int ring = size >= 16 ? 2 : 1;
		int face = switch (kind) {
			case 0 -> HubTheme.COIN_GOLD;
			case 1 -> HubTheme.COIN_SILVER;
			default -> HubTheme.COIN_COPPER;
		};
		int edge = switch (kind) {
			case 0 -> HubTheme.COIN_GOLD_EDGE;
			case 1 -> HubTheme.COIN_SILVER_EDGE;
			default -> HubTheme.COIN_COPPER_EDGE;
		};
		// 外层（描边）：两个互相垂直的矩形叠出「切角」
		dc.fill(x + ring, y, x + size - ring, y + size, edge);
		dc.fill(x, y + ring, x + size, y + size - ring, edge);
		// 内层（面）：同样两块，内缩 ring + 1
		int in = ring + 1;
		dc.fill(x + ring, y + in, x + size - ring, y + size - in, face);
		dc.fill(x + in, y + ring, x + size - in, y + size - ring, face);
	}
}
