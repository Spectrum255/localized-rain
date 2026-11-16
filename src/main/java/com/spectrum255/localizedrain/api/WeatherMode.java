package com.spectrum255.localizedrain.api;

import com.spectrum255.localizedrain.Config;
import net.minecraft.network.chat.Component;
import net.minecraft.util.StringRepresentable;
import net.neoforged.neoforge.common.TranslatableEnum;

/** 天气接管模式。只能通过配置文件切换，运行期不做热切换。 */
public enum WeatherMode implements StringRepresentable, TranslatableEnum {
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

    /** 配置界面循环按钮显示的文本；没有 {@code TranslatableEnum} 时 NeoForge 会显示枚举常量名。 */
    @Override
    public Component getTranslatedName() {
        return Component.translatable("localizedrain.configuration.weatherMode." + this.name());
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
        try {
            WeatherMode mode = Config.WEATHER_MODE.get();
            return mode == null ? PATCHES : mode;
        } catch (IllegalStateException ignored) {
            // 配置尚未加载：按默认值推进，避免服务器启动早期抛异常
            return PATCHES;
        }
    }
}
