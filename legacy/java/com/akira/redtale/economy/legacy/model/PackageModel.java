package com.akira.redtale.economy.legacy.model;

import com.akira.redtale.economy.legacy.entity.PackageEntity;
import net.minecraft.util.Identifier;
import software.bernie.geckolib.model.GeoModel;

public class PackageModel extends GeoModel<PackageEntity> {

    @Override
    public Identifier getModelResource(PackageEntity animatable) {
        return new Identifier("redtale_economy", "geo/package.geo.json");
    }

    @Override
    public Identifier getTextureResource(PackageEntity animatable) {
        return new Identifier("redtale_economy", "textures/entity/normal_package.png");
    }

    @Override
    public Identifier getAnimationResource(PackageEntity animatable) {
        return new Identifier("redtale_economy", "animations/package.animation.json");
    }
}