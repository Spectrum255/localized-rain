package com.spectrum255.localizedrain.network;

import com.spectrum255.localizedrain.Localizedrain;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 服务端 -> 客户端：玩家当前所在群系片的「目标」天气状态，不做平滑。
 *
 * <p>原版的 START/STOP + 雨量/雷量包为了跨区域时天空和雨声有约 5 秒渐变，发的是按 tick
 * 0.01 逼近的显示值；HUD（Xaero 天气行）需要的是即时目标值，所以单独用这个包通知。</p>
 *
 * <p>{@code typeKey} 是自定义天气类型的显示键（{@code WeatherType#displayKey()}），
 * 内置类型与晴天为空串——此时 HUD 按原版雨/雷布尔显示。{@code typeId} 是完整的类型 id，
 * 供客户端视觉层读取粒子/音效定义；{@code intensity} 是 0–1 的天气「展示强度」，
 * 无降水类型（雾、沙尘暴）也会给出非零值。</p>
 */
public record WeatherStatePayload(boolean raining, boolean thundering, String typeKey,
                                  String typeId, float intensity)
        implements CustomPacketPayload {
    public static final Type<WeatherStatePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Localizedrain.MODID, "weather_state"));
    public static final StreamCodec<RegistryFriendlyByteBuf, WeatherStatePayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.BOOL, WeatherStatePayload::raining,
                    ByteBufCodecs.BOOL, WeatherStatePayload::thundering,
                    ByteBufCodecs.STRING_UTF8, WeatherStatePayload::typeKey,
                    ByteBufCodecs.STRING_UTF8, WeatherStatePayload::typeId,
                    ByteBufCodecs.FLOAT, WeatherStatePayload::intensity,
                    WeatherStatePayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
