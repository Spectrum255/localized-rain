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
 * 资源提示（{@code particle} / {@code sound} / {@code visuals}）、玩法效果（{@code effects}）
 * 与任意附加数据（{@code extra}）。</p>
 *
 * <p>模组内置的客户端视觉层会直接消费这些提示：{@code particle} 指向一个
 * {@link net.minecraft.core.particles.SimpleParticleType}，在天气激活时围绕玩家生成；
 * {@code sound} 指向一个环境音，按 {@code sound_interval_ticks} 间隔播放；
 * {@code visuals.fog_color/fog_near/fog_far} 会覆盖默认雾效。需要复杂粒子（带颜色/速度等选项）
 * 或自定义渲染时，制作方可以继续读取这些字段自行渲染，模组不会覆盖 {@code extra} 的语义。</p>
 *
 * @param displayKey         名称翻译键；留空时按类型 id 推导为 {@code weather.<namespace>.<path>}
 * @param display            可选的纯文本显示名（没有资源包翻译时用）
 * @param hasPrecipitation   是否产生降水（影响积雪、炼药锅、粒子判定）
 * @param precipitation      降水形态；{@link Biome.Precipitation#NONE} 表示不产生降水
 * @param allowsThunder      是否允许雷暴与闪电
 * @param particle           可选：客户端粒子资源 id（内置视觉层只支持简单粒子）
 * @param sound              可选：客户端环境音资源 id
 * @param particleCount      每秒生成的粒子数（默认 80；0 表示不生成）
 * @param soundIntervalTicks 环境音播放间隔（tick，默认 40；小于等于 0 表示不播放）
 * @param visuals            可选：客户端雾效提示
 * @param effects            可选：服务端玩法效果（伤害、移动速度）
 * @param extra              可选：任意附加数据（NBT），供制作方读取
 */
public record WeatherType(
        String displayKey,
        Optional<String> display,
        boolean hasPrecipitation,
        Biome.Precipitation precipitation,
        boolean allowsThunder,
        Optional<ResourceLocation> particle,
        Optional<ResourceLocation> sound,
        int particleCount,
        int soundIntervalTicks,
        Visuals visuals,
        Effects effects,
        CompoundTag extra,
        boolean requiresSkylight
) {
    public static final int DEFAULT_PARTICLE_COUNT = 80;
    public static final int DEFAULT_SOUND_INTERVAL_TICKS = 40;

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
            Codec.INT.optionalFieldOf("particle_count", DEFAULT_PARTICLE_COUNT).forGetter(WeatherType::particleCount),
            Codec.INT.optionalFieldOf("sound_interval_ticks", DEFAULT_SOUND_INTERVAL_TICKS)
                    .forGetter(WeatherType::soundIntervalTicks),
            Visuals.CODEC.optionalFieldOf("visuals", Visuals.EMPTY).forGetter(WeatherType::visuals),
            Effects.CODEC.optionalFieldOf("effects", Effects.EMPTY).forGetter(WeatherType::effects),
            CompoundTag.CODEC.optionalFieldOf("extra", new CompoundTag()).forGetter(WeatherType::extra),
            Codec.BOOL.optionalFieldOf("requires_skylight", false).forGetter(WeatherType::requiresSkylight)
    ).apply(instance, WeatherType::new));

    /** 兼容旧构造：没有显示名、渲染提示与玩法效果的内置类型。 */
    public WeatherType(String displayKey, boolean hasPrecipitation, Biome.Precipitation precipitation,
                       boolean allowsThunder) {
        this(displayKey, Optional.empty(), hasPrecipitation, precipitation, allowsThunder,
                Optional.empty(), Optional.empty(), DEFAULT_PARTICLE_COUNT, DEFAULT_SOUND_INTERVAL_TICKS,
                Visuals.EMPTY, Effects.EMPTY, new CompoundTag(), false);
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
                this.particle, this.sound, this.particleCount, this.soundIntervalTicks,
                this.visuals, this.effects, this.extra, this.requiresSkylight);
    }

    /** 客户端雾效提示；全部缺省时沿用原版雾。 */
    public record Visuals(Optional<Integer> fogColor, Optional<Float> fogNear, Optional<Float> fogFar) {
        public static final Visuals EMPTY = new Visuals(Optional.empty(), Optional.empty(), Optional.empty());

        /** 接受 {@code #rrggbb}、{@code 0xrrggbb} 或 6 位十六进制，统一补成不透明 ARGB。 */
        public static final Codec<Integer> COLOR_CODEC = Codec.STRING.comapFlatMap(
                value -> {
                    String text = value == null ? "" : value.trim();
                    try {
                        if (text.startsWith("#")) {
                            text = text.substring(1);
                        } else if (text.startsWith("0x") || text.startsWith("0X")) {
                            text = text.substring(2);
                        }
                        if (text.length() != 6) {
                            return DataResult.error(() -> "expected #rrggbb color, got '" + value + "'");
                        }
                        return DataResult.success((int) (Long.parseLong(text, 16) | 0xFF000000L));
                    } catch (NumberFormatException exception) {
                        return DataResult.error(() -> "invalid color '" + value + "'");
                    }
                },
                color -> String.format("#%06x", color & 0xFFFFFF));

        public static final Codec<Visuals> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                COLOR_CODEC.optionalFieldOf("fog_color").forGetter(Visuals::fogColor),
                Codec.FLOAT.optionalFieldOf("fog_near").forGetter(Visuals::fogNear),
                Codec.FLOAT.optionalFieldOf("fog_far").forGetter(Visuals::fogFar)
        ).apply(instance, Visuals::new));
    }

    /**
     * 服务端玩法效果。{@code damage} 按 {@code interval_ticks} 周期对暴露在天气中的玩家造成伤害；
     * {@code movement_speed} 是乘算倍率（1.0 表示不改变）。只在片模式（patches）生效。
     */
    public record Effects(Optional<Damage> damage, Optional<Float> movementSpeed) {
        public static final Effects EMPTY = new Effects(Optional.empty(), Optional.empty());

        /** @param type 可选：伤害类型 id；缺省用 generic。 */
        public record Damage(float amount, int intervalTicks, boolean onlyWhenExposed,
                             Optional<ResourceLocation> type) {
            public static final Codec<Damage> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                    Codec.FLOAT.fieldOf("amount").forGetter(Damage::amount),
                    Codec.INT.optionalFieldOf("interval_ticks", 40).forGetter(Damage::intervalTicks),
                    Codec.BOOL.optionalFieldOf("only_when_exposed", true).forGetter(Damage::onlyWhenExposed),
                    ResourceLocation.CODEC.optionalFieldOf("type").forGetter(Damage::type)
            ).apply(instance, Damage::new));
        }

        public static final Codec<Effects> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Damage.CODEC.optionalFieldOf("damage").forGetter(Effects::damage),
                Codec.FLOAT.optionalFieldOf("movement_speed").forGetter(Effects::movementSpeed)
        ).apply(instance, Effects::new));
    }
}
