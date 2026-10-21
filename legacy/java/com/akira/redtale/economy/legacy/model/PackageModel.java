package com.akira.redtale.economy.legacy.model;

import com.akira.redtale.economy.legacy.entity.PackageEntity;
import net.minecraft.util.Identifier;
import software.bernie.geckolib.model.GeoModel;

public class PackageModel extends GeoModel<PackageEntity> {

    private static final Identifier GEO = new Identifier("redtale_economy", "geo/package.geo.json");
    private static final Identifier ANIM = new Identifier("redtale_economy", "animations/package.animation.json");
    private static final Identifier TEX_NORMAL = new Identifier("redtale_economy", "textures/entity/normal_package.png");
    private static final Identifier TEX_RARE = new Identifier("redtale_economy", "textures/entity/rare_package.png");
    private static final Identifier TEX_LEGENDARY = new Identifier("redtale_economy", "textures/entity/legendary_package.png");

    @Override
    public Identifier getModelResource(PackageEntity animatable) {
        return GEO;
    }

    /**
     * 按档位选贴图。★不能用 {@code animatable.getPackageStack()}★：那个字段只在服务端构造器里赋值，
     * 客户端实体是空栈 ⇒ 永远取到 normal。档位走 DataTracker（{@code PACKAGE_TIER}）同步。
     */
    @Override
    public Identifier getTextureResource(PackageEntity animatable) {
        if (animatable == null) return TEX_NORMAL;
        String tier = animatable.getPackageTier();
        if ("legendary".equals(tier)) return TEX_LEGENDARY;
        if ("rare".equals(tier)) return TEX_RARE;
        return TEX_NORMAL;
    }

    @Override
    public Identifier getAnimationResource(PackageEntity animatable) {
        return ANIM;
    }
}
