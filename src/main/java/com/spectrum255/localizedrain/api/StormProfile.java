package com.spectrum255.localizedrain.api;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.spectrum255.localizedrain.Localizedrain;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;

/** 数据驱动风暴档案：形状、阶段序列、环境调制参数、允许生成的维度与生物群系。JSON 路径 data/<ns>/localizedrain/storm_profile/<id>.json。 */
public record StormProfile(int weight, boolean natural, double speedMultiplier, Range radius,
                           VerticalMode vertical, List<Stage> stages, Environment environment,
                           List<StormBiomeSelector> biomes, List<DimensionSelector> dimensions,
                           SkylightMode skylight) {

    /** 竖直判定模式。 */
    public enum VerticalMode implements StringRepresentable {
        SURFACE("surface"), BAND("band"), COLUMN("column");

        public static final Codec<VerticalMode> CODEC = StringRepresentable.fromEnum(VerticalMode::values);
        /** 容错版：未知值回退 {@link #SURFACE}，避免一个笔误中止整个世界加载。 */
        public static final Codec<VerticalMode> TOLERANT_CODEC = Codec.STRING.comapFlatMap(
                value -> {
                    for (VerticalMode mode : values()) {
                        if (mode.name.equalsIgnoreCase(value)) {
                            return DataResult.success(mode);
                        }
                    }
                    Localizedrain.LOGGER.warn("[localizedrain] unknown storm vertical mode '{}', using surface", value);
                    return DataResult.success(SURFACE);
                },
                VerticalMode::getSerializedName);
        private final String name;

        VerticalMode(String name) {
            this.name = name;
        }

        @Override
        public String getSerializedName() {
            return this.name;
        }
    }

    /** 二元区间，JSON 里写作 [min, max]。 */
    public record Range(double min, double max) {
        public static final Codec<Range> CODEC = Codec.list(Codec.DOUBLE)
                .comapFlatMap(list -> {
                            if (list.size() == 2) {
                                return DataResult.success(new Range(list.get(0), list.get(1)));
                            }
                            double first = list.isEmpty() ? 0.0D : list.get(0);
                            Localizedrain.LOGGER.warn("[localizedrain] expected [min, max], got {} value(s); using [{}, {}]",
                                    list.size(), first, first);
                            return DataResult.success(new Range(first, first));
                        },
                        range -> List.of(range.min(), range.max()));

        public float lerp(float delta) {
            return (float) (this.min + (this.max - this.min) * delta);
        }
    }

    /** 单个阶段。阈值字段缺省表示该档案不启用对应阈值。 */
    public record Stage(ResourceLocation type, int minTicks, int maxTicks, Range rain, Range thunder,
                        Range radiusScale, Optional<Float> advanceWhenMoisture, Optional<Float> dieWhenMoisture) {
        private static final ResourceLocation DEFAULT_TYPE = ResourceLocation.fromNamespaceAndPath(Localizedrain.MODID, "rain");
        /** 容错版资源位置：非法 id 回退内置降雨类型并给出 warn 级解析消息。 */
        public static final Codec<ResourceLocation> TOLERANT_TYPE_CODEC = Codec.STRING.comapFlatMap(
                value -> {
                    ResourceLocation id = ResourceLocation.tryParse(value);
                    if (id != null) {
                        return DataResult.success(id);
                    }
                    Localizedrain.LOGGER.warn("[localizedrain] invalid weather type '{}', using localizedrain:rain", value);
                    return DataResult.success(DEFAULT_TYPE);
                },
                ResourceLocation::toString);
        public static final Codec<Stage> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                TOLERANT_TYPE_CODEC.fieldOf("type").forGetter(Stage::type),
                Codec.INT.optionalFieldOf("min_ticks", 4000).forGetter(Stage::minTicks),
                Codec.INT.optionalFieldOf("max_ticks", 8000).forGetter(Stage::maxTicks),
                Range.CODEC.optionalFieldOf("rain", new Range(0.4D, 0.8D)).forGetter(Stage::rain),
                Range.CODEC.optionalFieldOf("thunder", new Range(0.0D, 0.0D)).forGetter(Stage::thunder),
                Range.CODEC.optionalFieldOf("radius_scale", new Range(1.0D, 1.0D)).forGetter(Stage::radiusScale),
                Codec.FLOAT.optionalFieldOf("advance_when_moisture").forGetter(Stage::advanceWhenMoisture),
                Codec.FLOAT.optionalFieldOf("die_when_moisture").forGetter(Stage::dieWhenMoisture)
        ).apply(instance, Stage::new));
    }

    /** 环境层参数。 */
    public record Environment(boolean stageSpeedFromMoisture, double moistureGain, double moistureDecay) {
        public static final Codec<Environment> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.BOOL.optionalFieldOf("stage_speed_from_moisture", true).forGetter(Environment::stageSpeedFromMoisture),
                Codec.DOUBLE.optionalFieldOf("moisture_gain", 0.002D).forGetter(Environment::moistureGain),
                Codec.DOUBLE.optionalFieldOf("moisture_decay", 0.001D).forGetter(Environment::moistureDecay)
        ).apply(instance, Environment::new));
    }

    /** 维度的天光要求，与 {@code dimensions} 列表叠加生效。 */
    public enum SkylightMode implements StringRepresentable {
        ANY("any"), REQUIRED("required"), FORBIDDEN("forbidden");

        /** 容错版：未知值回退 {@link #ANY}，避免一个笔误中止整个世界加载。 */
        public static final Codec<SkylightMode> CODEC = Codec.STRING.comapFlatMap(
                value -> {
                    for (SkylightMode mode : values()) {
                        if (mode.name.equalsIgnoreCase(value)) {
                            return DataResult.success(mode);
                        }
                    }
                    Localizedrain.LOGGER.warn("[localizedrain] unknown storm skylight mode '{}', using any", value);
                    return DataResult.success(ANY);
                },
                SkylightMode::getSerializedName);
        private final String name;

        SkylightMode(String name) {
            this.name = name;
        }

        @Override
        public String getSerializedName() {
            return this.name;
        }
    }

    private static final Codec<StormProfile> RECORD_CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.INT.optionalFieldOf("weight", 10).forGetter(StormProfile::weight),
            Codec.BOOL.optionalFieldOf("natural", true).forGetter(StormProfile::natural),
            Codec.DOUBLE.optionalFieldOf("speed_multiplier", 1.0D).forGetter(StormProfile::speedMultiplier),
            Range.CODEC.optionalFieldOf("radius", new Range(150.0D, 300.0D)).forGetter(StormProfile::radius),
            VerticalMode.TOLERANT_CODEC.optionalFieldOf("vertical", VerticalMode.SURFACE).forGetter(StormProfile::vertical),
            Stage.CODEC.listOf().optionalFieldOf("stages", List.of()).forGetter(StormProfile::stages),
            Environment.CODEC.optionalFieldOf("environment", new Environment(true, 0.002D, 0.001D))
                    .forGetter(StormProfile::environment),
            StormBiomeSelector.CODEC.listOf().optionalFieldOf("biomes", List.of())
                    .forGetter(StormProfile::biomes),
            DimensionSelector.CODEC.listOf().optionalFieldOf("dimensions", List.of())
                    .forGetter(StormProfile::dimensions),
            SkylightMode.CODEC.optionalFieldOf("skylight", SkylightMode.ANY)
                    .forGetter(StormProfile::skylight)
    ).apply(instance, StormProfile::new));

    /**
     * 读档时把越界数值修正到可用范围，而不是报错：
     * 数据包加载器对 codec error 会中止整个世界加载（不是跳过单个档案）。区间允许 min &gt; max，
     * 表示该阶段内数值从 min 递减到 max（强度渐变、半径收缩）。没有任何阶段时补一个默认降雨阶段。
     */
    public static final Codec<StormProfile> CODEC = RECORD_CODEC.flatXmap(StormProfile::sanitize, DataResult::success);

    private static DataResult<StormProfile> sanitize(StormProfile profile) {
        // 数据包注册表 codec error 会中止整个世界加载（不是跳过单个档案）：空阶段时补一个默认降雨阶段。
        boolean emptyStages = profile.stages().isEmpty();
        List<Stage> stages = new ArrayList<>(Math.max(1, profile.stages().size()));
        if (emptyStages) {
            stages.add(new Stage(ResourceLocation.fromNamespaceAndPath(Localizedrain.MODID, "rain"),
                    4000, 8000, new Range(0.4D, 0.7D), new Range(0.0D, 0.0D), new Range(1.0D, 1.0D),
                    Optional.empty(), Optional.empty()));
        }
        for (Stage stage : profile.stages()) {
            int minTicks = Math.max(1, stage.minTicks());
            int maxTicks = Math.max(minTicks, stage.maxTicks());
            Range rain = clamp(stage.rain(), 0.0D, 1.0D);
            Range thunder = clamp(stage.thunder(), 0.0D, 1.0D);
            Range scale = clamp(stage.radiusScale(), 0.0D, 4.0D);
            Optional<Float> advance = stage.advanceWhenMoisture().map(StormProfile::clamp01);
            Optional<Float> die = stage.dieWhenMoisture().map(StormProfile::clamp01);
            stages.add(new Stage(stage.type(), minTicks, maxTicks, rain, thunder, scale, advance, die));
        }
        // 半径是随机生成区间，顺序无意义：统一整理为升序（旧实现会把递减区间压成 [min, min]）
        double lowRadius = Math.min(profile.radius().min(), profile.radius().max());
        double highRadius = Math.max(profile.radius().min(), profile.radius().max());
        double minRadius = Math.max(1.0D, lowRadius);
        double maxRadius = Math.max(minRadius, highRadius);
        int weight = Math.max(1, profile.weight());
        double speed = Math.max(0.01D, profile.speedMultiplier());
        List<StormBiomeSelector> biomes = new ArrayList<>(profile.biomes().size());
        StormBiomeSelector firstInvalidBiome = null;
        for (StormBiomeSelector selector : profile.biomes()) {
            if (selector.kind() == StormBiomeSelector.Kind.INVALID) {
                if (firstInvalidBiome == null) {
                    firstInvalidBiome = selector;   // 非法写法读档时丢弃，避免一个笔误中止整个世界加载
                }
                continue;
            }
            if (!biomes.contains(selector)) {
                biomes.add(selector);
            }
        }
        List<DimensionSelector> dimensions = new ArrayList<>(profile.dimensions().size());
        DimensionSelector firstInvalidDimension = null;
        for (DimensionSelector selector : profile.dimensions()) {
            if (selector.kind() == DimensionSelector.Kind.INVALID) {
                if (firstInvalidDimension == null) {
                    firstInvalidDimension = selector;
                }
                continue;
            }
            if (!dimensions.contains(selector)) {
                dimensions.add(selector);
            }
        }
        // 原列表非空但全部写错：保留一个非法项，让列表仍然「有限制」，
        // 否则过滤后列表为空会被当成「不限制」，笔误反而变成全群系/全维度生成。
        // INVALID 选择器的 test 恒为 false，所以结果就是该档案永远不匹配。
        boolean biomeAllInvalid = biomes.isEmpty() && firstInvalidBiome != null;
        if (biomeAllInvalid) {
            biomes.add(firstInvalidBiome);
        }
        boolean dimensionAllInvalid = dimensions.isEmpty() && firstInvalidDimension != null;
        if (dimensionAllInvalid) {
            dimensions.add(firstInvalidDimension);
        }
        StormProfile result = new StormProfile(weight, profile.natural(), speed,
                new Range(minRadius, maxRadius), profile.vertical(), List.copyOf(stages), profile.environment(),
                List.copyOf(biomes), List.copyOf(dimensions), profile.skylight());
        if (biomeAllInvalid) {
            Localizedrain.LOGGER.warn("[localizedrain] storm profile has no valid biomes selectors; it will never spawn in any biome");
        } else if (firstInvalidBiome != null) {
            Localizedrain.LOGGER.warn("[localizedrain] storm profile has invalid biomes selectors; ignored them");
        }
        if (dimensionAllInvalid) {
            Localizedrain.LOGGER.warn("[localizedrain] storm profile has no valid dimensions selectors; it will never spawn in any dimension");
        } else if (firstInvalidDimension != null) {
            Localizedrain.LOGGER.warn("[localizedrain] storm profile has invalid dimensions selectors; ignored them");
        }
        if (!result.equals(profile)) {
            if (emptyStages) {
                Localizedrain.LOGGER.warn("[localizedrain] storm profile has no stages; added a default rain stage");
            } else {
                Localizedrain.LOGGER.warn(
                        "[localizedrain] storm profile values were clamped to a valid range; check its JSON (stages={}, radius=[{}, {}])",
                        stages.size(), minRadius, maxRadius);
            }
        }
        return DataResult.success(result);
    }

    /** 是否有任一阶段的天气类型允许落雷；无天光维度据此拒绝雷暴档案（不只检查 stage 0）。 */
    public boolean hasThunderStage(ServerLevel level) {
        for (Stage stage : this.stages) {
            if (WeatherTypes.byId(level, stage.type()).allowsThunder()) {
                return true;
            }
        }
        return false;
    }

    /** 是否显式声明了允许生成的生物群系列表。 */
    public boolean restrictsBiomes() {
        return !this.biomes.isEmpty();
    }

    /** 显式 dimensions 列表是否允许该维度；列表为空表示不限维度（任何已注册维度都行）。 */
    public boolean allowsDimension(Level level) {
        if (this.dimensions.isEmpty()) {
            return true;
        }
        for (DimensionSelector selector : this.dimensions) {
            if (selector.test(level)) {
                return true;
            }
        }
        return false;
    }

    /** 天光要求：{@code REQUIRED} 只允许有天光维度，{@code FORBIDDEN} 只允许无天光维度。 */
    public boolean allowsSkylight(Level level) {
        boolean skylit = level.dimensionType().hasSkyLight();
        return switch (this.skylight) {
            case ANY -> true;
            case REQUIRED -> skylit;
            case FORBIDDEN -> !skylit;
        };
    }

    /** 显式 biomes 列表是否允许该群系；列表为空时返回 true（交给默认筛选）。 */
    public boolean allowsBiome(Holder<Biome> biome) {
        if (this.biomes.isEmpty()) {
            return true;
        }
        for (StormBiomeSelector selector : this.biomes) {
            if (selector.test(biome)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 没有显式 biomes 时使用的默认生物群系筛选：会下雨的 {@link VerticalMode#SURFACE} 风暴
     * 只落在会降水的群系里；洞穴/整列档案不受此限制。
     */
    /**
     * 档案没有显式 biomes 列表时的默认筛选。
     *
     * <p>判断依据是「天气类型是否有降水能力」（{@link WeatherType#hasPrecipitation()}），不是阶段里的
     * rain 强度：纯降雪档案即使 rain = 0 也应该只落在会降水的群系；纯特效云团即使写了 rain &gt; 0
     * 也不该被错误地限制到降水群系。</p>
     */
    public boolean defaultBiomeAllowed(ServerLevel level, Holder<Biome> biome) {
        if (this.vertical != VerticalMode.SURFACE) {
            return true;
        }
        for (Stage stage : this.stages) {
            WeatherType type = WeatherTypes.resolve(level, stage.type());
            if (type != null && type.hasPrecipitation()) {
                return biome.value().hasPrecipitation();
            }
        }
        return true;
    }

    /** 两个端点各自夹到 [low, high]，保留顺序（min &gt; max 表示递减）。 */
    private static Range clamp(Range range, double low, double high) {
        double min = Math.max(low, Math.min(high, range.min()));
        double max = Math.max(low, Math.min(high, range.max()));
        return new Range(min, max);
    }

    private static float clamp01(float value) {
        return Math.max(0.0F, Math.min(1.0F, value));
    }

    /** 取指定阶段的强度区间；索引越界时钳到末阶段。 */
    public Stage stage(int index) {
        return this.stages.get(Math.min(Math.max(index, 0), this.stages.size() - 1));
    }
}
