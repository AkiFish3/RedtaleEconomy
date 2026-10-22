package com.akira.redtale.economy.legacy.screen.client;

import com.akira.redtale.economy.legacy.data.PackageCatalog;
import com.akira.redtale.economy.legacy.data.PackageData;
import com.akira.redtale.economy.legacy.init.RENetworking;
import com.akira.redtale.economy.legacy.screen.PackageEditHandler;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.List;

/**
 * 包裹可视化编辑器（T71）。
 *
 * <pre>
 * ┌──────────────────────────────────────────────┐
 * │ 编辑包裹                          总权重 12/3 │
 * │ 模式  [全部][随机][选择]                       │
 * │ 档位  [普通][稀有][史诗][传说][神话][活动]      │
 * │ ┌──────────────────────────┐ ┌─────────────┐ │
 * │ │  15 × 6 候选物品网格      │ │ 权重表       │ │
 * │ │  （从背包拖入）           │ │ ▶图标 - 10 + │ │
 * │ └──────────────────────────┘ └─────────────┘ │
 * │  玩家背包 / 快捷栏                            │
 * └──────────────────────────────────────────────┘
 * </pre>
 *
 * <p>保存语义：**没有保存按钮**（规格 §十一 / T76）。拖入、改权重、改模式都会置脏并由服务端
 * 1 秒 debounce 落盘 + 关窗 + 掉线三保险写入物品 NBT。
 *
 * <p>几何全部读 {@link PackageEditHandler} 的常量：槽位位置（服务端）与绘制/命中（这里）共用一份，
 * 不再出现"图标在 A、点击判在 B"的错位（旧编辑器两处各写了一遍坐标）。
 */
public class PackageEditScreen extends HandledScreen<PackageEditHandler> {

    // ——— 几何常量（全部相对屏幕左上角，与 handler 的槽位坐标同一坐标系） ———
    /**
     * 面板 408×240。
     *
     * <p>★尺寸不能超过逻辑分辨率★：旧值 300×318 在 1920×1080（guiScale auto=4 ⇒ 480×270）、
     * 1366×768（gs3 ⇒ 455×256）、默认窗口 854×480（gs2 ⇒ 427×240）三种常见逻辑尺寸下都超高，
     * `top()` 为负 ⇒ 模式/档位 chip 被顶出屏幕（改不了模式）、底部快捷栏也看不见。
     * 现在网格改成 15×6 横向铺开，面板压到 408×240：最窄的 427×240 也装得下。
     */
    private static final int PANEL_W = 408;
    private static final int PANEL_H = 240;

    private static final int MODE_Y = 16;
    private static final int MODE_CHIP_W = 86;
    private static final int MODE_CHIP_H = 12;
    private static final int MODE_CHIP_GAP = 4;

    private static final int TIER_Y = 30;
    private static final int TIER_CHIP_W = 45;
    private static final int TIER_CHIP_H = 12;

    /** 表格起点 = 网格右边（8 + 15×18 = 278）再留 8px。116 宽 ⇒ 286+116 = 402，右留 6px。 */
    private static final int TABLE_X = 286;
    private static final int TABLE_W = 116;
    private static final int TABLE_Y = PackageEditHandler.GRID_Y;
    private static final int TABLE_ROW_H = 18;
    private static final int TABLE_ROWS = PackageEditHandler.GRID_ROWS;

    /** 表格行里三个热区的相对偏移（相对 TABLE_X）。 */
    private static final int BTN_MINUS_X = 20;
    private static final int BTN_W = 11;
    private static final int WEIGHT_X = 33;
    private static final int BTN_PLUS_X = 52;
    private static final int NAME_X = 66;
    /** 名字列可用宽度（右侧留 2px）。★绘制与"是否截断"判定共用这一份★。 */
    private static final int TABLE_NAME_W = TABLE_W - NAME_X - 2;

    /** 表格滚动偏移（按"已占用条目"计，不是按格子）。 */
    private int tableScroll = 0;
    /** 悬停缓存：用于 tooltip 与滚轮目标。 */
    private int hoverGridSlot = -1;
    private int hoverTableRow = -1;

    // ——— T80 权重编辑器：入口①按键气泡 / 入口②右上角按钮 ———

    /** 唤出气泡的按键（规格 §二 允许改键；先固定 W，键位冲突时只改这一处）。 */
    private static final int WEIGHT_KEY = org.lwjgl.glfw.GLFW.GLFW_KEY_W;
    private static final int KEY_ESCAPE = org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE;

    /** 右上角「权重」按钮（规格 §二 方案 2）。★必须留在面板内★：门 `_b44` 会核对。 */
    private static final int WEIGHT_BTN_W = 56;
    private static final int WEIGHT_BTN_H = 14;
    private static final int WEIGHT_BTN_X = PANEL_W - 6 - WEIGHT_BTN_W;
    private static final int WEIGHT_BTN_Y = 3;

