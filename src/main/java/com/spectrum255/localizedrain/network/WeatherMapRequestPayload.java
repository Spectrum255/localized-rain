package com.spectrum255.localizedrain.network;

import com.spectrum255.localizedrain.Localizedrain;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** 客户端 → 服务端：请求一份天气地图快照（空包，服务端限流 10 tick）。 */
public record WeatherMapRequestPayload() implements CustomPacketPayload {
    public static final WeatherMapRequestPayload INSTANCE = new WeatherMapRequestPayload();

    public static final Type<WeatherMapRequestPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Localizedrain.MODID, "weather_map_request"));

    public static final StreamCodec<RegistryFriendlyByteBuf, WeatherMapRequestPayload> STREAM_CODEC =
            StreamCodec.unit(INSTANCE);

    @Override
    public Type<WeatherMapRequestPayload> type() {
        return TYPE;
    }
}
