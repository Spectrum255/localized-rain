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

/** 服务端 → 客户端的风暴全量快照（40 tick 一次校正 + 生成/移除/换阶段立即推送）。
 * 尾部的 {@code isolatedMode}/{@code falloffRatio} 让客户端只使用服务端配置，避免联机时 COMMON 配置不同步。 */
public record StormSyncPayload(List<StormEntry> storms, String isolatedMode, double falloffRatio)
        implements CustomPacketPayload {
    public static final Type<StormSyncPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Localizedrain.MODID, "storm_sync"));

    /** 服务端消亡淡出固定 tick 数；常量在 `storm.StormTuning`，客户端据此把强度平滑到 0。 */
    public static final int FADE_TICKS = StormTuning.FADE_TICKS;

    /** 客户端渲染只需要这些字段，不发档案与阶段计时。 */
    public record StormEntry(UUID id, double x, double z, double radius,
                             double velocityX, double velocityZ,
                             double minY, double maxY,
                             ResourceLocation typeId,
                             boolean hasPrecipitation, boolean allowsThunder,
                             float rain, float thunder,
                             boolean manual, boolean dying, int dyingTicksLeft) {}

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
                buf.writeVarInt(payload.storms().size());
                for (StormEntry entry : payload.storms()) {
                    ENTRY_CODEC.encode(buf, entry);
                }
                buf.writeUtf(payload.isolatedMode());
                buf.writeDouble(payload.falloffRatio());
            },
            buf -> {
                int size = buf.readVarInt();
                List<StormEntry> storms = new ArrayList<>(size);
                for (int index = 0; index < size; index++) {
                    storms.add(ENTRY_CODEC.decode(buf));
                }
                return new StormSyncPayload(storms, buf.readUtf(), buf.readDouble());
            });

    @Override
    public Type<StormSyncPayload> type() {
        return TYPE;
    }
}
