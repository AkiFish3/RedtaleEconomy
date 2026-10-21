package com.akira.redtale.economy.legacy.model;

import com.akira.redtale.economy.legacy.LegacyInit;
import com.akira.redtale.economy.legacy.data.PackageCatalog;
import com.akira.redtale.economy.legacy.entity.PackageEntity;
import net.minecraft.util.Identifier;
import software.bernie.geckolib.model.GeoModel;

/**
 * 包裹实体模型：geo/动画**按系列**共用，6 档只换贴图（见 `_PACKAGE_EXTEND.md`）。
 *
 * <p>★不能用 {@code animatable.getPackageStack()}★：那个字段只在服务端构造器里赋值，
 * 客户端实体是空栈 ⇒ 永远取到第一档。皮肤走 DataTracker（{@code PACKAGE_SKIN}）同步。
 *
 * <p>T74：special（{@code surprise}）走 {@link PackageCatalog#renderOf} 的同一出口 —— 以前
 * series==null 直接回落默认包裹，彩蛋永远画成普通包裹（零报错）。
 */
public class PackageModel extends GeoModel<PackageEntity> {

    private static final Identifier GEO_DEFAULT = LegacyInit.id("geo/package.geo.json");
    private static final Identifier ANIM_DEFAULT = LegacyInit.id("animations/package.animation.json");
    private static final Identifier TEX_DEFAULT = LegacyInit.id("textures/item/package/normal.png");

    @Override
    public Identifier getModelResource(PackageEntity animatable) {
        PackageCatalog.RenderSpec spec = specOf(animatable);
        return spec == null ? GEO_DEFAULT : idFromSpec(spec.geo(), GEO_DEFAULT);
    }

    @Override
    public Identifier getTextureResource(PackageEntity animatable) {
        PackageCatalog.RenderSpec spec = specOf(animatable);
        if (spec == null) return TEX_DEFAULT;
        return LegacyInit.id("textures/" + spec.texturePath());
    }

    @Override
    public Identifier getAnimationResource(PackageEntity animatable) {
        PackageCatalog.RenderSpec spec = specOf(animatable);
        return spec == null ? ANIM_DEFAULT : idFromSpec(spec.anim(), ANIM_DEFAULT);
    }

    /** {@code packages.json} 里的 {@code ns:path} → Identifier；格式不对回落默认。 */
    private static Identifier idFromSpec(String spec, Identifier def) {
        if (spec == null || spec.isBlank()) return def;
        Identifier id = Identifier.tryParse(spec);
        return id == null ? def : id;
    }

    private static PackageCatalog.RenderSpec specOf(PackageEntity animatable) {
        if (animatable == null) return null;
        return PackageCatalog.renderOf(animatable.getPackageSkin());
    }
}
