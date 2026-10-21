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
 */
public class PackageModel extends GeoModel<PackageEntity> {

    private static final Identifier GEO_DEFAULT = LegacyInit.id("geo/package.geo.json");
    private static final Identifier ANIM_DEFAULT = LegacyInit.id("animations/package.animation.json");
    private static final Identifier TEX_DEFAULT = LegacyInit.id("textures/item/package/normal.png");

    @Override
    public Identifier getModelResource(PackageEntity animatable) {
        PackageCatalog.Series s = seriesOf(animatable);
        return s == null ? GEO_DEFAULT : idFromSpec(s.geo(), GEO_DEFAULT);
    }

    @Override
    public Identifier getTextureResource(PackageEntity animatable) {
        PackageCatalog.Series s = seriesOf(animatable);
        String tier = animatable == null ? "normal" : animatable.getPackageTier();
        if (s == null) return TEX_DEFAULT;
        return LegacyInit.id("textures/" + s.texturePath(tier));
    }

    @Override
    public Identifier getAnimationResource(PackageEntity animatable) {
        PackageCatalog.Series s = seriesOf(animatable);
        return s == null ? ANIM_DEFAULT : idFromSpec(s.anim(), ANIM_DEFAULT);
    }

    /** {@code packages.json} 里的 {@code ns:path} → Identifier；格式不对回落默认。 */
    private static Identifier idFromSpec(String spec, Identifier def) {
        if (spec == null || spec.isBlank()) return def;
        Identifier id = Identifier.tryParse(spec);
        return id == null ? def : id;
    }

    private static PackageCatalog.Series seriesOf(PackageEntity animatable) {
        if (animatable == null) return null;
        String[] skin = PackageCatalog.skinOf(animatable.getPackageSkin());
        return skin == null || skin[0] == null ? null : PackageCatalog.seriesById(skin[0]);
    }
}