    /** 权重模式（点格子即开气泡）。 */
    private boolean weightMode = false;
    /** 当前气泡（null = 没开）。两个入口共用。 */
    private WeightPopup popup = null;
    /** 长按连发：正在按住的热区（{@link WeightPopup#ID_MINUS}/{@link WeightPopup#ID_PLUS}）。 */
    private int holdRegion = WeightPopup.ID_NONE;
    private static final long HOLD_DELAY_MS = 300L;
    private static final long HOLD_REPEAT_MS = 50L;
    private long holdNextMs = 0L;
    /** 上一帧的鼠标位置（绝对逻辑坐标）——按 W 时用它决定气泡出现在哪里。 */
    private int lastMouseX = 0;
    private int lastMouseY = 0;

    public PackageEditScreen(PackageEditHandler handler, PlayerInventory inventory, Text title) {
        super(handler, inventory, title);
        this.backgroundWidth = PANEL_W;
        this.backgroundHeight = PANEL_H;
        this.playerInventoryTitleY = PackageEditHandler.INV_Y - 12;
    }

    @Override
    protected void init() {
        super.init();
        // ★装不下时把锚点钳到 ≥0★（第 43 轮）：`HandledScreen.init()` 只做居中
        // `x = (width - backgroundWidth) / 2`（javap：字节码 0..27，连 super.init() 都不调），
        // 面板 408 宽在 320×240（guiScale 被 vanilla 限到"逻辑 ≥320×240"的最小档）/ 341×256（1024×768 gs3）
        // 下居中会得到负的 x ⇒ **最左两列格子被顶出屏幕**（看得见点不到：`isPointWithinBounds`
        // 用的是同一个 this.x，鼠标永远落不进那一列），模式 chip 也被切掉一截。
        // 钳到 0 之后改为左上对齐：模式/档位 chip + 整块 90 格网格优先完整，代价是右侧权重表被屏幕
        // 右缘裁掉一部分（权重仍可在网格上滚轮调、tooltip 里也能看到数值）。
        // ★必须改 this.x/this.y 而不是只改 left()/top()★：槽位渲染与命中用的是 HandledScreen 自己的
        // this.x/this.y，两处各钳一次就会出现"面板在 A、格子点不到"的错位。
        this.x = Math.max(0, this.x);
        this.y = Math.max(0, this.y);
        this.titleX = 8;
        this.titleY = 6;
        // 状态（mode/weights）不随槽位同步 —— 开界面主动要一份真值
        RENetworking.sendEditAction(RENetworking.ACT_REQ_STATE, null);
    }

    // ————————————————————————— 绘制 —————————————————————————

    /**
     * 面板左上角（绝对坐标）。
     *
     * <p>★直接返回 {@code this.x/this.y}，不再自己算居中★：`init()` 里已把锚点钳到 ≥0，
     * 这里若还写 `(width - backgroundWidth) / 2` 就会在窄屏上和服务端槽位坐标分叉
     * （背景按钳后的位置画、格子按居中位置渲染）。`this.x/this.y` 是 HandledScreen 渲染槽位
     * 与命中判定的同一份真值，跟着它走一定对得上。
     */
    private int left() {
        return this.x;
    }

    private int top() {
        return this.y;
    }

