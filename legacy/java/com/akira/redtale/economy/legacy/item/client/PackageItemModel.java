package com.akira.redtale.economy.legacy.item.client;

import com.akira.redtale.economy.legacy.LegacyInit;
import com.akira.redtale.economy.legacy.item.PackageItem;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import software.bernie.geckolib.model.GeoModel;

public class PackageItemModel extends GeoModel<PackageItem> {

    private static final Identifier GEO = new Identifier(LegacyInit.MOD_ID, "geo/package.geo.json");
    private static final Identifier ANIM = new Identifier(LegacyInit.MOD_ID, "animations/package_item.animation.json");
    private static final Identifier TEX_NORMAL = new Identifier(LegacyInit.MOD_ID, "textures/item/normal_package.png");
    private static final Identifier TEX_RARE = new Identifier(LegacyInit.MOD_ID, "textures/item/rare_package.png");
    private static final Identifier TEX_LEGENDARY = new Identifier(LegacyInit.MOD_ID, "textures/item/legendary_package.png");

    @Override
    public Identifier getModelResource(PackageItem animatable) {
        return GEO;
    }

    /** 按注册名选贴图（registry id 比 translationKey 稳：后者受 lang 影响，这里只看 id 的 path）。 */
    @Override
    public Identifier getTextureResource(PackageItem animatable) {
        if (animatable == null) return TEX_NORMAL;
        String path = Registries.ITEM.getId(animatable).getPath();
        if (path.startsWith("legendary")) return TEX_LEGENDARY;
        if (path.startsWith("rare")) return TEX_RARE;
        return TEX_NORMAL;
    }

    @Override
    public Identifier getAnimationResource(PackageItem animatable) {
        return ANIM;
    }
}
