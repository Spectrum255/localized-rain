package com.spectrum255.localizedrain.api;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.spectrum255.localizedrain.Localizedrain;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.StringRepresentable;

/** 数据驱动风暴档案：形状、阶段序列与环境调制参数。JSON 路径 data/<ns>/storm_profile/<id>.json。 */
public record StormProfile(int weight, boolean natural, double speedMultiplier, Range radius,
                           VerticalMode vertical, List<Stage> stages, Environment environment) {

    /** 竖直判定模式。 */
    public enum VerticalMode implements StringRepresentable {
        SURFACE("surface"), BAND("band"), COLUMN("column");

        public static final Codec<VerticalMode> CODEC = StringRepresentable.fromEnum(VerticalMode::values);
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
                .comapFlatMap(list -> list.size() == 2
                                ? DataResult.success(new Range(list.get(0), list.get(1)))
                                : DataResult.error(() -> "expected [min, max], got " + list.size() + " values"),
                        range -> List.of(range.min(), range.max()));

        public float lerp(float delta) {
            return (float) (this.min + (this.max - this.min) * delta);
        }
    }

    /** 单个阶段。阈值字段缺省表示该档案不启用对应阈值。 */
    public record Stage(ResourceLocation type, int minTicks, int maxTicks, Range rain, Range thunder,
                        Range radiusScale, Optional<Float> advanceWhenMoisture, Optional<Float> dieWhenMoisture) {
        public static final Codec<Stage> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                ResourceLocation.CODEC.fieldOf("type").forGetter(Stage::type),
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

    private static final Codec<StormProfile> RECORD_CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.INT.optionalFieldOf("weight", 10).forGetter(StormProfile::weight),
            Codec.BOOL.optionalFieldOf("natural", true).forGetter(StormProfile::natural),
            Codec.DOUBLE.optionalFieldOf("speed_multiplier", 1.0D).forGetter(StormProfile::speedMultiplier),
            Range.CODEC.optionalFieldOf("radius", new Range(150.0D, 300.0D)).forGetter(StormProfile::radius),
            VerticalMode.CODEC.optionalFieldOf("vertical", VerticalMode.SURFACE).forGetter(StormProfile::vertical),
            Stage.CODEC.listOf().fieldOf("stages").forGetter(StormProfile::stages),
            Environment.CODEC.optionalFieldOf("environment", new Environment(true, 0.002D, 0.001D))
                    .forGetter(StormProfile::environment)
    ).apply(instance, StormProfile::new));

    /**
     * 读档时把越界数值修正到可用范围，而不是报错：
     * 数据包加载器对 codec error 会中止整个世界加载（不是跳过单个档案）。区间允许 min &gt; max，
     * 表示该阶段内数值从 min 递减到 max（强度渐变、半径收缩）。只有「没有任何阶段」才报错。
     */
    public static final Codec<StormProfile> CODEC = RECORD_CODEC.flatXmap(StormProfile::sanitize, DataResult::success);

    private static DataResult<StormProfile> sanitize(StormProfile profile) {
        if (profile.stages().isEmpty()) {
            return DataResult.error(() -> "storm profile must contain at least one stage");
        }
        boolean changed = false;
        List<Stage> stages = new ArrayList<>(profile.stages().size());
        for (Stage stage : profile.stages()) {
            int minTicks = Math.max(1, stage.minTicks());
            int maxTicks = Math.max(minTicks, stage.maxTicks());
            Range rain = clamp(stage.rain(), 0.0D, 1.0D);
            Range thunder = clamp(stage.thunder(), 0.0D, 1.0D);
            Range scale = clamp(stage.radiusScale(), 0.0D, 4.0D);
            Optional<Float> advance = stage.advanceWhenMoisture().map(value -> clamp01(value));
            Optional<Float> die = stage.dieWhenMoisture().map(value -> clamp01(value));
            if (minTicks != stage.minTicks() || maxTicks != stage.maxTicks()
                    || !rain.equals(stage.rain()) || !thunder.equals(stage.thunder())
                    || !scale.equals(stage.radiusScale())
                    || !advance.equals(stage.advanceWhenMoisture()) || !die.equals(stage.dieWhenMoisture())) {
                changed = true;
                stages.add(new Stage(stage.type(), minTicks, maxTicks, rain, thunder, scale, advance, die));
            } else {
                stages.add(stage);
            }
        }
        double minRadius = Math.max(1.0D, profile.radius().min());
        double maxRadius = Math.max(minRadius, profile.radius().max());
        int weight = Math.max(1, profile.weight());
        double speed = Math.max(0.01D, profile.speedMultiplier());
        StormProfile result = new StormProfile(weight, profile.natural(), speed,
                new Range(minRadius, maxRadius), profile.vertical(), List.copyOf(stages), profile.environment());
        if (!result.equals(profile)) {
            Localizedrain.LOGGER.warn(
                    "[localizedrain] storm profile values were clamped to a valid range; check its JSON (stages={}, radius=[{}, {}])",
                    stages.size(), minRadius, maxRadius);
        }
        return DataResult.success(result);
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
