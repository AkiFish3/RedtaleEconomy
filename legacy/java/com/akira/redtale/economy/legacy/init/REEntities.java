package com.akira.redtale.economy.legacy.init;

import com.akira.redtale.economy.legacy.LegacyInit;
import com.akira.redtale.economy.legacy.entity.PackageEntity;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricEntityTypeBuilder;
import net.minecraft.entity.EntityDimensions;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;

public class REEntities {
    public static final EntityType<PackageEntity> PACKAGE_ENTITY = Registry.register(
            Registries.ENTITY_TYPE,
            new Identifier(LegacyInit.MOD_ID, "package_entity"),
            FabricEntityTypeBuilder.<PackageEntity>create(SpawnGroup.MISC, PackageEntity::new)
                    .dimensions(EntityDimensions.changing(0.8f, 0.8f))
                    .build()
    );

    public static void register() {
        LegacyInit.LOGGER.info("✅ Registered LegacyInit entities");
    }
}