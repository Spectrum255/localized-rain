package com.spectrum255.localizedrain.network;

import com.spectrum255.localizedrain.Localizedrain;
import com.spectrum255.localizedrain.storm.StormTuning;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

/** 服务端 → 客户端的风暴全量快照（40 tick 一次校正 + 生成/移除/换阶段立即推送）。
 * 尾部的 {@code isolatedMode}/{@code falloffRatio} 让客户端只使用服务端配置，避免联机时 COMMON 配置不同步。 */
public record StormSyncPayload(List<StormEntry> storms, String isolatedMode, double falloffRatio, boolean active)
        implements CustomPacketPayload {
    public static final Type<StormSyncPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Localizedrain.MODID, "storm_sync"));

    /** 服务端消亡淡出固定 tick 数；常量在 `storm.StormTuning`，客户端据此把强度平滑到 0。 */
    public static final int FADE_TICKS = StormTuning.FADE_TICKS;

    /** 一次快照里允许的最大风暴数；正常玩法远达不到，超过即视为损坏或恶意数据包。 */
    public static final int MAX_STORMS = 4096;

    /** 客户端渲染只需要这些字段，不发档案与阶段计时。 */
    public record StormEntry(UUID id, double x, double z, double radius,
                             double velocityX, double velocityZ,
                             double minY, double maxY,
                             ResourceLocation typeId,
                             boolean hasPrecipitation, boolean allowsThunder,
                             float rain, float thunder,
                             boolean manual, boolean dying, int dyingTicksLeft) {

        public StormEntry {
            // 解码端统一净化：NaN 会经客户端外推写进雨量并永久卡住，直到服务端下次全量快照
            x = finite(x);
            z = finite(z);
            velocityX = finite(velocityX);
            velocityZ = finite(velocityZ);
            minY = finite(minY);
            maxY = Math.max(minY, finite(maxY));
            radius = Mth.clamp(finite(radius), 1.0D, 4096.0D);
            rain = Mth.clamp(finite(rain), 0.0F, 1.0F);
            thunder = Mth.clamp(finite(thunder), 0.0F, 1.0F);
            dyingTicksLeft = Mth.clamp(dyingTicksLeft, 0, FADE_TICKS);
        }

        private static double finite(double value) {
            return Double.isFinite(value) ? value : 0.0D;
        }

        private static float finite(float value) {
            return Float.isFinite(value) ? value : 0.0F;
        }
    }

    public static final StreamCodec<RegistryFriendlyByteBuf, StormEntry> ENTRY_CODEC = StreamCodec.of(
            (buf, entry) -> {
                buf.writeUUID(entry.id());
                buf.writeDouble(entry.x());
                buf.writeDouble(entry.z());
                buf.writeDouble(entry.radius());
                buf.writeDouble(entry.velocityX());
                buf.writeDouble(entry.velocityZ());
                buf.writeDouble(entry.minY());
                buf.writeDouble(entry.maxY());
                buf.writeResourceLocation(entry.typeId());
                buf.writeBoolean(entry.hasPrecipitation());
                buf.writeBoolean(entry.allowsThunder());
                buf.writeFloat(entry.rain());
                buf.writeFloat(entry.thunder());
                buf.writeBoolean(entry.manual());
                buf.writeBoolean(entry.dying());
                buf.writeVarInt(entry.dyingTicksLeft());
            },
            buf -> new StormEntry(buf.readUUID(), buf.readDouble(), buf.readDouble(), buf.readDouble(),
                    buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readDouble(),
                    buf.readResourceLocation(), buf.readBoolean(), buf.readBoolean(),
                    buf.readFloat(), buf.readFloat(),
                    buf.readBoolean(), buf.readBoolean(), buf.readVarInt()));

    public static final StreamCodec<RegistryFriendlyByteBuf, StormSyncPayload> STREAM_CODEC = StreamCodec.of(
            (buf, payload) -> {
                buf.writeBoolean(payload.active());
                buf.writeVarInt(payload.storms().size());
                for (StormEntry entry : payload.storms()) {
                    ENTRY_CODEC.encode(buf, entry);
                }
                buf.writeUtf(payload.isolatedMode(), 16);
                buf.writeDouble(payload.falloffRatio());
            },
            buf -> {
                boolean active = buf.readBoolean();
                int size = buf.readVarInt();
                // 数量来自对端：不能直接拿去 ArrayList 预分配，否则恶意/损坏数据包可让客户端 OOM
                if (size < 0 || size > MAX_STORMS) {
                    throw new io.netty.handler.codec.DecoderException("storm count out of range: " + size);
                }
                List<StormEntry> storms = new ArrayList<>(Math.min(size, 64));
                for (int index = 0; index < size; index++) {
                    storms.add(ENTRY_CODEC.decode(buf));
                }
                // 与写侧 writeUtf(..., 16) 对称：isolatedMode 只有 clear/manual/patch 三个值
                String isolatedMode = buf.readUtf(16);
                double falloff = buf.readDouble();
                return new StormSyncPayload(storms, isolatedMode,
                        Double.isFinite(falloff) ? Mth.clamp(falloff, 0.0D, 1.0D) : 0.3D, active);
            });

    @Override
    public Type<StormSyncPayload> type() {
        return TYPE;
    }
}
