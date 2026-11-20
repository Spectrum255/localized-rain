package com.spectrum255.localizedrain.weather;

import com.spectrum255.localizedrain.api.WeatherType;
import net.minecraft.resources.ResourceLocation;

/**
 * 移动模式下同一位置的一个天气层。
 *
 * <p>同一位置被多个风暴覆盖时，只有配置里声明为互相兼容的类型才会各占一层；层内的雨/雷/强度
 * 都是该位置的局部采样值（已经乘过水平衰减与淡出），不是风暴中心值。</p>
 *
 * @param typeId             天气类型 id
 * @param type               已解析的天气类型（非 null）
 * @param rain               该位置的局部雨量（0–1，非降水类型恒 0）
 * @param thunder            该位置的局部雷量（0–1，已按雨量封顶）
 * @param intensity          展示强度（降水类型=rain，非降水类型=水平衰减×淡出）
 * @param canPrecipitateHere 该位置是否满足该风暴的降水可见条件（见天/高度/群系降水）
 * @param affectsPlayers     该层是否参与玩家效果（正在淡出的风暴不施加移速/伤害/药水）
 */
public record WeatherLayer(ResourceLocation typeId, WeatherType type, float rain, float thunder,
                           float intensity, boolean canPrecipitateHere, boolean affectsPlayers) {
    public WeatherLayer {
        if (type == null || typeId == null) {
            throw new IllegalArgumentException("weather layer needs a resolved type");
        }
        rain = clamp(rain);
        thunder = Math.min(clamp(thunder), rain);
        intensity = clamp(intensity);
    }

    private static float clamp(float value) {
        return Float.isFinite(value) ? Math.max(0.0F, Math.min(1.0F, value)) : 0.0F;
    }
}
