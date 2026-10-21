package com.akira.redtale.economy.legacy.item.client;

import com.akira.redtale.economy.legacy.LegacyInit;
import com.akira.redtale.economy.legacy.data.PackageCatalog;
import com.akira.redtale.economy.legacy.item.PackageItem;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import software.bernie.geckolib.model.GeoModel;

/**
 * 包裹物品模型：与实体同源（geo/动画按系列，贴图按档位）。
 * 物品 id 直接就是皮肤 key（{@code chest_legend}），查 {@link PackageCatalog} 即可。
 *
 * <p>T74：special（{@code surprise}）与系列**同一个出口** {@link PackageCatalog#renderOf}；
 * 旧写法 series==null 就回落默认包裹，彩蛋拿到手也是普通包裹外观。
 */
public class PackageItemModel extends GeoModel<PackageItem> {

    private static final Identifier GEO_DEFAULT = LegacyInit.id("geo/package.geo.json");
    private static final Identifier ANIM_DEFAULT = LegacyInit.id("animations/package_item.animation.json");
    private static final Identifier TEX_DEFAULT = LegacyInit.id("textures/item/package/normal.png");

    @Override
    public Identifier getModelResource(PackageItem animatable) {
        PackageCatalog.RenderSpec spec = specOf(animatable);
        return spec == null ? GEO_DEFAULT : idFromSpec(spec.geo(), GEO_DEFAULT);
    }

    @Override
    public Identifier getTextureResource(PackageItem animatable) {
        PackageCatalog.RenderSpec spec = specOf(animatable);
        if (spec == null) return TEX_DEFAULT;
        return LegacyInit.id("textures/" + spec.texturePath());
    }

    @Override
    public Identifier getAnimationResource(PackageItem animatable) {
        PackageCatalog.RenderSpec spec = specOf(animatable);
        if (spec == null) return ANIM_DEFAULT;
        // ★物品动画与实体动画是两个文件★：物品只有 {@code item.idle}（键在 package_item.animation.json 里），
        // 拿实体的 package.animation.json 当物品动画 ⇒ geckolib 找不到动画、物品永远静止。
        return idFromSpec(spec.animItem(), ANIM_DEFAULT);
    }

    private static PackageCatalog.RenderSpec specOf(PackageItem animatable) {
        if (animatable == null) return null;
        return PackageCatalog.renderOf(Registries.ITEM.getId(animatable).getPath());
    }

    private static Identifier idFromSpec(String spec, Identifier def) {
        if (spec == null || spec.isBlank()) return def;
        Identifier id = Identifier.tryParse(spec);
        return id == null ? def : id;
    }
}
