package com.akira.redtale.economy.legacy.screen.client;

import com.akira.redtale.economy.legacy.entity.PackageEntity;
import com.akira.redtale.economy.legacy.init.RENetworking;
import com.akira.redtale.economy.legacy.particles.PackageParticles;
import com.akira.redtale.economy.legacy.screen.PackageChooseHandler;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

public class PackageChooseScreen extends HandledScreen<PackageChooseHandler> {
    private static final Identifier TEXTURE = new Identifier("minecraft", "textures/gui/container/generic_54.png");

    public PackageChooseScreen(PackageChooseHandler handler, PlayerInventory inventory, Text title) {
        super(handler, inventory, title);
        // 与原版 ChestScreen 完全同口径（114 + rows*18）：旧值 127+rows*18 比实际绘制内容高 14px，
        // 面板因此被顶高 7px、底部留一条空白 —— 格子贴图与物品图标也整体错 1px。
        this.backgroundHeight = 114 + handler.rows * 18;
        this.playerInventoryTitleY = 17 + handler.rows * 18 + 3;
    }

    @Override
    protected void drawBackground(DrawContext context, float delta, int mouseX, int mouseY) {
        int x = (this.width - this.backgroundWidth) / 2;
        int y = (this.height - this.backgroundHeight) / 2;

        // 槽位坐标来自 handler（x=8+col*18, y=17+row*18）⇒ 背景必须从 y+17 开始画，
        // 旧值 y+16 让每一行都压到上一行 1px，物品图标看起来偏下。
        context.drawTexture(TEXTURE, x, y, 0, 0, 176, 17);
        int contentY = y + 17;
        int contentHeight = handler.rows * 18;
        for (int i = 0; i < handler.rows; i++) {
            context.drawTexture(TEXTURE, x, contentY + i * 18, 0, 17, 176, 18);
        }
        int playerInvY = contentY + contentHeight;
        context.drawTexture(TEXTURE, x, playerInvY, 0, 126, 176, 96);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        updateHoverParticles(mouseX, mouseY);
        this.renderBackground(context);
        super.render(context, mouseX, mouseY, delta);
        drawMouseoverTooltip(context, mouseX, mouseY);
    }

    /** 上一次悬停的格子（-1 = 不在任何格子上）。 */
    private int lastHoverIndex = -1;

    /**
     * 滚动切换（规格 §十「每次换候选 8 颗」）。
     *
     * <p>★当前界面是网格点选，不是滚动轮盘★，所以「换候选」的语义落到「鼠标移到另一格」上：
     * 换一格放一组粒子，手感与规格描述的滚动一致，且完全在客户端侧完成
     * （不新增 C2S 包 —— 每次移动鼠标都发包会被服务端的校验/日志淹掉）。
     *
     * <p>只在 index 真变化时发射：每帧都放会变成一条粒子尾巴。
     */
    private void updateHoverParticles(int mouseX, int mouseY) {
        int index = hoveredIndex(mouseX, mouseY);
        if (index == lastHoverIndex) return;
        lastHoverIndex = index;
        if (index < 0 || this.client == null || this.client.world == null) return;
        if (!(this.client.world.getEntityById(handler.getEntityId()) instanceof PackageEntity pkg)) return;
        PackageParticles.scroll(this.client.world, pkg.getPos(), pkg.getPackageTier());
    }

    /** 鼠标位置 → 候选格 index；不在格子区域或超出实际物品数返回 -1。 */
    private int hoveredIndex(double mouseX, double mouseY) {
        int slotsStartX = (this.width - this.backgroundWidth) / 2 + 8;
        int slotsStartY = (this.height - this.backgroundHeight) / 2 + 17;
        int slotsEndY = slotsStartY + handler.rows * 18;
        if (mouseX < slotsStartX || mouseX >= slotsStartX + 162 || mouseY < slotsStartY || mouseY >= slotsEndY) {
            return -1;
        }
        int slotRow = (int) ((mouseY - slotsStartY) / 18);
        int slotCol = (int) ((mouseX - slotsStartX) / 18);
        int slotIndex = slotRow * 9 + slotCol;
        return slotIndex < handler.getActualItemCount() ? slotIndex : -1;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // ★只吃左键★：旧实现不判 button ⇒ 右键/中键点格子也会发 select_item 并消耗包裹，
        // 玩家习惯性右键查看物品时直接白丢一个礼包（客户端看不出任何异常）。
        if (button != 0) {
            return super.mouseClicked(mouseX, mouseY, button);
        }
        int slotsStartX = (this.width - this.backgroundWidth) / 2 + 8;
        int slotsStartY = (this.height - this.backgroundHeight) / 2 + 17;
        int slotsEndY = slotsStartY + handler.rows * 18;

        if (mouseX >= slotsStartX && mouseX < slotsStartX + 162 && mouseY >= slotsStartY && mouseY < slotsEndY) {
            int slotRow = (int) ((mouseY - slotsStartY) / 18);
            int slotCol = (int) ((mouseX - slotsStartX) / 18);
            int slotIndex = slotRow * 9 + slotCol;

            if (slotIndex < handler.getActualItemCount()) {
                PacketByteBuf buf = PacketByteBufs.create();
                buf.writeInt(slotIndex);
                buf.writeInt(handler.getEntityId());
                ClientPlayNetworking.send(RENetworking.SELECT_ITEM, buf);
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public void removed() {
        if (handler.getEntityId() != -1) {
            RENetworking.sendCloseChoosePackagePacket(handler.getEntityId());
        }
        super.removed();
    }
}