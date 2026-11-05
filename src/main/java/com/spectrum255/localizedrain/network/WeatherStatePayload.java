package com.spectrum255.localizedrain.network;

import com.spectrum255.localizedrain.Localizedrain;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 服务端 -> 客户端：玩家当前所在群系片的「目标」天气布尔值，不做平滑。
 *
 * <p>原版的 START/STOP + 雨量/雷量包为了跨区域时天空和雨声有约 5 秒渐变，发的是按 tick
 * 0.01 逼近的显示值；HUD（Xaero 天气行）需要的是即时目标值，所以单独用这个包通知。</p>
 */
public record WeatherStatePayload(boolean raining, boolean thundering) implements CustomPacketPayload {
    public static final Type<WeatherStatePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Localizedrain.MODID, "weather_state"));
    public static final StreamCodec<RegistryFriendlyByteBuf, WeatherStatePayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.BOOL, WeatherStatePayload::raining,
                    ByteBufCodecs.BOOL, WeatherStatePayload::thundering,
                    WeatherStatePayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
