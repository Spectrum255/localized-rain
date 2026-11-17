package com.spectrum255.localizedrain.api;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Optional;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.Biome;

/**
 * 天气类型定义。类型本身是数据包注册表 {@code localizedrain:weather_type} 的条目，
 * 数据包可以在 {@code data/<namespace>/localizedrain/weather_type/<id>.json} 里新增。
 *
 * <p>这里只描述「天气是什么」：名称、降水形态、是否允许雷暴，以及给客户端渲染用的
 * 资源提示（{@code particle} / {@code sound}）与任意附加数据（{@code extra}）。
 * 粒子与特殊效果本身由制作方自行渲染——模组只提供读取接口，不替自定义类型硬编码渲染。</p>
 *
 * @param displayKey       名称翻译键；留空时按类型 id 推导为 {@code weather.<namespace>.<path>}
 * @param display          可选的纯文本显示名（没有资源包翻译时用）
 * @param hasPrecipitation 是否产生降水（影响积雪、炼药锅、粒子判定）
 * @param precipitation    降水形态；{@link Biome.Precipitation#NONE} 表示不产生降水
 * @param allowsThunder    是否允许雷暴与闪电
 * @param particle         可选：客户端粒子资源 id（由制作方读取并渲染）
 * @param sound            可选：客户端环境音资源 id（由制作方读取并播放）
 * @param extra            可选：任意附加数据（NBT），供制作方读取
 */
public record WeatherType(
        String displayKey,
        Optional<String> display,
        boolean hasPrecipitation,
        Biome.Precipitation precipitation,
        boolean allowsThunder,
        Optional<ResourceLocation> particle,
        Optional<ResourceLocation> sound,
        CompoundTag extra
) {
    public static final Codec<Biome.Precipitation> PRECIPITATION_CODEC = Codec.STRING.comapFlatMap(
            name -> switch (name) {
                case "none" -> DataResult.success(Biome.Precipitation.NONE);
                case "rain" -> DataResult.success(Biome.Precipitation.RAIN);
                case "snow" -> DataResult.success(Biome.Precipitation.SNOW);
                default -> DataResult.error(() -> "unknown precipitation: " + name);
            },
            value -> value == Biome.Precipitation.RAIN ? "rain"
                    : value == Biome.Precipitation.SNOW ? "snow" : "none");

    public static final Codec<WeatherType> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.optionalFieldOf("display_key", "").forGetter(WeatherType::displayKey),
            Codec.STRING.optionalFieldOf("display").forGetter(WeatherType::display),
            Codec.BOOL.optionalFieldOf("has_precipitation", false).forGetter(WeatherType::hasPrecipitation),
            PRECIPITATION_CODEC.optionalFieldOf("precipitation", Biome.Precipitation.NONE)
                    .forGetter(WeatherType::precipitation),
            Codec.BOOL.optionalFieldOf("allows_thunder", false).forGetter(WeatherType::allowsThunder),
            ResourceLocation.CODEC.optionalFieldOf("particle").forGetter(WeatherType::particle),
            ResourceLocation.CODEC.optionalFieldOf("sound").forGetter(WeatherType::sound),
            CompoundTag.CODEC.optionalFieldOf("extra", new CompoundTag()).forGetter(WeatherType::extra)
    ).apply(instance, WeatherType::new));

    /** 兼容旧构造：没有显示名与渲染提示的内置类型。 */
    public WeatherType(String displayKey, boolean hasPrecipitation, Biome.Precipitation precipitation,
                       boolean allowsThunder) {
        this(displayKey, Optional.empty(), hasPrecipitation, precipitation, allowsThunder,
                Optional.empty(), Optional.empty(), new CompoundTag());
    }

    /** 没有降水也没有雷暴的类型（晴天）。 */
    public boolean isClear() {
        return !this.hasPrecipitation && !this.allowsThunder;
    }

    /** 显示名：优先使用数据包里的纯文本，否则走翻译键。 */
    public Component displayName() {
        return this.display.map(Component::literal).orElseGet(() -> Component.translatable(this.displayKey));
    }

    /** 数据包没有写 display_key 时，按类型 id 推导翻译键。 */
    public WeatherType withDerivedDisplayKey(ResourceLocation id) {
        if (!this.displayKey.isEmpty() || id == null) {
            return this;
        }
        return new WeatherType("weather." + id.getNamespace() + "." + id.getPath(), this.display,
                this.hasPrecipitation, this.precipitation, this.allowsThunder,
                this.particle, this.sound, this.extra);
    }
}
