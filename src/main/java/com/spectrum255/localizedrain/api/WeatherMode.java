package com.spectrum255.localizedrain.api;

import com.spectrum255.localizedrain.Config;
import com.spectrum255.localizedrain.Localizedrain;
import net.minecraft.util.StringRepresentable;

/** 天气接管模式。只能通过配置文件切换，运行期不做热切换。 */
public enum WeatherMode implements StringRepresentable {
    PATCHES("patches"),
    MOBILE("mobile"),
    VANILLA("vanilla");

    private final String name;

    WeatherMode(String name) {
        this.name = name;
    }

    @Override
    public String getSerializedName() {
        return this.name;
    }

    /** 配置加载/重载与服务器启动时清空，避免热路径反复读配置。 */
    private static WeatherMode cached;

    /** 当前模式；配置尚未加载或取值非法时回退 {@link #PATCHES}。 */
    public static WeatherMode current() {
        WeatherMode mode = cached;
        if (mode == null) {
            mode = read();
            cached = mode;
        }
        return mode;
    }

    public static void invalidate() {
        cached = null;
    }

    private static WeatherMode read() {
        String value;
        try {
            value = Config.WEATHER_MODE.get();
        } catch (IllegalStateException ignored) {
            // 配置尚未加载：按默认值推进，避免服务器启动早期抛异常
            return PATCHES;
        }
        for (WeatherMode mode : values()) {
            if (mode.name.equals(value)) {
                return mode;
            }
        }
        Localizedrain.LOGGER.warn("[localizedrain] unknown weatherMode '{}', falling back to patches", value);
        return PATCHES;
    }
}
