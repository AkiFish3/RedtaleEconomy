package com.akira.redtale.economy.legacy.screen.client;

import com.akira.redtale.economy.legacy.init.RENetworking;
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
        this.backgroundHeight = 17 + handler.rows * 18 + 14 + 96;
        this.playerInventoryTitleY = 17 + handler.rows * 18 + 3;
    }

    @Override
    protected void drawBackground(DrawContext context, float delta, int mouseX, int mouseY) {
        int x = (this.width - this.backgroundWidth) / 2;
        int y = (this.height - this.backgroundHeight) / 2;

        context.drawTexture(TEXTURE, x, y, 0, 0, 176, 17);
        int contentY = y + 16;
        int contentHeight = handler.rows * 18;
        for (int i = 0; i < handler.rows; i++) {
            context.drawTexture(TEXTURE, x, contentY + i * 18, 0, 17, 176, 18);
        }
        int playerInvY = contentY + contentHeight;
        context.drawTexture(TEXTURE, x, playerInvY, 0, 125, 176, 96);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        this.renderBackground(context);
        super.render(context, mouseX, mouseY, delta);
        drawMouseoverTooltip(context, mouseX, mouseY);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
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