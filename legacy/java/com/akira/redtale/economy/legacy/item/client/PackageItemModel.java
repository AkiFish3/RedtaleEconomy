package com.akira.redtale.economy.legacy.item.client;

import com.akira.redtale.economy.legacy.LegacyInit;
import com.akira.redtale.economy.legacy.item.PackageItem;
import net.minecraft.item.Item;
import net.minecraft.util.Identifier;
import software.bernie.geckolib.model.GeoModel;

public class PackageItemModel extends GeoModel<PackageItem> {

    @Override
    public Identifier getModelResource(PackageItem animatable) {
        return new Identifier(LegacyInit.MOD_ID, "geo/package.geo.json");
    }

    @Override
    public Identifier getTextureResource(PackageItem animatable) {
        // 根据物品类型返回不同的贴图
        Item item = Item.byRawId(Item.getRawId(animatable)); // 获取实际物品实例

        // 通过物品的注册名来判断
        String itemName = animatable.getTranslationKey();
        if (itemName.contains("rare")) {
            return new Identifier(LegacyInit.MOD_ID, "textures/item/rare_package.png");
        } else if (itemName.contains("legendary")) {
            return new Identifier(LegacyInit.MOD_ID, "textures/item/legendary_package.png");
        } else {
            return new Identifier(LegacyInit.MOD_ID, "textures/item/normal_package.png");
        }
    }

    @Override
    public Identifier getAnimationResource(PackageItem animatable) {
        // 必须返回一个存在的动画文件
        return new Identifier(LegacyInit.MOD_ID, "animations/package_item.animation.json");
    }
}