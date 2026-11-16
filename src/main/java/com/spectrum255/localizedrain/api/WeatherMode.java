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

    /**
     * 大小写不敏感的解析入口：接受小写 serialized name（{@code "mobile"}）与
     * 大写常量名（{@code "MOBILE"}）。配置读写走 NeoForge 的 {@code EnumValue}，
     * 正常情况下由 {@code EnumGetMethod.NAME_IGNORECASE} 处理；此方法用于防御性读取
     * 以及未来从字符串配置迁移时复用。
     */
    public static WeatherMode parse(String input) {
        if (input == null) {
            return PATCHES;
        }
        for (WeatherMode mode : values()) {
            if (mode.name.equals(input)) {
                return mode;
            }
        }
        for (WeatherMode mode : values()) {
            if (mode.name().equalsIgnoreCase(input)) {
                return mode;
            }
        }
        return PATCHES;
    }

    /** 配置界面循环按钮显示的文本；没有 {@code TranslatableEnum} 时 NeoForge 会显示枚举常量名。 */
    @Override
    public Component getTranslatedName() {
        return Component.translatable("localizedrain.configuration.weatherMode." + this.name());
    }

    /** 配置加载/重载与服务器启动时清空，避免热路径反复读配置。 */
    private static volatile WeatherMode cached;

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
            if (mode != null) {
                return mode;
            }
            return PATCHES;
        } catch (IllegalStateException ignored) {
            // 配置尚未加载：按默认值推进，避免服务器启动早期抛异常
            return PATCHES;
        }
    }
}
