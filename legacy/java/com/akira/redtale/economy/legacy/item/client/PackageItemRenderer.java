package com.akira.redtale.economy.legacy.item.client;

import com.akira.redtale.economy.legacy.item.PackageItem;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.model.json.ModelTransformationMode;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.ItemStack;
import software.bernie.geckolib.renderer.GeoItemRenderer;

public class PackageItemRenderer extends GeoItemRenderer<PackageItem> {
    public PackageItemRenderer() {
        super(new PackageItemModel());
    }

    @Override
    public void render(ItemStack itemStack, ModelTransformationMode mode, MatrixStack poseStack,
                       VertexConsumerProvider bufferSource, int packedLight, int packedOverlay) {
        super.render(itemStack, mode, poseStack, bufferSource, packedLight, packedOverlay);
    }
}