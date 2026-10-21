package com.akira.redtale.economy.legacy.entity.client;

import com.akira.redtale.economy.legacy.entity.PackageEntity;
import com.akira.redtale.economy.legacy.model.PackageModel;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.util.math.MatrixStack;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

public class PackageRenderer extends GeoEntityRenderer<PackageEntity> {

    public PackageRenderer(EntityRendererFactory.Context context) {
        super(context, new PackageModel());
    }

    @Override
    public void render(PackageEntity entity, float entityYaw, float partialTick,
                       MatrixStack poseStack, VertexConsumerProvider bufferSource,
                       int packedLight) {
        poseStack.push();
        float scale = 0.6f;
        poseStack.scale(scale, scale, scale);
        super.render(entity, entityYaw, partialTick, poseStack, bufferSource, packedLight);
        poseStack.pop();
    }
}