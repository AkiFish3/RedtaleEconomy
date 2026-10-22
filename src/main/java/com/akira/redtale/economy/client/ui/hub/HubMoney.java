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

	/** 硬币图标边长（含描边）。 */
	public static final int ICON = 7;
	/** 图标与数字之间的间距。 */
	private static final int ICON_TEXT = 2;
	/** 两个币种之间的间距。 */
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
			coin(dc, cx, y + 1, i);
			dc.drawText(tr, Text.literal(n), cx + ic + ICON_TEXT, y,
					i == 0 ? HubTheme.GOLD : HubTheme.TEXT, false);
			cx += ic + ICON_TEXT + tr.getWidth(n);
			drawn++;
		}
		return cx - x;
	}

	/** 硬币图标宽：有原版贴图时 8（16×16 缩一半），否则 7。 */
	private static int iconW() {
		return texOk(0) ? 8 : ICON;
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

	/** 几何硬币：1px 描边 + 实心面，7×7，四角切掉（圆形近似）。 */
	private static void coinGeometry(DrawContext dc, int x, int y, int kind) {
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
		// 外层（描边）
		dc.fill(x + 1, y, x + ICON - 1, y + ICON, edge);
		dc.fill(x, y + 1, x + ICON, y + ICON - 1, edge);
		// 内层（面）
		dc.fill(x + 2, y + 1, x + ICON - 2, y + ICON - 1, face);
		dc.fill(x + 1, y + 2, x + ICON - 1, y + ICON - 2, face);
	}
}
