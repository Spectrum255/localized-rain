package com.spectrum255.localizedrain.api;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.List;
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
 * @param particleCount      每 tick 生成的基准粒子数（默认 80；0 表示不生成）。实际数量会按强度缩放，
 *                           并受客户端每 tick 上限（当前 160）约束
 * @param soundIntervalTicks 环境音播放间隔（tick，默认 40；小于等于 0 表示不播放）
 * @param visuals            可选：客户端雾效提示
 * @param effects            可选：服务端玩法效果（伤害、移动速度）
 * @param extra              可选：任意附加数据（NBT），供制作方读取
 * @param lifecycle          可选：片模式下的强度生命周期（淡入/淡出）
 * @param next               可选：片模式寿命到期后的演化目标（按权重抽取）
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
        boolean requiresSkylight,
        SkylightMode skylight,
        Lifecycle lifecycle,
        List<Evolution> next
) {
    public static final int DEFAULT_PARTICLE_COUNT = 80;
    public static final int DEFAULT_SOUND_INTERVAL_TICKS = 40;
    /**
     * 显示键的长度上限，与 {@code WeatherStatePayload.MAX_TEXT} 对齐。
     * 数据包可以写出任意长度的 {@code display_key}，过长会让 HUD 广播在编码时抛
     * {@code EncoderException}（整段天气同步失败），因此在注册期就截断。
     */
    public static final int MAX_DISPLAY_KEY = 128;

    /** 解码/编码两侧都截断，避免超长键进入注册表后污染网络包。 */
    public static final Codec<String> DISPLAY_KEY_CODEC = Codec.STRING.xmap(
            key -> key.length() > MAX_DISPLAY_KEY ? key.substring(0, MAX_DISPLAY_KEY) : key,
            key -> key);

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
            DISPLAY_KEY_CODEC.optionalFieldOf("display_key", "").forGetter(WeatherType::displayKey),
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
            Codec.BOOL.optionalFieldOf("requires_skylight", false).forGetter(WeatherType::requiresSkylight),
            SkylightMode.CODEC.optionalFieldOf("skylight", SkylightMode.ANY).forGetter(WeatherType::skylight),
            Lifecycle.CODEC.optionalFieldOf("lifecycle", Lifecycle.NONE).forGetter(WeatherType::lifecycle),
            Evolution.CODEC.listOf().optionalFieldOf("next", List.of()).forGetter(WeatherType::next)
    ).apply(instance, WeatherType::new));

    /** 兼容旧构造：没有显示名、渲染提示与玩法效果的内置类型。 */
    public WeatherType(String displayKey, boolean hasPrecipitation, Biome.Precipitation precipitation,
                       boolean allowsThunder) {
        this(displayKey, Optional.empty(), hasPrecipitation, precipitation, allowsThunder,
                Optional.empty(), Optional.empty(), DEFAULT_PARTICLE_COUNT, DEFAULT_SOUND_INTERVAL_TICKS,
                Visuals.EMPTY, Effects.EMPTY, new CompoundTag(), false, SkylightMode.ANY,
                Lifecycle.NONE, List.of());
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
        String derived = "weather." + id.getNamespace() + "." + id.getPath();
        if (derived.length() > MAX_DISPLAY_KEY) {
            derived = derived.substring(0, MAX_DISPLAY_KEY);
        }
        return new WeatherType(derived, this.display,
                this.hasPrecipitation, this.precipitation, this.allowsThunder,
                this.particle, this.sound, this.particleCount, this.soundIntervalTicks,
                this.visuals, this.effects, this.extra, this.requiresSkylight, this.skylight,
                this.lifecycle, this.next);
    }

    /**
     * 片模式的强度生命周期：片刚被设置时强度从 0 线性升到 1（{@code ramp_in_ticks} 内），
     * 寿命结束前从 1 线性降到 0（{@code ramp_out_ticks} 内）。两者都为 0 表示关闭（强度恒为 1）。
     *
     * <p>只影响片模式（{@code weatherMode} 非 {@code mobile}）的雨/雷强度；风暴强度由阶段曲线控制。</p>
     */
    public record Lifecycle(int rampInTicks, int rampOutTicks) {
        public static final Lifecycle NONE = new Lifecycle(0, 0);

        public boolean enabled() {
            return this.rampInTicks > 0 || this.rampOutTicks > 0;
        }

        /**
         * @param ageTicks     该天气已经持续的 tick 数
         * @param remainingTicks 当前天气（雨或晴）距结束还有多少 tick
         */
        public float factor(long ageTicks, long remainingTicks) {
            if (!enabled()) {
                return 1.0F;
            }
            float fadeIn = this.rampInTicks <= 0
                    ? 1.0F : Math.min(1.0F, (float) Math.max(0L, ageTicks) / this.rampInTicks);
            float fadeOut = this.rampOutTicks <= 0
                    ? 1.0F : Math.min(1.0F, (float) Math.max(0L, remainingTicks) / this.rampOutTicks);
            return Math.max(0.0F, Math.min(fadeIn, fadeOut));
        }

        public static final Codec<Lifecycle> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.INT.optionalFieldOf("ramp_in_ticks", 0).forGetter(Lifecycle::rampInTicks),
                Codec.INT.optionalFieldOf("ramp_out_ticks", 0).forGetter(Lifecycle::rampOutTicks)
        ).apply(instance, Lifecycle::new));
    }

    /**
     * 片模式演化目标：当前天气寿命结束后，按权重在满足条件的候选里抽取下一个天气。
     *
     * <p>目标必须在该片的候选池（白名单/黑名单/锁定结果）里，否则本条被忽略；所有条目都不可用时
     * 回退到随机候选池（与原行为一致）。</p>
     *
     * @param type              目标天气类型
     * @param weight            抽取权重（1–1000000）
     * @param minBiomeTemperature 可选：片群系温度下限
     * @param maxBiomeTemperature 可选：片群系温度上限
     * @param biomeTag          可选：片群系必须带有的标签
     */
    public record Evolution(ResourceLocation type, int weight,
                            Optional<Float> minBiomeTemperature, Optional<Float> maxBiomeTemperature,
                            Optional<ResourceLocation> biomeTag) {
        public static final Codec<Evolution> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                ResourceLocation.CODEC.fieldOf("type").forGetter(Evolution::type),
                Codec.intRange(1, 1_000_000).optionalFieldOf("weight", 1).forGetter(Evolution::weight),
                Codec.FLOAT.optionalFieldOf("min_biome_temperature").forGetter(Evolution::minBiomeTemperature),
                Codec.FLOAT.optionalFieldOf("max_biome_temperature").forGetter(Evolution::maxBiomeTemperature),
                ResourceLocation.CODEC.optionalFieldOf("biome_tag").forGetter(Evolution::biomeTag)
        ).apply(instance, Evolution::new));
    }

    /**
     * 粒子速度：内置视觉层生成粒子时使用的初速度（例如雨雪下落 {@code [0.0, -0.6, 0.0]}）。
     */
    public record Motion(double x, double y, double z) {
        public static final Motion DEFAULT = new Motion(0.0D, -0.05D, 0.0D);

        public static final Codec<Motion> CODEC = Codec.DOUBLE.listOf(3, 3).xmap(
                values -> new Motion(values.get(0), values.get(1), values.get(2)),
                motion -> List.of(motion.x(), motion.y(), motion.z()));
    }

    /**
     * 客户端视觉提示 + 天空盒覆盖：
     * <ul>
     *   <li>{@code fog_*} / {@code particle_motion} 由内置视觉层消费；</li>
     *   <li>{@code sky_darken} 是 0–1 的「天空变暗」强度，服务端会把它计入该位置的天空亮度，
     *       即使天气本身不降水也能像雷暴一样压低光照、触发刷怪；</li>
     *   <li>{@code sky_color} / {@code cloud_color} / {@code sunrise_color} / {@code star_brightness} /
     *       {@code sun_visible} / {@code moon_visible} 由客户端天空覆盖层消费（{@code WeatherSky}）：
     *       分别覆盖天空基色、云色调、日出日落颜色、星星亮度乘数，并开关日月显示。</li>
     * </ul>
     */
    public record Visuals(Optional<Integer> fogColor, Optional<Float> fogNear, Optional<Float> fogFar,
                          Optional<Motion> particleMotion, float skyDarken,
                          Optional<Integer> skyColor, Optional<Integer> cloudColor, Optional<Integer> sunriseColor,
                          Optional<Float> starBrightness, boolean sunVisible, boolean moonVisible) {
        public static final Visuals EMPTY = new Visuals(Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), 0.0F, Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), true, true);

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
                Codec.FLOAT.optionalFieldOf("fog_far").forGetter(Visuals::fogFar),
                Motion.CODEC.optionalFieldOf("particle_motion").forGetter(Visuals::particleMotion),
                Codec.floatRange(0.0F, 1.0F).optionalFieldOf("sky_darken", 0.0F).forGetter(Visuals::skyDarken),
                COLOR_CODEC.optionalFieldOf("sky_color").forGetter(Visuals::skyColor),
                COLOR_CODEC.optionalFieldOf("cloud_color").forGetter(Visuals::cloudColor),
                COLOR_CODEC.optionalFieldOf("sunrise_color").forGetter(Visuals::sunriseColor),
                Codec.floatRange(0.0F, 1.0F).optionalFieldOf("star_brightness").forGetter(Visuals::starBrightness),
                Codec.BOOL.optionalFieldOf("sun_visible", true).forGetter(Visuals::sunVisible),
                Codec.BOOL.optionalFieldOf("moon_visible", true).forGetter(Visuals::moonVisible)
        ).apply(instance, Visuals::new));
    }

    /**
     * 服务端玩法效果，片模式与移动模式都会执行：
     * <ul>
     *   <li>{@code damage} 按 {@code interval_ticks} 周期对暴露在天气中的玩家造成伤害；</li>
     *   <li>{@code movement_speed} 是乘算倍率（1.0 表示不改变）；</li>
     *   <li>{@code ignite} 让暴露玩家着火（秒）；</li>
     *   <li>{@code freeze} 累积细雪冻结值；</li>
     *   <li>{@code potions} 周期性给予药水效果（建议 {@code duration_ticks} 大于刷新间隔）。</li>
     * </ul>
     * 所有子项都支持 {@code only_when_exposed}（默认 true）：要求玩家头顶能看到天空。
     */
    public record Effects(Optional<Damage> damage, Optional<Float> movementSpeed,
                          Optional<Ignite> ignite, Optional<Freeze> freeze, List<Potion> potions,
                          List<AttributeMod> attributes) {
        public static final Effects EMPTY = new Effects(Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), List.of(), List.of());

        /**
         * 任意属性修饰符：由服务端按天气层合成后写入玩家的 {@code AttributeMap}。
         *
         * <p>{@code amount} 按 {@code operation} 解释：{@code add_value} 是原值加成，
         * {@code add_multiplied_base} / {@code add_multiplied_total} 是基值/总值的乘算比例。
         * 同一属性、同一 operation 的多个条目会先求和再写入一个固定 id 的修饰符，
         * 所以不同天气层叠加时不会互相覆盖；天气结束、玩家退出或死亡时统一移除。</p>
         *
         * <p>{@code only_when_exposed} 为 true（默认）时只对能看到天空的玩家生效。</p>
         */
        public record AttributeMod(ResourceLocation attribute, double amount, Operation operation,
                                   boolean onlyWhenExposed) {
            public static final Codec<AttributeMod> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                    ResourceLocation.CODEC.fieldOf("attribute").forGetter(AttributeMod::attribute),
                    Codec.DOUBLE.fieldOf("amount").forGetter(AttributeMod::amount),
                    Operation.CODEC.optionalFieldOf("operation", Operation.ADD_VALUE).forGetter(AttributeMod::operation),
                    Codec.BOOL.optionalFieldOf("only_when_exposed", true).forGetter(AttributeMod::onlyWhenExposed)
            ).apply(instance, AttributeMod::new));

            /** 与原版 {@code AttributeModifier.Operation} 一一对应的三种计算方式。 */
            public enum Operation {
                ADD_VALUE, ADD_MULTIPLIED_BASE, ADD_MULTIPLIED_TOTAL;

                /** 数据包里的写法（小写下划线），例如 {@code add_multiplied_total}。 */
                public String key() {
                    return name().toLowerCase(java.util.Locale.ROOT);
                }

                public net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation vanilla() {
                    return switch (this) {
                        case ADD_VALUE -> net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADD_VALUE;
                        case ADD_MULTIPLIED_BASE ->
                                net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADD_MULTIPLIED_BASE;
                        case ADD_MULTIPLIED_TOTAL ->
                                net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL;
                    };
                }

                public static final Codec<Operation> CODEC = Codec.STRING.comapFlatMap(
                        text -> {
                            try {
                                return DataResult.success(valueOf(text.trim().toUpperCase(java.util.Locale.ROOT)));
                            } catch (IllegalArgumentException exception) {
                                return DataResult.error(() -> "unknown attribute operation '" + text + "'");
                            }
                        },
                        Operation::key);
            }
        }

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

        /** 着火：{@code seconds} 秒（会取当前剩余燃烧时间与配置值的较大者，不会缩短更长的火）。 */
        public record Ignite(float seconds, boolean onlyWhenExposed) {
            public static final Codec<Ignite> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                    Codec.FLOAT.fieldOf("seconds").forGetter(Ignite::seconds),
                    Codec.BOOL.optionalFieldOf("only_when_exposed", true).forGetter(Ignite::onlyWhenExposed)
            ).apply(instance, Ignite::new));
        }

        /** 冻结：每 tick 增加 {@code ticks} 点细雪冻结值（原版细雪满 140 点开始掉血）。 */
        public record Freeze(int ticks, boolean onlyWhenExposed) {
            public static final Codec<Freeze> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                    Codec.INT.fieldOf("ticks").forGetter(Freeze::ticks),
                    Codec.BOOL.optionalFieldOf("only_when_exposed", true).forGetter(Freeze::onlyWhenExposed)
            ).apply(instance, Freeze::new));
        }

        /**
         * 周期性药水效果。
         *
         * @param effect          药水效果 id，例如 {@code minecraft:slowness}
         * @param durationTicks   持续时间（tick）；天气激活期间会持续刷新，建议 ≥ 100
         * @param amplifier       等级（0 表示 I 级）
         * @param ambient         是否是环境效果（粒子更透明）
         * @param visible         是否显示粒子
         * @param onlyWhenExposed 是否只在暴露于天空时给予
         */
        public record Potion(ResourceLocation effect, int durationTicks, int amplifier,
                             boolean ambient, boolean visible, boolean onlyWhenExposed) {
            public static final Codec<Potion> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                    ResourceLocation.CODEC.fieldOf("effect").forGetter(Potion::effect),
                    Codec.INT.optionalFieldOf("duration_ticks", 100).forGetter(Potion::durationTicks),
                    Codec.INT.optionalFieldOf("amplifier", 0).forGetter(Potion::amplifier),
                    Codec.BOOL.optionalFieldOf("ambient", true).forGetter(Potion::ambient),
                    Codec.BOOL.optionalFieldOf("visible", true).forGetter(Potion::visible),
                    Codec.BOOL.optionalFieldOf("only_when_exposed", true).forGetter(Potion::onlyWhenExposed)
            ).apply(instance, Potion::new));
        }

        public static final Codec<Effects> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Damage.CODEC.optionalFieldOf("damage").forGetter(Effects::damage),
                Codec.FLOAT.optionalFieldOf("movement_speed").forGetter(Effects::movementSpeed),
                Ignite.CODEC.optionalFieldOf("ignite").forGetter(Effects::ignite),
                Freeze.CODEC.optionalFieldOf("freeze").forGetter(Effects::freeze),
                Potion.CODEC.listOf().optionalFieldOf("potions", List.of()).forGetter(Effects::potions),
                AttributeMod.CODEC.listOf().optionalFieldOf("attributes", List.of()).forGetter(Effects::attributes)
        ).apply(instance, Effects::new));
    }
}
