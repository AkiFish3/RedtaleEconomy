package com.akira.redtale.economy.legacy.screen.client;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;

/**
 * 权重气泡（T80）：一个格子权重的就地编辑小面板。
 *
 * <pre>
 * ┌──────────────────────┐
 * │ 附魔金苹果 × 3        │   ← 物品名 × 数量
 * │ 权重  [−]  12  [+]   │   ← 点 ± 改 1，长按 0.3s 后每 50ms ±1
 * │                [确认] │
 * └──────────────────────┘
 * </pre>
 *
 * <p>两个入口共用这一个组件（规格 §二）：悬停格子按 {@code W} 在鼠标处弹、权重模式下点格子在
 * 格子旁弹。区别只在**位置**，绘制/命中/数值语义完全一致。
 *
 * <p>★坐标是绝对屏幕坐标★：它在 {@code PackageEditScreen.render} 里画在
 * {@code drawMouseoverTooltip} **之后**（规格 §六「气泡被 tooltip 盖住 ⇒ 最后画」），
 * 而那一层已经退出了 panel 的 translate，用的就是绝对坐标。内部的鼠标命中判定同样吃绝对坐标。
 *
 * <p>★数值不自己存★：显示值每次从 {@link PackageEditClientState} 取（服务端回包的唯一真值）。
 * 本地自增计数会在「连续点 + 但服务端还没回包」时越加越多，回包一到又跳回去（抖动），
 * 也会出现界面显示 13 而服务端是 5 的分叉。
 */
public final class WeightPopup {

    /** 气泡尺寸。宽度按「3 位权重 + ± 按钮 + 右对齐确认」的最大内容定，改这里要同步 {@link #draw}。 */
    public static final int W = 132;
    public static final int H = 52;

    public static final int ID_NONE = 0;
    public static final int ID_MINUS = 1;
    public static final int ID_PLUS = 2;
    public static final int ID_CONFIRM = 3;

    /** − 按钮热区（相对气泡左上角）。 */
    private static final int MINUS_X = 46;
    private static final int MINUS_W = 13;
    private static final int BTN_Y = 17;
    private static final int BTN_H = 12;
    /** + 按钮热区。 */
    private static final int PLUS_X = 96;
    private static final int PLUS_W = 13;
    /** 数值文本右对齐的右边界。 */
    private static final int VALUE_RIGHT = 94;
    /** 确认按钮热区（右下）。 */
    private static final int CONFIRM_W = 40;
    private static final int CONFIRM_H = 12;

    /** 面板配色（与 PackageEditScreen 一致，深浅分层用于让气泡浮在面板之上）。 */
    private static final int BG = 0xFF23232B;
    private static final int BORDER = 0xFF58D6A8;
    private static final int SHADOW = 0xC0000000;
    private static final int BTN_BG = 0xFF3A3A44;
    private static final int BTN_BG_HOVER = 0xFF4C4C58;
    private static final int LABEL = 0xFF9A9AA6;
    private static final int WHITE = 0xFFEDEDF2;

    private final int slot;
    private int x;
    private int y;

    private WeightPopup(int slot, int x, int y) {
        this.slot = slot;
        this.x = x;
        this.y = y;
    }

    /**
     * 在 {@code (x,y)} 起画一个气泡，并保证整块留在屏幕内（贴边时自动翻到另一侧）。
     *
     * <p>★必须钳位★：鼠标在屏幕右缘按 W 时 {@code x + W} 会超出逻辑宽度，气泡右侧的
     * +/确认 直接画到屏幕外 —— 看得见点不到（与第 43 轮面板锚点同一类缺陷）。
     */
    public static WeightPopup at(int slot, int x, int y, int screenW, int screenH) {
        int px = x;
        int py = y;
        if (px + W > screenW) px = screenW - W;
        if (py + H > screenH) py = screenH - H;
        if (px < 0) px = 0;
        if (py < 0) py = 0;
        return new WeightPopup(slot, px, py);
    }

    public int slot() {
        return slot;
    }

    public int x() {
        return x;
    }

    public int y() {
        return y;
    }

    public boolean contains(double mx, double my) {
        return mx >= x && mx < x + W && my >= y && my < y + H;
    }

