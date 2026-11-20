package com.spectrum255.localizedrain.network;

import com.spectrum255.localizedrain.Localizedrain;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

/**
 * 服务端 → 客户端：天气地图快照。
 *
 * <p>只在玩家打开天气地图（{@code WeatherMapScreen}）时按请求发送，10 tick 一次；
 * 片数量与风暴数量都有上限，解码端逐项校验，避免损坏/恶意数据包把客户端内存打满。</p>
 */
public record WeatherMapPayload(long gameTime, int radius, float windX, float windZ,
                                List<PatchView> patches, List<StormView> storms,
                                List<ForecastView> forecast) implements CustomPacketPayload {
    public static final Type<WeatherMapPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Localizedrain.MODID, "weather_map"));

    /** 一次快照最多的片区数量（512 个 16×16 片 ≈ 直径 1 万方块范围）。 */
    public static final int MAX_PATCHES = 512;
    /** 一次快照最多的风暴数量。 */
    public static final int MAX_STORMS = 64;
    /** 预报最多步数。 */
    public static final int MAX_FORECAST = 8;
    /** 地图半径下限/上限（方块）。 */
    public static final int MIN_RADIUS = 64;
    public static final int MAX_RADIUS = 1024;

    /** 一个 16×16 群系片：packedCenter 是片中心（y=0）的 BlockPos.long 编码。 */
    public record PatchView(long packedCenter, ResourceLocation typeId, float rain, float thunder,
                            int rainTicksLeft, int thunderTicksLeft, boolean forced, boolean locked) {
        public PatchView {
            rain = finite(rain);
            thunder = finite(thunder);
            rainTicksLeft = Math.max(0, rainTicksLeft);
            thunderTicksLeft = Math.max(0, thunderTicksLeft);
        }

        public int x() {
            return BlockPos.getX(this.packedCenter);
        }

        public int z() {
            return BlockPos.getZ(this.packedCenter);
        }
    }

    /** 一个风暴快照：客户端只用于画圆与列表，不做逻辑运算。 */
    public record StormView(UUID id, double x, double z, double velocityX, double velocityZ, double radius,
                            ResourceLocation typeId, float rain, float thunder,
                            int stageIndex, int stageCount) {
        public StormView {
            x = finite(x);
            z = finite(z);
            velocityX = finite(velocityX);
            velocityZ = finite(velocityZ);
            radius = Mth.clamp(finite(radius), 1.0D, 4096.0D);
            rain = Mth.clamp(finite(rain), 0.0F, 1.0F);
            thunder = Mth.clamp(finite(thunder), 0.0F, 1.0F);
            stageCount = Math.max(1, stageCount);
            stageIndex = Mth.clamp(stageIndex, 0, stageCount - 1);
        }
    }

    /** 预报一步：inTicks 后变成 typeId（强度为该类型目标雨量）。 */
    public record ForecastView(int inTicks, ResourceLocation typeId, float intensity) {
        public ForecastView {
            inTicks = Math.max(0, inTicks);
            intensity = Mth.clamp(finite(intensity), 0.0F, 1.0F);
        }
    }

    public static final StreamCodec<RegistryFriendlyByteBuf, WeatherMapPayload> STREAM_CODEC = StreamCodec.of(
            WeatherMapPayload::encode, WeatherMapPayload::decode);

    private static void encode(RegistryFriendlyByteBuf buf, WeatherMapPayload payload) {
        buf.writeLong(payload.gameTime());
        buf.writeVarInt(payload.radius());
        buf.writeFloat(payload.windX());
        buf.writeFloat(payload.windZ());

        buf.writeVarInt(payload.patches().size());
        for (PatchView patch : payload.patches()) {
            buf.writeLong(patch.packedCenter());
            buf.writeResourceLocation(patch.typeId());
            buf.writeFloat(patch.rain());
            buf.writeFloat(patch.thunder());
            buf.writeVarInt(patch.rainTicksLeft());
            buf.writeVarInt(patch.thunderTicksLeft());
            buf.writeBoolean(patch.forced());
            buf.writeBoolean(patch.locked());
        }

        buf.writeVarInt(payload.storms().size());
        for (StormView storm : payload.storms()) {
            buf.writeUUID(storm.id());
            buf.writeDouble(storm.x());
            buf.writeDouble(storm.z());
            buf.writeDouble(storm.velocityX());
            buf.writeDouble(storm.velocityZ());
            buf.writeDouble(storm.radius());
            buf.writeResourceLocation(storm.typeId());
            buf.writeFloat(storm.rain());
            buf.writeFloat(storm.thunder());
            buf.writeVarInt(storm.stageIndex());
            buf.writeVarInt(storm.stageCount());
        }

        buf.writeVarInt(payload.forecast().size());
        for (ForecastView step : payload.forecast()) {
            buf.writeVarInt(step.inTicks());
            buf.writeResourceLocation(step.typeId());
            buf.writeFloat(step.intensity());
        }
    }

    private static WeatherMapPayload decode(RegistryFriendlyByteBuf buf) {
        long gameTime = buf.readLong();
        int radius = Mth.clamp(buf.readVarInt(), MIN_RADIUS, MAX_RADIUS);
        float windX = finite(buf.readFloat());
        float windZ = finite(buf.readFloat());

        int patchCount = readCount(buf, MAX_PATCHES, "patch");
        List<PatchView> patches = new ArrayList<>(Math.min(patchCount, 128));
        for (int index = 0; index < patchCount; index++) {
            patches.add(new PatchView(buf.readLong(), buf.readResourceLocation(), buf.readFloat(),
                    buf.readFloat(), buf.readVarInt(), buf.readVarInt(), buf.readBoolean(), buf.readBoolean()));
        }

        int stormCount = readCount(buf, MAX_STORMS, "storm");
        List<StormView> storms = new ArrayList<>(Math.min(stormCount, 16));
        for (int index = 0; index < stormCount; index++) {
            storms.add(new StormView(buf.readUUID(), buf.readDouble(), buf.readDouble(), buf.readDouble(),
                    buf.readDouble(), buf.readDouble(), buf.readResourceLocation(), buf.readFloat(),
                    buf.readFloat(), buf.readVarInt(), buf.readVarInt()));
        }

        int forecastCount = readCount(buf, MAX_FORECAST, "forecast");
        List<ForecastView> forecast = new ArrayList<>(forecastCount);
        for (int index = 0; index < forecastCount; index++) {
            forecast.add(new ForecastView(buf.readVarInt(), buf.readResourceLocation(), buf.readFloat()));
        }
        return new WeatherMapPayload(gameTime, radius, windX, windZ, List.copyOf(patches),
                List.copyOf(storms), List.copyOf(forecast));
    }

    private static int readCount(RegistryFriendlyByteBuf buf, int max, String what) {
        int count = buf.readVarInt();
        if (count < 0 || count > max) {
            throw new io.netty.handler.codec.DecoderException(what + " count out of range: " + count);
        }
        return count;
    }

    private static double finite(double value) {
        return Double.isFinite(value) ? value : 0.0D;
    }

    private static float finite(float value) {
        return Float.isFinite(value) ? value : 0.0F;
    }

    @Override
    public Type<WeatherMapPayload> type() {
        return TYPE;
    }
}
