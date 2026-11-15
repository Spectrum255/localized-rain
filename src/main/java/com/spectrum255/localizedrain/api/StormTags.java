package com.spectrum255.localizedrain.api;

import com.spectrum255.localizedrain.Localizedrain;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.biome.Biome;

/** 风暴相关的群系标签。 */
public final class StormTags {
    /** 完全不受风暴影响的群系（空标签默认不隔离任何群系）。 */
    public static final TagKey<Biome> STORM_ISOLATED =
            TagKey.create(Registries.BIOME,
                    ResourceLocation.fromNamespaceAndPath(Localizedrain.MODID, "storm_isolated"));

    private StormTags() {}
}