    @Override
    protected void drawBackground(DrawContext context, float delta, int mouseX, int mouseY) {
        int x = left();
        int y = top();

        // 面板与描边（不用 vanilla 容器贴图：宽度 300 超出 176 的图集）
        context.fill(x - 1, y - 1, x + PANEL_W + 1, y + PANEL_H + 1, 0xFF000000);
        context.fill(x, y, x + PANEL_W, y + PANEL_H, 0xFF1B1B1F);
        context.fill(x, y, x + PANEL_W, y + 1, 0xFF3C3C46);

        // 模式 chip
        for (int i = 0; i < PackageData.PackageMode.values().length; i++) {
            int cx = x + 8 + i * (MODE_CHIP_W + MODE_CHIP_GAP);
            boolean active = PackageEditClientState.mode().equals(PackageData.PackageMode.values()[i].name());
            boolean hover = inside(mouseX, mouseY, cx, y + MODE_Y, MODE_CHIP_W, MODE_CHIP_H);
            chip(context, cx, y + MODE_Y, MODE_CHIP_W, MODE_CHIP_H, active, hover, 0xFF4A7BD0);
        }

        // 档位 chip（special 档不参与换档）
        List<PackageCatalog.Tier> tiers = editableTiers();
        String currentTier = handler.currentTierId();
        for (int i = 0; i < tiers.size(); i++) {
            PackageCatalog.Tier tier = tiers.get(i);
            int cx = x + 8 + i * TIER_CHIP_W;
            boolean active = tier.id().equals(currentTier);
            boolean hover = inside(mouseX, mouseY, cx, y + TIER_Y, TIER_CHIP_W - 1, TIER_CHIP_H);
            chip(context, cx, y + TIER_Y, TIER_CHIP_W - 1, TIER_CHIP_H, active, hover, 0xFF000000 | tier.color());
        }

        // 网格底槽
        int gx = x + PackageEditHandler.GRID_X;
        int gy = y + PackageEditHandler.GRID_Y;
        for (int r = 0; r < PackageEditHandler.GRID_ROWS; r++) {
            for (int c = 0; c < PackageEditHandler.GRID_COLS; c++) {
                slotBg(context, gx + c * 18 - 1, gy + r * 18 - 1);
            }
        }

        // 权重表底
        int tx = x + TABLE_X;
        context.fill(tx - 1, gy - 1, tx + TABLE_W + 1, gy + TABLE_ROWS * TABLE_ROW_H + 1, 0xFF141418);
        List<Integer> entries = occupiedSlots();
        int scroll = tableScrollFor(entries.size());
        for (int i = 0; i < TABLE_ROWS; i++) {
            int idx = scroll + i;
            int ry = gy + i * TABLE_ROW_H;
            if (idx < entries.size()) {
                boolean hover = inside(mouseX, mouseY, tx, ry, TABLE_W, TABLE_ROW_H);
                context.fill(tx, ry, tx + TABLE_W, ry + TABLE_ROW_H - 1,
                        hover ? 0xFF33333C : 0xFF202027);
            }
        }

        // 玩家背包底槽
        int ix = x + PackageEditHandler.INV_X;
        int iy = y + PackageEditHandler.INV_Y;
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 9; c++) slotBg(context, ix + c * 18 - 1, iy + r * 18 - 1);
        }
        for (int c = 0; c < 9; c++) slotBg(context, ix + c * 18 - 1, iy + 58 - 1);
    }

    private void chip(DrawContext context, int x, int y, int w, int h, boolean active, boolean hover, int accent) {
        int fill = chipFill(active, hover, accent);
        context.fill(x, y, x + w, y + h, fill);
        context.fill(x, y, x + w, y + 1, active ? 0xFFFFFFFF : 0xFF4A4A55);
    }

    /** chip 底色 —— 绘制与文字取色共用这一份，避免"填充用 A 色、文字按 B 色算对比度"。 */
    private static int chipFill(boolean active, boolean hover, int accent) {
        return active ? (0xFF000000 | (accent & 0xFFFFFF)) : (hover ? 0xFF3A3A44 : 0xFF26262E);
    }

    /**
     * 给定底色挑可读的文字色（亮度 ITU-R BT.601）。
     *
     * <p>★硬编码文字色是这类自绘面板的通病★：第 40 轮是「标题/物品栏用 vanilla 的 0x404040 压在 0xFF1B1B1F 上」，
     * 本轮是「档位 chip 文字写死 0xFF101014，而未选中的 chip 底色是 0xFF26262E」——
     * 表现都是"字看不见"，但零报错、截图里只是少了几行字，很容易被当成没画。
     */
    private static int textOn(int argb) {
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        int lum = (299 * r + 587 * g + 114 * b) / 1000;
        return lum >= 128 ? 0xFF101014 : 0xFFEDEDF2;
    }

    /**
     * 权重数字的颜色（规格 §三）。
     *
     * <ul>
     *   <li>编辑态（气泡开着 / 权重模式）：{@code #58D6A8} 绿</li>
     *   <li>权重为 0：{@code #8A8A8A} 灰 —— 0 表示该条目在 RANDOM 里被跳过，必须一眼看出来</li>
     *   <li>普通：{@code #FFD24A} 金</li>
     * </ul>
     *
     * <p>★三个入口共用这一个函数★（网格角标 / 权重表 / 气泡），否则同一格在三处显示三种颜色。
     */
    public static int weightColor(int weight, boolean editing) {
        if (editing) return 0xFF58D6A8;
        return weight == 0 ? 0xFF8A8A8A : 0xFFD24A;
    }

    /**
     * 同色深一档（描边用）。规格给的例子是金 {@code #FFD24A} → {@code #7A5E00}，
     * 这里按 RGB ×0.45 统一推导 ⇒ (114,94,33)，与例子肉眼同级，三态都能自动获得对应深色，
     * 不用为绿/灰再各写一个魔数。
     */
    private static int darken(int argb) {
        int r = (int) (((argb >> 16) & 0xFF) * 0.45f);
        int g = (int) (((argb >> 8) & 0xFF) * 0.45f);
        int b = (int) ((argb & 0xFF) * 0.45f);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    /**
     * 画一格的权重角标（面板相对坐标 {@code (sx, sy)} = 格子左上角 +1）。
     *
     * <p>★3 位数要缩一半★：9px 字体一个字符宽 6px，{@code 999} 就是 18px，正好等于整格宽
     * ⇒ 不缩会压到右边相邻格子上（看起来像"隔壁的权重"）。上限 999 时用 0.5 缩放画成 9px 宽。
     */
    private void drawWeightBadge(DrawContext context, int sx, int sy, int weight, boolean editing) {
        String text = String.valueOf(weight);
        int color = weightColor(weight, editing);
        int outline = darken(color);
        if (text.length() >= 3) {
            context.getMatrices().push();
            context.getMatrices().translate(sx, sy, 0);
            context.getMatrices().scale(0.5f, 0.5f, 1.0f);
            context.drawText(this.textRenderer, text, 1, 1, outline, false);
            context.drawText(this.textRenderer, text, 0, 0, color, false);
            context.getMatrices().pop();
            return;
        }
        // 1px 右下描边 ⇒ 浅色物品（纸、白羊毛）上也读得清
        context.drawText(this.textRenderer, text, sx + 1, sy + 1, outline, false);
        context.drawText(this.textRenderer, text, sx, sy, color, false);
    }

    private void slotBg(DrawContext context, int x, int y) {        context.fill(x, y, x + 18, y + 18, 0xFF2A2A31);
        context.fill(x, y, x + 18, y + 1, 0xFF14141A);
    }

    @Override
    protected void drawForeground(DrawContext context, int mouseX, int mouseY) {
        // ★不调 super.drawForeground★：javap 实测它整个方法体只做两件事 —— 用 0x404040（vanilla
        // 浅灰底用的深灰）画 `this.title` 与 `this.playerInventoryTitle`。本面板底色是 0xFF1B1B1F，
        // 深灰字（64,64,64 on 27,27,31）几乎看不见 ⇒ 标题和「物品栏」都像丢了。
        // 这里按面板配色自己画，位置仍沿用 titleX/titleY 与 playerInventoryTitleX/Y（= 8 / INV_Y-12），
        // 与 HandledScreen 完全同口径，接入原版坐标体系不变。
        // ★这里必须是「面板相对坐标」，不能加 left()/top()★（本轮 javap 实证）：
        // HandledScreen.render 在调 drawForeground 之前已经 `matrices.translate(x, y)`（字节码
        // 33..51 push+translate，drawForeground 在 179 调用，pop 在 467）⇒ 方法内部 (0,0) 就是面板左上角。
        // 同时传进来的 mouseX/mouseY 是**绝对坐标**（字节码 177/178 直接 iload_2/iload_3）⇒
        // 如果哪天要在这里做悬停判定，必须先 `mouseX - left()`，不能直接比面板相对坐标（门 B42 的 F2）。
        // 旧实现错在绘制：`int x = left(); int y = top();` 让 chip 文案、权重表图标/数字/名字、总权重
        // 全部整体右移 left()、下移 top()（9~36px / 0~15px，随分辨率变化）—— 底槽由 drawBackground 按
        // 绝对坐标画、文字整体偏移 ⇒ 字与自己的格子错开一段，看起来像"标签串行/漏画"。
        // 零报错、零日志、静态门原先也查不出（口径全在渲染期）。
        final int x = 0;
        final int y = 0;
        context.drawText(this.textRenderer, this.title, this.titleX, this.titleY, 0xFFEDEDF2, false);
        context.drawText(this.textRenderer, this.playerInventoryTitle,
                this.playerInventoryTitleX, this.playerInventoryTitleY, 0xFF8E8E9A, false);

        // ★T80：每格权重数字画在物品**左上角**（规格 §三）★
        // 只在该格有物品时画；颜色三态见 weightColor()。位置 = 格子坐标 +(1,1)，
        // 即 `slot.x + 1, slot.y + 1`（与槽位同一份几何，不另算）。
        // drawForeground 里已经是面板相对坐标且**在物品之后**执行（第 42 轮 javap 实证：
        // HandledScreen.render 的 translate 在 40，drawForeground 在 179）⇒ 数字压在图标上，正是规格要的角标。
        for (int i = 0; i < PackageEditHandler.EDITOR_SLOTS; i++) {
            ItemStack stack = this.handler.getSlot(i).getStack();
            if (stack.isEmpty()) continue;
            int sx = PackageEditHandler.GRID_X + (i % PackageEditHandler.GRID_COLS) * 18 + 1;
            int sy = PackageEditHandler.GRID_Y + (i / PackageEditHandler.GRID_COLS) * 18 + 1;
            drawWeightBadge(context, sx, sy, PackageEditClientState.weight(i), weightMode);
        }

        // 模式 chip 文案
        PackageData.PackageMode[] modes = PackageData.PackageMode.values();
        for (int i = 0; i < modes.length; i++) {
            int cx = x + 8 + i * (MODE_CHIP_W + MODE_CHIP_GAP);
            Text label = Text.translatable("screen.redtale_economy.edit.mode."
                    + modes[i].name().toLowerCase());
            boolean active = PackageEditClientState.mode().equals(modes[i].name());
            context.drawText(this.textRenderer, label, cx + (MODE_CHIP_W - this.textRenderer.getWidth(label)) / 2,
                    y + MODE_Y + 2, textOn(chipFill(active, false, 0xFF4A7BD0)), false);
        }

        // 档位 chip 文案
        List<PackageCatalog.Tier> tiers = editableTiers();
        String currentTier = handler.currentTierId();
        for (int i = 0; i < tiers.size(); i++) {
            PackageCatalog.Tier tier = tiers.get(i);
            int cx = x + 8 + i * TIER_CHIP_W;
            String name = tier.nameZh();
            // ★文字色必须跟着 chip 底色走★：旧实现写死 0xFF101014（近黑），而**未选中**的 chip 底色是
            // 0xFF26262E ⇒ 六个档位里只有当前档那一个字看得见，其余五个是黑字压黑底（肉眼=标签缺失）。
            // 与第 40 轮修过的「标题深灰压在深底」同一类问题，只是这次在 chip 上。
            boolean active = tier.id().equals(currentTier);
            int chipColor = 0xFF000000 | tier.color();
            context.drawText(this.textRenderer, name,
                    cx + (TIER_CHIP_W - 1 - this.textRenderer.getWidth(name)) / 2,
                    y + TIER_Y + 2, textOn(chipFill(active, false, chipColor)), false);
        }

        // 权重表内容
        int tx = x + TABLE_X;
        int gy = y + PackageEditHandler.GRID_Y;
        List<Integer> entries = occupiedSlots();
        int scroll = tableScrollFor(entries.size());
        for (int i = 0; i < TABLE_ROWS; i++) {
            int entryIndex = scroll + i;
            int ry = gy + i * TABLE_ROW_H;
            if (entryIndex >= entries.size()) break;
            int slot = entries.get(entryIndex);
            ItemStack stack = this.handler.getSlot(slot).getStack();
            if (stack.isEmpty()) continue;

            // 图标（不缩放，16×16 正好塞进 18 行高）
            context.drawItem(stack, tx + 1, ry + 1);

            int weight = PackageEditClientState.weight(slot);
            context.drawText(this.textRenderer, "-", tx + BTN_MINUS_X + 3, ry + 5, 0xFFB9B9C4, false);
            String w = String.valueOf(weight);
            context.drawText(this.textRenderer, w, tx + WEIGHT_X + 17 - this.textRenderer.getWidth(w),
                    ry + 5, weight == 0 ? 0xFFE06C6C : 0xFFEDEDF2, false);
            context.drawText(this.textRenderer, "+", tx + BTN_PLUS_X + 3, ry + 5, 0xFFB9B9C4, false);

            String name = stack.getName().getString();
            name = trim(name, TABLE_NAME_W);
            context.drawText(this.textRenderer, name, tx + NAME_X, ry + 5, 0xFFA9A9B4, false);
        }

        // 汇总：总权重（RANDOM 模式下就是抽取概率的分母）——放标题右侧，不占背包区。
        // ★T80：权重模式下改显示提示条★（规格 §二 方案 2「提示条显示：点击物品调整权重」）。
        // 两者同一位置二选一，不新增行高（面板高度已在 408×240 顶满，任何新行都会顶掉背包）。
        String right;
        int rightColor;
        if (weightMode) {
            right = Text.translatable("screen.redtale_economy.edit.pickHint").getString();
            rightColor = 0xFF58D6A8;
        } else {
            int total = 0;
            for (int slot : entries) total += Math.max(0, PackageEditClientState.weight(slot));
            right = Text.translatable("screen.redtale_economy.edit.total", total,
                    entries.size()).getString();
            rightColor = 0xFF8E8E9A;
        }
        context.drawText(this.textRenderer, right,
                x + weightBtnX() - 8 - this.textRenderer.getWidth(right), y + 6, rightColor, false);

        // 右上角「权重」切换按钮（规格 §二 方案 2）。★不画在面板外★：x/y 都在 [0, PANEL] 内。
        int relBtnX = weightBtnX();
        int bx = x + relBtnX;
        int by = y + WEIGHT_BTN_Y;
        boolean hoverBtn = inside(mouseX - left(), mouseY - top(), relBtnX, WEIGHT_BTN_Y,
                WEIGHT_BTN_W, WEIGHT_BTN_H);
        // ★mouseX/mouseY 是绝对坐标，必须先减 left()/top()★（第 42 轮 javap：字节码 177/178 直接传原始值）
        int btnFill = weightMode ? 0xFF58D6A8 : (hoverBtn ? 0xFF3A3A44 : 0xFF26262E);
        context.fill(bx, by, bx + WEIGHT_BTN_W, by + WEIGHT_BTN_H, btnFill);
        context.fill(bx, by, bx + WEIGHT_BTN_W, by + 1, weightMode ? 0xFFFFFFFF : 0xFF4A4A55);
        Text btnLabel = Text.translatable("screen.redtale_economy.edit.weightBtn");
        context.drawText(this.textRenderer, btnLabel,
                bx + (WEIGHT_BTN_W - this.textRenderer.getWidth(btnLabel)) / 2, by + 3,
                textOn(btnFill), false);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        lastMouseX = mouseX;
        lastMouseY = mouseY;
        updateHover(mouseX, mouseY);
        // 气泡的目标格被清空（拖回背包 / 右键删 / 换档重算）⇒ 气泡必须自己关掉，
        // 否则会留一个"权重 1、没有物品"的孤儿气泡，点 ± 还在往服务端发 SET_WEIGHT。
        if (popup != null && this.handler.getSlot(popup.slot()).getStack().isEmpty()) {
            popup = null;
            clearHold();
        }
        // 长按连发（规格 §六 第 3 条）：0.3s 后每 50ms ±1。
        // ★不能用 tick() 当节拍器★：`HandledScreen.tick()` 是 **final**（javac 实测「无法覆盖」），
        // 而 render() 每帧都被调、且这些操作本来就只影响本客户端 UI（发包方式和 mouseClicked 一致）。
        // ★鼠标离开按钮就停★：只在 mouseReleased 清按下状态不够 —— 按住 − 后把鼠标甩出窗口再松手，
        // 释放事件可能丢失，连发会一路把权重降到 0（零报错、玩家以为"卡住了"）。
        if (holdRegion != WeightPopup.ID_NONE && popup != null
                && popup.hit(lastMouseX, lastMouseY) != holdRegion) {
            clearHold();
        }
        if (holdRegion != WeightPopup.ID_NONE && popup != null) {
            long now = System.currentTimeMillis();
            if (now >= holdNextMs) {
                stepWeight(popup.slot(), holdRegion == WeightPopup.ID_PLUS ? 1 : -1);
                holdNextMs = now + HOLD_REPEAT_MS;
            }
        }
        this.renderBackground(context);
        super.render(context, mouseX, mouseY, delta);

        // 悬停提示：网格格子上显示「物品 / 权重 / 滚轮调整」，表格行显示完整名
        List<Text> tip = new ArrayList<>();
        if (hoverGridSlot >= 0) {
            ItemStack stack = this.handler.getSlot(hoverGridSlot).getStack();
            if (!stack.isEmpty()) {
                tip.add(stack.getName());
                tip.add(Text.translatable("screen.redtale_economy.edit.weight",
                        PackageEditClientState.weight(hoverGridSlot)).formatted(Formatting.GRAY));
                tip.add(Text.translatable("screen.redtale_economy.edit.hint").formatted(Formatting.DARK_GRAY));
            }
        } else if (hoverTableRow >= 0) {
            // ★表格行补完整名★：名字列只有 48px（约 5 个汉字），`trim()` 会截成「附魔金苹果…」
            // 这类无法区分的串；`hoverTableRow` 之前**只写不读**（死字段）⇒ 类注释承诺的
            // 「表格行显示完整名」从未生效。只在真被截断时提示，避免每行都弹 tooltip 变噪音。
            int entrySlot = tableEntrySlot(hoverTableRow);
            if (entrySlot >= 0) {
                ItemStack stack = this.handler.getSlot(entrySlot).getStack();
                if (!stack.isEmpty() && this.textRenderer.getWidth(stack.getName()) > TABLE_NAME_W) {
                    tip.add(stack.getName());
                }
            }
        }
        if (!tip.isEmpty()) {
            context.drawTooltip(this.textRenderer, tip, mouseX, mouseY);
        }
        this.drawMouseoverTooltip(context, mouseX, mouseY);

        // ★气泡最后画★（规格 §六 第 1 条）：槽位 tooltip 由 drawMouseoverTooltip 画，
        // 气泡若先画会被"物品名/权重/滚轮调整"那三行压住（并且 tooltip 底是半透明黑，叠加后不可读）。
        if (popup != null) {
            ItemStack stack = this.handler.getSlot(popup.slot()).getStack();
            if (!stack.isEmpty()) {
                popup.draw(context, this.textRenderer, stack,
                        PackageEditClientState.weight(popup.slot()), mouseX, mouseY);
            }
        }
    }

    // ————————————————————————— 命中 —————————————————————————

    private static boolean inside(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    private void updateHover(double mouseX, double mouseY) {
        hoverGridSlot = gridSlotAt(mouseX, mouseY);
        hoverTableRow = tableRowAt(mouseX, mouseY);
    }

    /**
     * 鼠标 → 网格 slot index。★按 handler 常量算，不重复写坐标★：与服务端槽位同一份几何，
     * 布局改动不会出现命中错位。
     */
    private int gridSlotAt(double mouseX, double mouseY) {
        int gx = left() + PackageEditHandler.GRID_X;
        int gy = top() + PackageEditHandler.GRID_Y;
        int w = PackageEditHandler.GRID_COLS * 18;
        int h = PackageEditHandler.GRID_ROWS * 18;
        if (!inside(mouseX, mouseY, gx, gy, w, h)) return -1;
        int col = (int) ((mouseX - gx) / 18);
        int row = (int) ((mouseY - gy) / 18);
        int idx = row * PackageEditHandler.GRID_COLS + col;
        return idx >= 0 && idx < PackageEditHandler.EDITOR_SLOTS ? idx : -1;
    }

    /** 鼠标 → 权重表第几行（0..TABLE_ROWS-1），不在表格上返回 -1。 */
    private int tableRowAt(double mouseX, double mouseY) {
        int tx = left() + TABLE_X;
        int gy = top() + PackageEditHandler.GRID_Y;
        if (!inside(mouseX, mouseY, tx, gy, TABLE_W, TABLE_ROWS * TABLE_ROW_H)) return -1;
        return (int) ((mouseY - gy) / TABLE_ROW_H);
    }

    /**
     * 表格第 {@code row} 行对应哪个编辑格（-1 = 该行没有条目）。
     *
     * <p>绘制、命中、tooltip 三处都走这一个换算，避免"滚动了但点击还按未滚动的下标"。
     */
    private int tableEntrySlot(int row) {
        if (row < 0 || row >= TABLE_ROWS) return -1;
        List<Integer> entries = occupiedSlots();
        int idx = tableScrollFor(entries.size()) + row;
        return idx >= 0 && idx < entries.size() ? entries.get(idx) : -1;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int x = left();
        int y = top();

        // ——— T80 气泡优先：它画在最上层，命中也必须在最上层 ———
        if (popup != null) {
            if (button == 0) {
                int region = popup.hit(mouseX, mouseY);
                if (region == WeightPopup.ID_MINUS || region == WeightPopup.ID_PLUS) {
                    boolean plus = region == WeightPopup.ID_PLUS;
                    stepWeight(popup.slot(), plus ? 1 : -1);
                    // 长按连发：按下时启动计时，tick() 里每 50ms 再来一次（规格 §六 第 3 条）
                    holdRegion = region;
                    holdNextMs = System.currentTimeMillis() + HOLD_DELAY_MS;
                    return true;
                }
                if (region == WeightPopup.ID_CONFIRM) {
                    // 「确认」= 把当前值再发一次（幂等）+ 关闭。规格 §四 是「改即发」，确认只是收尾动作，
                    // 但它必须是**能用的按钮**：旧式"只关不加"的假按钮点了没反应更让人困惑。
                    sendWeight(popup.slot(), PackageEditClientState.weight(popup.slot()));
                    closePopup();
                    return true;
                }
                if (popup.contains(mouseX, mouseY)) return true; // 点在气泡空白处：吃掉，别穿透去搬物品
            } else if (popup.contains(mouseX, mouseY)) {
                return true;
            }
            // 点在气泡外：先关，然后让这一次点击照常生效（不做"第一下只关气泡"的拦截）
            closePopup();
        }

        // ——— T80 入口②：右上角「权重」按钮切模式 ———
        if (button == 0 && inside(mouseX, mouseY, x + weightBtnX(), y + WEIGHT_BTN_Y,
                WEIGHT_BTN_W, WEIGHT_BTN_H)) {
            weightMode = !weightMode;
            closePopup();
            return true;
        }

        // ——— T80 入口②：权重模式下点格子 → 在格子旁弹气泡，且**不搬动物品** ———
        // 只在格里真有物品时接管；空格仍走原版（否则权重模式下没法把物品拖进网格）。
        if (weightMode && button == 0) {
            int slot = gridSlotAt(mouseX, mouseY);
            if (slot >= 0 && !this.handler.getSlot(slot).getStack().isEmpty()) {
                openPopupAtSlot(slot);
                return true;
            }
        }

        // 模式 chip（只吃左键）
        if (button == 0) {
            PackageData.PackageMode[] modes = PackageData.PackageMode.values();
            for (int i = 0; i < modes.length; i++) {
                int cx = x + 8 + i * (MODE_CHIP_W + MODE_CHIP_GAP);
                if (inside(mouseX, mouseY, cx, y + MODE_Y, MODE_CHIP_W, MODE_CHIP_H)) {
                    final int ordinal = i;
                    RENetworking.sendEditAction(RENetworking.ACT_SET_MODE, buf -> buf.writeVarInt(ordinal));
                    return true;
                }
            }
            // 档位 chip
            List<PackageCatalog.Tier> tiers = editableTiers();
            for (int i = 0; i < tiers.size(); i++) {
                int cx = x + 8 + i * TIER_CHIP_W;
                if (inside(mouseX, mouseY, cx, y + TIER_Y, TIER_CHIP_W - 1, TIER_CHIP_H)) {
                    final String tierId = tiers.get(i).id();
                    RENetworking.sendEditAction(RENetworking.ACT_SET_TIER, buf -> buf.writeString(tierId));
                    return true;
                }
            }
        }

        // 权重表
        int row = tableRowAt(mouseX, mouseY);
        if (row >= 0) {
            int slot = tableEntrySlot(row);
            if (slot >= 0) {
                double relX = mouseX - (x + TABLE_X);
                int weight = PackageEditClientState.weight(slot);
                if (button == 0 && relX >= BTN_MINUS_X && relX < BTN_MINUS_X + BTN_W) {
                    RENetworking.sendEditAction(RENetworking.ACT_SET_WEIGHT,
                            buf -> { buf.writeVarInt(slot); buf.writeVarInt(Math.max(0, weight - 1)); });
                    return true;
                }
                if (button == 0 && relX >= BTN_PLUS_X && relX < BTN_PLUS_X + BTN_W) {
                    RENetworking.sendEditAction(RENetworking.ACT_SET_WEIGHT,
                            buf -> { buf.writeVarInt(slot); buf.writeVarInt(Math.min(
                                    PackageEditClientState.maxWeight(), weight + 1)); });
                    return true;
                }
                if (button == 1) {
                    // 右键删条目（拖回背包也可以，但右键更快）
                    RENetworking.sendEditAction(RENetworking.ACT_CLEAR_SLOT, buf -> buf.writeVarInt(slot));
                    return true;
                }
            }
            // 点在表格空白处：交给 super，别把点击吞掉
        }

        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        // 表格上滚动 = 翻页
        if (tableRowAt(mouseX, mouseY) >= 0) {
            int occupied = occupiedSlots().size();
            int maxScroll = Math.max(0, occupied - TABLE_ROWS);
            tableScroll = Math.max(0, Math.min(maxScroll,
                    tableScrollFor(occupied) - (int) Math.signum(amount)));
            return true;
        }
        // 网格上滚动 = 调权重（★只对已有物品生效★：空格子滚轮会变成"给未来物品预设权重"，很反直觉）
        int slot = gridSlotAt(mouseX, mouseY);
        if (slot >= 0 && !this.handler.getSlot(slot).getStack().isEmpty()) {
            int weight = PackageEditClientState.weight(slot);
            int next = Math.max(0, Math.min(PackageEditClientState.maxWeight(),
                    weight + (int) Math.signum(amount)));
            if (next != weight) {
                RENetworking.sendEditAction(RENetworking.ACT_SET_WEIGHT,
                        buf -> { buf.writeVarInt(slot); buf.writeVarInt(next); });
            }
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, amount);
    }

    // ————————————————————————— T80 键盘 / 气泡 —————————————————————————

    /**
     * 入口①：悬停某格按 {@code W} → 在鼠标处弹气泡（规格 §二 方案 1）。
     *
     * <p>"键盘被聊天框抢走"的顾虑在 1.20.1 不成立：本界面没有 {@code TextFieldWidget}，
     * 聊天框在打开容器界面时不可聚焦（{@code Screen.keyPressed} 只可能来自本界面）。
     * 唯一要守的是：**没有悬停到有物品的格子就不要吞键**，否则 W 在其它位置失效。
     */
    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == KEY_ESCAPE && popup != null) {
            closePopup();
            return true;
        }
        if (keyCode == WEIGHT_KEY) {
            if (popup != null) { // 再按一次 = 关闭
                closePopup();
                return true;
            }
            int slot = hoverGridSlot;
            if (slot >= 0 && !this.handler.getSlot(slot).getStack().isEmpty()) {
                // 鼠标旁 10px：不压住正在悬停的格子（压住会让 tooltip 与点选都看不见）
                popup = WeightPopup.at(slot, lastMouseX + 10, lastMouseY + 10, this.width, this.height);
                return true;
            }
            return false;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        clearHold();
        return super.mouseReleased(mouseX, mouseY, button);
    }

    /**
     * 权重按钮的面板相对 X。
     *
     * <p>★窄屏要跟着可见右缘走★：面板 408 宽在 320/341 逻辑宽下会被屏幕右缘裁掉（`init()` 把锚点钳到 0
     * ⇒ 面板右侧 88px 在屏幕外）。按钮若死守 {@link #WEIGHT_BTN_X}=346，在 320 宽下就整块不可见 ——
     * 而它正是「权重模式」唯一入口，等于窄屏下只有按 W 这条路。这里把它贴到**当前可见**右缘，
     * 与第 43 轮的锚点钳位同一思路；绘制与命中都读这一个函数。
     */
    private int weightBtnX() {
        int visibleRight = this.width - left() - 2 - WEIGHT_BTN_W;
        return Math.max(0, Math.min(WEIGHT_BTN_X, visibleRight));
    }

    private void openPopupAtSlot(int slot) {
        int col = slot % PackageEditHandler.GRID_COLS;
        int row = slot / PackageEditHandler.GRID_COLS;
        // 固定贴在格子右侧；WeightPopup.at 会把它钳回屏幕内（贴右边时自动左移）
        popup = WeightPopup.at(slot, left() + PackageEditHandler.GRID_X + col * 18 + 20,
                top() + PackageEditHandler.GRID_Y + row * 18, this.width, this.height);
    }

    private void closePopup() {
        popup = null;
        clearHold();
    }

    private void clearHold() {
        holdRegion = WeightPopup.ID_NONE;
        holdNextMs = 0L;
    }

    /** 发一条 SET_WEIGHT（钳到 [0, maxWeight]）。值没变就不发，避免长按到边界后每 50ms 刷包。 */
    private void stepWeight(int slot, int delta) {
        int current = PackageEditClientState.weight(slot);
        sendWeight(slot, current + delta);
    }

    private void sendWeight(int slot, int value) {
        int clamped = Math.max(0, Math.min(PackageEditClientState.maxWeight(), value));
        if (clamped == PackageEditClientState.weight(slot)) return;
        RENetworking.sendEditAction(RENetworking.ACT_SET_WEIGHT,
                buf -> { buf.writeVarInt(slot); buf.writeVarInt(clamped); });
    }

    // ————————————————————————— 小工具 —————————————————————————

    /**
     * 表格滚动偏移（已按当前条目数钳位）。
     *
     * <p>★必须钳位★：滚动位置只在滚轮事件里更新，而条目数会因为「拖回背包 / 右键删 / （换档后重算）」
     * 变少 ⇒ `tableScroll` 可能大于 maxScroll，此时 {@code drawForeground} 里
     * {@code entryIndex >= entries.size()} 全部 break ⇒ 表格**整块空白**（物品还在，但一行不显示，
     * 鼠标放上去也没有可点行），且零报错、零日志。每次使用前收敛到合法区间的做法把这条堵死。
     */
    private int tableScrollFor(int entries) {
        int max = Math.max(0, entries - TABLE_ROWS);
        if (tableScroll > max) tableScroll = max;
        else if (tableScroll < 0) tableScroll = 0;
        return tableScroll;
    }

    /** 参与换档的档位（跳过 special：surprise 是全局单件，不随系列展开）。 */
    private static List<PackageCatalog.Tier> editableTiers() {
        List<PackageCatalog.Tier> out = new ArrayList<>();
        for (PackageCatalog.Tier t : PackageCatalog.tiers()) {
            if (!t.special()) out.add(t);
        }
        return out;
    }

    /** 已占用格子（按 slot 升序），表格与汇总都用这一份口径。 */
    private List<Integer> occupiedSlots() {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < PackageEditHandler.EDITOR_SLOTS; i++) {
            if (!this.handler.getSlot(i).getStack().isEmpty()) out.add(i);
        }
        return out;
    }

    private String trim(String s, int maxWidth) {
        if (this.textRenderer.getWidth(s) <= maxWidth) return s;
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (this.textRenderer.getWidth(sb.toString() + c + "…") > maxWidth) break;
            sb.append(c);
        }
        return sb + "…";
    }
}
