package com.spectrum255.localizedrain.client;

import com.spectrum255.localizedrain.network.WeatherStatePayload;
import java.util.List;

/**
 * 客户端缓存的天气叠加层：服务端 {@link WeatherStatePayload#layers()} 的不可变副本。
 *
 * <p>列表为空表示「没有叠加信息」，视觉层回落到 {@link ClientWeatherTargets} 的单层语义
 * （片模式、或尚未收到第一条状态包）。</p>
 */
public final class ClientWeatherLayers {
    private static volatile List<WeatherStatePayload.Layer> layers = List.of();

    private ClientWeatherLayers() {}

    public static void set(List<WeatherStatePayload.Layer> value) {
        layers = value == null || value.isEmpty() ? List.of() : List.copyOf(value);
    }

    public static List<WeatherStatePayload.Layer> layers() {
        return layers;
    }

    public static boolean isEmpty() {
        return layers.isEmpty();
    }

    public static void clear() {
        layers = List.of();
    }
}