    /** 命中哪个热区。返回 {@link #ID_NONE} 表示点在气泡内但不在任何按钮上。 */
    public int hit(double mx, double my) {
        if (!contains(mx, my)) return ID_NONE;
        double relX = mx - x;
        double relY = my - y;
        if (relY >= BTN_Y && relY < BTN_Y + BTN_H) {
            if (relX >= MINUS_X && relX < MINUS_X + MINUS_W) return ID_MINUS;
            if (relX >= PLUS_X && relX < PLUS_X + PLUS_W) return ID_PLUS;
        }
        if (relX >= W - 6 - CONFIRM_W && relX < W - 6 && relY >= H - 6 - CONFIRM_H && relY < H - 6) {
            return ID_CONFIRM;
        }
        return ID_NONE;
    }

    /**
     * 画气泡。
     *
     * @param weight 当前权重（从服务端状态镜像取，不是本地自增）
     */
    public void draw(DrawContext context, TextRenderer tr, ItemStack stack, int weight, int mouseX, int mouseY) {
        // 投影（1px 偏移的半透明黑），让气泡从面板上"浮"起来
        context.fill(x + 2, y + 2, x + W + 2, y + H + 2, SHADOW);
        context.fill(x, y, x + W, y + H, BG);
        // 描边：编辑态绿，与网格上的权重数字同色系
        context.fill(x, y, x + W, y + 1, BORDER);
        context.fill(x, y + H - 1, x + W, y + H, BORDER);
        context.fill(x, y, x + 1, y + H, BORDER);
        context.fill(x + W - 1, y, x + W, y + H, BORDER);

        // 第 1 行：名字 × 数量（名字可能超宽，截断到气泡内宽）
        String name = stack.isEmpty() ? "" : stack.getName().getString();
        String count = " × " + (stack.isEmpty() ? 1 : stack.getCount());
        String title = trim(tr, name, W - 8 - tr.getWidth(count)) + count;
        context.drawText(tr, title, x + 5, y + 4, WHITE, false);

        // 第 2 行：标签 + − 数值 +
        context.drawText(tr, Text.translatable("screen.redtale_economy.edit.popup.weight"),
                x + 5, y + 19, LABEL, false);
        button(context, tr, x + MINUS_X, y + BTN_Y, MINUS_W, BTN_H, "-", mouseX, mouseY);
        // 数值右对齐：3 位数也不会压到 + 按钮上
        String value = String.valueOf(weight);
        context.drawText(tr, value, x + VALUE_RIGHT - tr.getWidth(value), y + 19,
                PackageEditScreen.weightColor(weight, true), false);
        button(context, tr, x + PLUS_X, y + BTN_Y, PLUS_W, BTN_H, "+", mouseX, mouseY);

        // 第 3 行：确认
        int cx = x + W - 6 - CONFIRM_W;
        int cy = y + H - 6 - CONFIRM_H;
        boolean hoverConfirm = mouseX >= cx && mouseX < cx + CONFIRM_W && mouseY >= cy && mouseY < cy + CONFIRM_H;
        context.fill(cx, cy, cx + CONFIRM_W, cy + CONFIRM_H, hoverConfirm ? BTN_BG_HOVER : BTN_BG);
        Text confirm = Text.translatable("screen.redtale_economy.edit.popup.confirm");
        context.drawText(tr, confirm, cx + (CONFIRM_W - tr.getWidth(confirm)) / 2, cy + 2, WHITE, false);
    }

    private static void button(DrawContext context, TextRenderer tr, int bx, int by, int bw, int bh, String label,
                               int mouseX, int mouseY) {
        boolean hover = mouseX >= bx && mouseX < bx + bw && mouseY >= by && mouseY < by + bh;
        context.fill(bx, by, bx + bw, by + bh, hover ? BTN_BG_HOVER : BTN_BG);
        context.fill(bx, by, bx + bw, by + 1, 0xFF585866);
        context.drawText(tr, label, bx + (bw - tr.getWidth(label)) / 2, by + 2, WHITE, false);
    }

    private static String trim(TextRenderer tr, String s, int maxWidth) {
        if (maxWidth <= 0) return "";
        if (tr.getWidth(s) <= maxWidth) return s;
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (tr.getWidth(sb.toString() + c + "…") > maxWidth) break;
            sb.append(c);
        }
        return sb + "…";
    }
}
