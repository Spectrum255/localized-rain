package com.spectrum255.localizedrain.storm;

import com.spectrum255.localizedrain.Config;
import com.spectrum255.localizedrain.api.WindState;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;

/** 维度级风场：所有自然风暴共享。 */
public final class WindField {
    /** 风场速度硬上限（方块/tick）：10 × 20 = 200 格/秒，避免 API/命令把风暴推出世界。 */
    public static final double MAX_SPEED = 10.0D;

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
        if (!Double.isFinite(velocityX) || !Double.isFinite(velocityZ)) {
            return;
        }
        double speed = Math.hypot(velocityX, velocityZ);
        if (speed > MAX_SPEED) {
            double scale = MAX_SPEED / speed;
            velocityX *= scale;
            velocityZ *= scale;
        }
        this.velocityX = velocityX;
        this.velocityZ = velocityZ;
    }

    /**
     * 每隔 windChangeIntervalTicks 调整一次：方向无期望值，首次随机、之后在当前角度上 ± 浮动；
     * 速度首次取初始值，之后在当前速度上浮动；若当前速度偏离初始值超过上限，则暂停随机浮动，
     * 按 windReturnRate 的比例逐步拉回初始值，回到范围内后重新开始浮动。
     * 每次浮动的「幅度」在 [min, max] 配置区间内均匀取值，正负号随机。
     */
    public void tick(ServerLevel level) {
        long now = level.getGameTime();
        if (now < this.nextChangeTick) {
            return;
        }
        int interval = intConfig(Config.WIND_CHANGE_INTERVAL_TICKS, 1200);
        this.nextChangeTick = now + Math.max(20, interval);
        RandomSource random = level.getRandom();

        double initialSpeed = Math.max(0.0D, doubleConfig(Config.WIND_INITIAL_SPEED, 10.0D)) / 20.0D;
        double deviationLimit = Math.max(0.0D, doubleConfig(Config.WIND_SPEED_DEVIATION_LIMIT, 5.0D)) / 20.0D;
        double returnRate = Mth.clamp(doubleConfig(Config.WIND_RETURN_RATE, 0.25D), 0.0D, 1.0D);
        double speedVariationMin = Math.max(0.0D, doubleConfig(Config.WIND_SPEED_VARIATION_MIN, 1.0D)) / 20.0D;
        double speedVariationMax = Math.max(0.0D, doubleConfig(Config.WIND_SPEED_VARIATION_MAX, 3.0D)) / 20.0D;
        double directionVariationMin = Math.toRadians(
                Math.max(0.0D, doubleConfig(Config.WIND_DIRECTION_VARIATION_MIN, 10.0D)));
        double directionVariationMax = Math.toRadians(
                Math.max(0.0D, doubleConfig(Config.WIND_DIRECTION_VARIATION_MAX, 30.0D)));

        double currentSpeed = Math.hypot(this.velocityX, this.velocityZ);
        if (!Double.isFinite(currentSpeed)) {
            currentSpeed = 0.0D;   // 手动 set/读档留下的坏值：当作无风，重新起风
        }
        double angle;
        if (currentSpeed < 1.0E-6D) {
            // 世界（维度）第一次起风：方向随机，速度取初始值
            angle = random.nextDouble() * Math.PI * 2.0D;
            currentSpeed = initialSpeed;
        } else {
            angle = Math.atan2(this.velocityZ, this.velocityX);
            double deviation = currentSpeed - initialSpeed;
            if (deviationLimit > 0.0D && Math.abs(deviation) > deviationLimit) {
                // 偏离过大：按比例向初始值回归，不再叠加随机浮动；returnRate 为 0 时至少吸附一次
                currentSpeed = returnRate > 0.0D ? currentSpeed + deviation * returnRate : initialSpeed;
            } else {
                double variation = sample(random, speedVariationMin, speedVariationMax);
                currentSpeed = Mth.clamp(
                        currentSpeed + (random.nextBoolean() ? variation : -variation),
                        0.0D, MAX_SPEED);
            }
        }
        double directionVariation = sample(random, directionVariationMin, directionVariationMax);
        angle += random.nextBoolean() ? directionVariation : -directionVariation;
        this.velocityX = Math.cos(angle) * currentSpeed;
        this.velocityZ = Math.sin(angle) * currentSpeed;
    }

    /** 在 [min, max] 内均匀采样（自动交换顺序），用于浮动幅度的随机取值。 */
    private static double sample(RandomSource random, double min, double max) {
        double lo = Math.min(min, max);
        double hi = Math.max(min, max);
        return lo + random.nextDouble() * (hi - lo);
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
        double velocityX = tag.getDouble("vx");
        double velocityZ = tag.getDouble("vz");
        double speed = Math.hypot(velocityX, velocityZ);
        if (!Double.isFinite(velocityX) || !Double.isFinite(velocityZ) || speed > MAX_SPEED * 1.001D) {
            // 存档被手改/旧版本写坏：归零并立刻重抽，否则 NaN 会一路污染 atan2 与所有自然风暴坐标
            wind.nextChangeTick = gameTime;
            return wind;
        }
        wind.set(velocityX, velocityZ);
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
