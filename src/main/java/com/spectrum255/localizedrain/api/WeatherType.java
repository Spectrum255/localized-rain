package com.spectrum255.localizedrain.api;

import net.minecraft.world.level.biome.Biome;

/**
 * 天气类型定义（数据部分）。
 *
 * <p>天气类型通过 {@link WeatherTypes#REGISTRY} 注册，附属模组可以注册自己的类型，
 * 再用 {@link WeatherApi#setWeather} 把它应用到某个群系片。天气的“强度”仍然复用
 * 原版的雨量/雷量通道（0–1），渲染和原版兼容逻辑不需要改协议。</p>
 *
 * @param displayKey       名称的翻译键（例如 {@code weather.localizedrain.rain}）
 * @param hasPrecipitation 是否产生降水（影响积雪、炼药锅、粒子判定）
 * @param precipitation    降水形态；{@link Biome.Precipitation#NONE} 表示不产生降水
 * @param allowsThunder    是否允许雷暴与闪电
 */
public record WeatherType(
        String displayKey,
        boolean hasPrecipitation,
        Biome.Precipitation precipitation,
        boolean allowsThunder
) {
    /** 没有降水也没有雷暴的类型（晴天）。 */
    public boolean isClear() {
        return !this.hasPrecipitation && !this.allowsThunder;
    }
}
