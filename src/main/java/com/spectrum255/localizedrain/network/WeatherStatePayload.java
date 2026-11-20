package com.spectrum255.localizedrain.network;

import com.spectrum255.localizedrain.Localizedrain;
import io.netty.handler.codec.DecoderException;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

/**
 * 服务端 -> 客户端：玩家当前天气的「目标」状态，不做平滑。
 *
 * <p>原版的 START/STOP + 雨量/雷量包为了跨区域时天空和雨声有约 5 秒渐变，发的是按 tick
 * 0.01 逼近的显示值；HUD（Xaero 天气行）需要的是即时目标值，所以单独用这个包通知。</p>
 *
 * <p>{@code typeKey} 是主导层自定义天气类型的显示键（{@code WeatherType#displayKey()}），
 * 内置类型与晴天为空串——此时 HUD 按原版雨/雷布尔显示。{@code typeId} 是主导层的完整类型 id。
 * {@code intensity} 是 0–1 的展示强度，无降水类型（雾、沙尘暴）也会给出非零值。</p>
 *
 * <p>{@code layers} 是完整叠加层（移动模式最多 8 层，片模式为空表示回落到单层语义）。
 * 客户端视觉层按层渲染粒子/雾，Xaero 天气行按层显示多行。</p>
 */
public record WeatherStatePayload(boolean raining, boolean thundering, String typeKey,
                                  String typeId, float intensity, List<Layer> layers)
        implements CustomPacketPayload {
    /** 层数上限：与 {@code maxStackedWeatherTypes} 的上限一致，避免恶意/损坏包无界分配。 */
    public static final int MAX_LAYERS = 8;
    /** 单行显示键与类型 id 的长度上限（真实值远小于此，仅用于协议边界）。 */
    private static final int MAX_TEXT = 128;

    public static final Type<WeatherStatePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Localizedrain.MODID, "weather_state"));
    public static final StreamCodec<RegistryFriendlyByteBuf, WeatherStatePayload> STREAM_CODEC =
            StreamCodec.of(WeatherStatePayload::write, WeatherStatePayload::read);

    /** 兼容旧调用点：没有叠加层时只发单层语义。 */
    public WeatherStatePayload(boolean raining, boolean thundering, String typeKey,
                               String typeId, float intensity) {
        this(raining, thundering, typeKey, typeId, intensity, List.of());
    }

    /** 单层：typeKey 为空表示原版内置类型，typeId 始终是真实 id。 */
    public record Layer(String typeKey, String typeId, float intensity) {
        public Layer {
            typeKey = clampText(typeKey);
            typeId = clampText(typeId);
            intensity = Mth.clamp(Float.isFinite(intensity) ? intensity : 0.0F, 0.0F, 1.0F);
        }
    }

    /** 写侧兜底：注册表条目若仍带超长键（旧存档/NBT 恢复的实例），编码前截断而不是抛异常。 */
    private static String clampText(String value) {
        if (value == null) {
            return "";
        }
        return value.length() > MAX_TEXT ? value.substring(0, MAX_TEXT) : value;
    }

    private static void write(RegistryFriendlyByteBuf buf, WeatherStatePayload payload) {
        buf.writeBoolean(payload.raining());
        buf.writeBoolean(payload.thundering());
        buf.writeUtf(clampText(payload.typeKey()), MAX_TEXT);
        buf.writeUtf(clampText(payload.typeId()), MAX_TEXT);
        buf.writeFloat(Mth.clamp(Float.isFinite(payload.intensity()) ? payload.intensity() : 0.0F, 0.0F, 1.0F));
        List<Layer> layers = payload.layers();
        int size = Math.min(layers.size(), MAX_LAYERS);
        buf.writeVarInt(size);
        for (int index = 0; index < size; index++) {
            Layer layer = layers.get(index);
            buf.writeUtf(layer.typeKey(), MAX_TEXT);
            buf.writeUtf(layer.typeId(), MAX_TEXT);
            buf.writeFloat(layer.intensity());
        }
    }

    private static WeatherStatePayload read(RegistryFriendlyByteBuf buf) {
        boolean raining = buf.readBoolean();
        boolean thundering = buf.readBoolean();
        String typeKey = buf.readUtf(MAX_TEXT);
        String typeId = buf.readUtf(MAX_TEXT);
        float intensity = buf.readFloat();
        intensity = Mth.clamp(Float.isFinite(intensity) ? intensity : 0.0F, 0.0F, 1.0F);
        int size = buf.readVarInt();
        if (size < 0 || size > MAX_LAYERS) {
            throw new DecoderException("localizedrain:weather_state layer count " + size + " is out of range");
        }
        List<Layer> layers = new ArrayList<>(Math.min(size, MAX_LAYERS));
        for (int index = 0; index < size; index++) {
            layers.add(new Layer(buf.readUtf(MAX_TEXT), buf.readUtf(MAX_TEXT), buf.readFloat()));
        }
        return new WeatherStatePayload(raining, thundering, typeKey, typeId, intensity, List.copyOf(layers));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
