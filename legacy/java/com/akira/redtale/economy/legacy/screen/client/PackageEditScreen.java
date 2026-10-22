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

    public PackageEditScreen(PackageEditHandler handler, PlayerInventory inventory, Text title) {
        super(handler, inventory, title);
        this.backgroundWidth = PANEL_W;
        this.backgroundHeight = PANEL_H;
        this.playerInventoryTitleY = PackageEditHandler.INV_Y - 12;
    }

    @Override
    protected void init() {
        super.init();
        this.titleX = 8;
        this.titleY = 6;
        // 状态（mode/weights）不随槽位同步 —— 开界面主动要一份真值
        RENetworking.sendEditAction(RENetworking.ACT_REQ_STATE, null);
    }

    // ————————————————————————— 绘制 —————————————————————————

    private int left() {
        return (this.width - this.backgroundWidth) / 2;
    }

    private int top() {
        return (this.height - this.backgroundHeight) / 2;
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

    private void slotBg(DrawContext context, int x, int y) {
        context.fill(x, y, x + 18, y + 18, 0xFF2A2A31);
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

        // 汇总：总权重（RANDOM 模式下就是抽取概率的分母）——放标题右侧，不占背包区
        int total = 0;
        for (int slot : entries) total += Math.max(0, PackageEditClientState.weight(slot));
        String summary = Text.translatable("screen.redtale_economy.edit.total", total,
                entries.size()).getString();
        context.drawText(this.textRenderer, summary,
                x + PANEL_W - 8 - this.textRenderer.getWidth(summary), y + 6, 0xFF8E8E9A, false);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        updateHover(mouseX, mouseY);
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
