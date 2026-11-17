package com.spectrum255.localizedrain.api;

import com.mojang.serialization.Codec;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;

/**
 * 维度选择器：数据包用它在 {@code storm_profile.dimensions} 里限制「哪些维度会生成这个档案」。
 *
 * <p>支持三种写法：</p>
 * <ul>
 *     <li>维度 id 或维度类型 id，例如 {@code minecraft:overworld}、{@code minecraft:the_nether}；</li>
 *     <li>维度类型标签，例如 {@code #localizedrain:cold_dimensions}；</li>
 *     <li>命名空间通配，例如 {@code mydatapack:*}。</li>
 * </ul>
 *
 * <p>维度 id 对任何已注册维度（包括数据包添加的维度）都有效，不要求是内置维度。
 * 标签按 {@link Registries#DIMENSION_TYPE} 解析，数据包在
 * {@code data/<namespace>/tags/minecraft/dimension_type/<id>.json} 里定义。</p>
 */
public record DimensionSelector(Kind kind, ResourceLocation id, String namespace, String raw) {
    public enum Kind {
        ID, TAG, NAMESPACE, INVALID
    }

    public static final Codec<DimensionSelector> CODEC =
            Codec.STRING.xmap(DimensionSelector::parse, DimensionSelector::raw);

    public static DimensionSelector parse(String value) {
        String text = value == null ? "" : value.trim();
        if (text.isEmpty()) {
            return new DimensionSelector(Kind.INVALID, null, "", text);
        }
        if (text.startsWith("#")) {
            ResourceLocation id = ResourceLocation.tryParse(text.substring(1));
            return id == null
                    ? new DimensionSelector(Kind.INVALID, null, "", text)
                    : new DimensionSelector(Kind.TAG, id, id.getNamespace(), text);
        }
        if (text.endsWith(":*")) {
            String namespace = text.substring(0, text.length() - 2);
            return ResourceLocation.isValidNamespace(namespace)
                    ? new DimensionSelector(Kind.NAMESPACE, null, namespace, text)
                    : new DimensionSelector(Kind.INVALID, null, "", text);
        }
        ResourceLocation id = ResourceLocation.tryParse(text);
        return id == null
                ? new DimensionSelector(Kind.INVALID, null, "", text)
                : new DimensionSelector(Kind.ID, id, id.getNamespace(), text);
    }

    /** 该选择器是否命中这个维度。 */
    public boolean test(Level level) {
        return switch (this.kind) {
            case ID -> matchesId(level);
            case TAG -> matchesTag(level);
            case NAMESPACE -> matchesNamespace(level);
            case INVALID -> false;
        };
    }

    private boolean matchesId(Level level) {
        if (level.dimension().location().equals(this.id)) {
            return true;
        }
        ResourceKey<DimensionType> typeKey = level.dimensionTypeRegistration().unwrapKey().orElse(null);
        return typeKey != null && typeKey.location().equals(this.id);
    }

    private boolean matchesTag(Level level) {
        TagKey<DimensionType> tag = TagKey.create(Registries.DIMENSION_TYPE, this.id);
        return level.dimensionTypeRegistration().is(tag);
    }

    private boolean matchesNamespace(Level level) {
        if (level.dimension().location().getNamespace().equals(this.namespace)) {
            return true;
        }
        ResourceKey<DimensionType> typeKey = level.dimensionTypeRegistration().unwrapKey().orElse(null);
        return typeKey != null && typeKey.location().getNamespace().equals(this.namespace);
    }
}
