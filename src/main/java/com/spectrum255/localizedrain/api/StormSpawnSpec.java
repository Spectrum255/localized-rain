package com.spectrum255.localizedrain.api;

import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

/** 生成参数。所有可选字段为空时取档案默认值。 */
public final class StormSpawnSpec {
    private final ResourceLocation profileId;
    private final Vec3 center;
    private final ResourceLocation typeOverride;
    private final Double radius;
    private final int stage;
    private final Float rainIntensity;
    private final Float thunderIntensity;
    private final double[] velocity;
    private final boolean manual;
    private final int lifetimeTicks;
    private final double[] verticalBand;

    private StormSpawnSpec(Builder builder) {
        this.profileId = builder.profileId;
        this.center = builder.center;
        this.typeOverride = builder.typeOverride;
        this.radius = builder.radius;
        this.stage = builder.stage;
        this.rainIntensity = builder.rainIntensity;
        this.thunderIntensity = builder.thunderIntensity;
        this.velocity = builder.velocity;
        this.manual = builder.manual;
        this.lifetimeTicks = builder.lifetimeTicks;
        this.verticalBand = builder.verticalBand;
    }

    public static Builder builder(ResourceLocation profileId, Vec3 center) {
        return new Builder(profileId, center);
    }

    public ResourceLocation profileId() { return this.profileId; }
    public Vec3 center() { return this.center; }
    /** 覆盖档案阶段天气类型；手动风暴用它让生成瞬间就带上正确类型。 */
    public Optional<ResourceLocation> typeOverride() { return Optional.ofNullable(this.typeOverride); }
    public Optional<Double> radius() { return Optional.ofNullable(this.radius); }
    public int stage() { return this.stage; }
    public Optional<Float> rainIntensity() { return Optional.ofNullable(this.rainIntensity); }
    public Optional<Float> thunderIntensity() { return Optional.ofNullable(this.thunderIntensity); }
    public Optional<double[]> velocity() { return Optional.ofNullable(this.velocity); }
    public boolean manual() { return this.manual; }
    /** -1 = 使用档案阶段时长；> 0 = 单阶段手动风暴，生命周期等于该值。 */
    public int lifetimeTicks() { return this.lifetimeTicks; }
    public Optional<double[]> verticalBand() { return Optional.ofNullable(this.verticalBand); }

    public static final class Builder {
        private final ResourceLocation profileId;
        private final Vec3 center;
        private ResourceLocation typeOverride;
        private Double radius;
        private int stage;
        private Float rainIntensity;
        private Float thunderIntensity;
        private double[] velocity;
        private boolean manual = true;
        private int lifetimeTicks = -1;
        private double[] verticalBand;

        private Builder(ResourceLocation profileId, Vec3 center) {
            this.profileId = profileId;
            this.center = center;
        }

        public Builder typeOverride(ResourceLocation typeId) {
            this.typeOverride = typeId;
            return this;
        }

        public Builder radius(double radius) {
            this.radius = radius;
            return this;
        }

        public Builder stage(int stage) {
            this.stage = Math.max(0, stage);
            return this;
        }

        public Builder rainIntensity(float rain) {
            this.rainIntensity = rain;
            return this;
        }

        public Builder thunderIntensity(float thunder) {
            this.thunderIntensity = thunder;
            return this;
        }

        public Builder velocity(double x, double z) {
            this.velocity = new double[] {x, z};
            return this;
        }

        public Builder manual(boolean manual) {
            this.manual = manual;
            return this;
        }

        public Builder lifetime(int ticks) {
            this.lifetimeTicks = ticks;
            return this;
        }

        public Builder verticalBand(double minY, double maxY) {
            this.verticalBand = new double[] {Math.min(minY, maxY), Math.max(minY, maxY)};
            return this;
        }

        /** 命令层用来判断「已显式给了 BAND，不必按 SURFACE 做维度守卫」。 */
        public boolean hasVerticalBand() {
            return this.verticalBand != null;
        }

        public StormSpawnSpec build() {
            return new StormSpawnSpec(this);
        }
    }
}
