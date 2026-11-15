package com.spectrum255.localizedrain.storm;

import com.spectrum255.localizedrain.Config;
import com.spectrum255.localizedrain.api.WindState;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;

/** 维度级风场：所有自然风暴共享。 */
public final class WindField {
    private double velocityX;
    private double velocityZ;
    private long nextChangeTick;

    public double velocityX() {
        return this.velocityX;
    }

    public double velocityZ() {
        return this.velocityZ;
    }

    public WindState state() {
        return new WindState(this.velocityX, this.velocityZ);
    }

    public void set(double velocityX, double velocityZ) {
        this.velocityX = velocityX;
        this.velocityZ = velocityZ;
    }

    /** 到达间隔就做一次有界随机游走；模长始终钳制在 [windMinSpeed, windMaxSpeed]。 */
    public void tick(ServerLevel level) {
        long now = level.getGameTime();
        if (now < this.nextChangeTick) {
            return;
        }
        int interval = intConfig(Config.WIND_CHANGE_INTERVAL_TICKS, 1200);
        this.nextChangeTick = now + Math.max(20, interval);
        RandomSource random = level.getRandom();
        double min = doubleConfig(Config.WIND_MIN_SPEED, 0.02D);
        double max = Math.max(min, doubleConfig(Config.WIND_MAX_SPEED, 0.08D));
        double currentSpeed = Math.hypot(this.velocityX, this.velocityZ);
        if (currentSpeed < 1.0E-6D) {
            // 初始风场（或档案里速度为 0）：先给一个随机的起始方向与速度
            double angle = random.nextDouble() * Math.PI * 2.0;
            double speed = min + random.nextDouble() * (max - min);
            this.velocityX = Math.cos(angle) * speed;
            this.velocityZ = Math.sin(angle) * speed;
            return;
        }
        // 有界随机游走：方向在 ±30° 内摆动、速度小幅抖动，避免 180° 突变把风暴甩来甩去
        double maxTurn = Math.toRadians(30.0D);
        double angle = Math.atan2(this.velocityZ, this.velocityX)
                + (random.nextDouble() * 2.0D - 1.0D) * maxTurn;
        double speed = Mth.clamp(currentSpeed + (random.nextDouble() * 2.0D - 1.0D) * 0.01D, min, max);
        this.velocityX = Math.cos(angle) * speed;
        this.velocityZ = Math.sin(angle) * speed;
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putDouble("vx", this.velocityX);
        tag.putDouble("vz", this.velocityZ);
        tag.putLong("next", this.nextChangeTick);
        return tag;
    }

    public static WindField load(CompoundTag tag, long gameTime) {
        WindField wind = new WindField();
        wind.velocityX = tag.getDouble("vx");
        wind.velocityZ = tag.getDouble("vz");
        wind.nextChangeTick = tag.contains("next") ? tag.getLong("next") : gameTime;
        return wind;
    }

    /** 配置可能在服务器启动早期未加载，读失败一律用默认值。以下四个 helper 供全模组复用。 */
    public static int intConfig(net.neoforged.neoforge.common.ModConfigSpec.IntValue value, int fallback) {
        try {
            return value.get();
        } catch (IllegalStateException ignored) {
            return fallback;
        }
    }

    public static double doubleConfig(net.neoforged.neoforge.common.ModConfigSpec.DoubleValue value, double fallback) {
        try {
            return value.get();
        } catch (IllegalStateException ignored) {
            return fallback;
        }
    }

    public static boolean boolConfig(net.neoforged.neoforge.common.ModConfigSpec.BooleanValue value, boolean fallback) {
        try {
            return value.get();
        } catch (IllegalStateException ignored) {
            return fallback;
        }
    }

    public static String stringConfig(net.neoforged.neoforge.common.ModConfigSpec.ConfigValue<String> value, String fallback) {
        try {
            return value.get();
        } catch (IllegalStateException ignored) {
            return fallback;
        }
    }

    static float clamp01(float value) {
        return Mth.clamp(value, 0.0F, 1.0F);
    }
}
