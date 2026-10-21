package com.akira.redtale.economy.legacy.screen.client;

import com.akira.redtale.economy.legacy.screen.PackageEditHandler;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

public class PackageEditScreen extends HandledScreen<PackageEditHandler> {
    private static final Identifier TEXTURE = new Identifier("minecraft", "textures/gui/container/generic_54.png");

    public PackageEditScreen(PackageEditHandler handler, PlayerInventory inventory, Text title) {
        super(handler, inventory, title);
        this.backgroundHeight = 301;
        this.playerInventoryTitleY = 17 + 180 + 4; // 玩家背包标题Y
    }

    @Override
    protected void init() {
        super.init();
        this.titleX = (this.backgroundWidth - this.textRenderer.getWidth(this.title)) / 2;
        this.titleY = 6;
    }

    @Override
    protected void drawBackground(DrawContext context, float delta, int mouseX, int mouseY) {
        int x = (this.width - this.backgroundWidth) / 2;
        int y = (this.height - this.backgroundHeight) / 2;

        // 1. 绘制顶部（17px）
        context.drawTexture(TEXTURE, x, y, 0, 0, 176, 17);

        // 2. 绘制中间内容区（10行 * 18px = 180px）
        int contentY = y + 17;
        for (int i = 0; i < 10; i++) {
            int drawY = contentY + i * 18;
            context.drawTexture(TEXTURE, x, drawY, 0, 17, 176, 18);
        }

        // 3. 绘制底部+玩家背包（96px）
        int playerInvY = contentY + 180;
        context.drawTexture(TEXTURE, x, playerInvY, 0, 125, 176, 96);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        this.renderBackground(context);
        super.render(context, mouseX, mouseY, delta);
        this.drawMouseoverTooltip(context, mouseX, mouseY);
    }

    @Override
    protected void drawForeground(DrawContext context, int mouseX, int mouseY) {
        super.drawForeground(context, mouseX, mouseY);
    }
}