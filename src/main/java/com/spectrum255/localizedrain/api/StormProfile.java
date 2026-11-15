package com.spectrum255.localizedrain.api;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
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

    /** 非法档案返回 DataResult.error：数据包加载器会记错误并跳过该档案，服务器继续启动。 */
    public static final Codec<StormProfile> CODEC = RECORD_CODEC.flatXmap(StormProfile::validate, DataResult::success);

    private static DataResult<StormProfile> validate(StormProfile profile) {
        if (profile.stages().isEmpty()) {
            return DataResult.error(() -> "stages must contain at least one entry");
        }
        if (profile.radius().min() <= 0 || profile.radius().max() < profile.radius().min()) {
            return DataResult.error(() -> "radius must satisfy 0 < min <= max");
        }
        if (profile.weight() < 1) {
            return DataResult.error(() -> "weight must be >= 1");
        }
        for (Stage stage : profile.stages()) {
            if (stage.minTicks() <= 0 || stage.maxTicks() < stage.minTicks()) {
                return DataResult.error(() -> "stage " + stage.type() + " must satisfy 0 < min_ticks <= max_ticks");
            }
            for (Range range : List.of(stage.rain(), stage.thunder(), stage.radiusScale())) {
                if (range.min() < 0 || range.max() < range.min()) {
                    return DataResult.error(() -> "range of stage " + stage.type() + " must satisfy 0 <= min <= max");
                }
            }
        }
        return DataResult.success(profile);
    }

    /** 取指定阶段的强度区间；索引越界时钳到末阶段。 */
    public Stage stage(int index) {
        return this.stages.get(Math.min(Math.max(index, 0), this.stages.size() - 1));
    }
}
