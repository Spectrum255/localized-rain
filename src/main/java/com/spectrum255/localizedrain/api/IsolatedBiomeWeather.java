package com.spectrum255.localizedrain.api;

import net.minecraft.network.chat.Component;
import net.minecraft.util.StringRepresentable;
import net.neoforged.neoforge.common.TranslatableEnum;

/**
 * {@code #localizedrain:storm_isolated} 群系在移动模式下的天气处理方式。
 *
 * <p>与 {@link WeatherMode} 一样实现 {@link StringRepresentable}，配置界面会渲染成按钮；
 * {@link #id()} 是写进同步包与旧配置文件的稳定字符串。</p>
 */
public enum IsolatedBiomeWeather implements StringRepresentable, TranslatableEnum {
    /** 始终晴天。 */
    CLEAR("clear"),
    /** 这些片继续走群系片天气机。 */
    PATCH("patch"),
    /** 除非命令/API 设置，否则晴天。 */
    MANUAL("manual");

    private final String id;

    IsolatedBiomeWeather(String id) {
        this.id = id;
    }

    @Override
    public String getSerializedName() {
        return this.id;
    }

    @Override
    public Component getTranslatedName() {
        return Component.translatable("localizedrain.configuration.isolatedBiomeWeather." + name());
    }

    /** 同步包与旧配置文件使用的字符串形式。 */
    public String id() {
        return this.id;
    }
}
