package com.spectrum255.localizedrain.api;

import com.mojang.serialization.Codec;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.biome.Biome;

/**
 * 风暴档案的「允许生成的生物群系」选择器，写在档案 JSON 的 {@code biomes} 字段里：
 *
 * <ul>
 *   <li>{@code #minecraft:is_taiga} —— 生物群系标签；</li>
 *   <li>{@code minecraft:desert} —— 单个生物群系；</li>
 *   <li>{@code minecraft:*} —— 整个命名空间。</li>
 * </ul>
 *
 * 列表为空表示不额外限制，交给默认筛选（见 {@link StormProfile#defaultBiomeAllowed}）。
 * 写法非法时保留为 {@link Kind#INVALID}，由 {@link StormProfile} 读档时丢弃并警告，避免中止世界加载。
 */
public record StormBiomeSelector(Kind kind, ResourceLocation id, String namespace, String raw) {

    public enum Kind { TAG, ID, NAMESPACE, INVALID }

    /** 容错的字符串 codec：解析失败不报错，交给读档阶段统一过滤。 */
    public static final Codec<StormBiomeSelector> CODEC =
            Codec.STRING.xmap(StormBiomeSelector::parse, StormBiomeSelector::raw);

    public static StormBiomeSelector parse(String value) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.startsWith("#")) {
            ResourceLocation id = ResourceLocation.tryParse(trimmed.substring(1));
            if (id == null) {
                return new StormBiomeSelector(Kind.INVALID, null, null, trimmed);
            }
            return new StormBiomeSelector(Kind.TAG, id, null, "#" + id);
        }
        if (trimmed.endsWith(":*")) {
            String namespace = trimmed.substring(0, trimmed.length() - 2);
            if (ResourceLocation.tryParse(namespace + ":air") != null) {
                return new StormBiomeSelector(Kind.NAMESPACE, null, namespace, namespace + ":*");
            }
            return new StormBiomeSelector(Kind.INVALID, null, null, trimmed);
        }
        ResourceLocation id = ResourceLocation.tryParse(trimmed);
        if (id == null) {
            return new StormBiomeSelector(Kind.INVALID, null, null, trimmed);
        }
        return new StormBiomeSelector(Kind.ID, id, null, id.toString());
    }

    /** 该选择器是否匹配给定生物群系。 */
    public boolean test(Holder<Biome> biome) {
        return switch (this.kind) {
            case TAG -> biome.is(TagKey.create(Registries.BIOME, this.id));
            case ID -> biome.unwrapKey().map(key -> key.location().equals(this.id)).orElse(false);
            case NAMESPACE -> biome.unwrapKey()
                    .map(key -> key.location().getNamespace().equals(this.namespace))
                    .orElse(false);
            case INVALID -> false;
        };
    }

    @Override
    public String toString() {
        return this.raw;
    }
}
