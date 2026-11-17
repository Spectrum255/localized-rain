package com.spectrum255.localizedrain.api;

import com.spectrum255.localizedrain.Config;
import java.util.Optional;
import net.minecraft.network.chat.Component;
import net.minecraft.util.StringRepresentable;
import net.neoforged.neoforge.common.TranslatableEnum;

/**
 * 天气接管模式。支持运行期热切换：{@code /weather mode} 命令、配置界面改动、以及直接编辑
 * 存档的 {@code serverconfig/localizedrain/weather.toml} 都会在约 1 秒内生效。
 */
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
        return strictParse(input).orElse(PATCHES);
    }

    /** 与 {@link #parse(String)} 相同，但未知/空值返回 empty，便于调用方报错而不是静默回退。 */
    public static Optional<WeatherMode> strictParse(String input) {
        if (input == null) {
            return Optional.empty();
        }
        String value = input.trim();
        for (WeatherMode mode : values()) {
            if (mode.name.equals(value)) {
                return Optional.of(mode);
            }
        }
        for (WeatherMode mode : values()) {
            if (mode.name().equalsIgnoreCase(value)) {
                return Optional.of(mode);
            }
        }
        return Optional.empty();
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

    /**
     * 运行期切换：立即更新缓存，并尝试把新值写进当前配置（配置未加载时只改缓存）。
     * 真正负责通知客户端的重推逻辑在 {@code WeatherManager.tick} 检测到模式变化时执行。
     */
    public static void apply(WeatherMode mode) {
        if (mode == null) {
            return;
        }
        cached = mode;
        try {
            Config.WEATHER_MODE.set(mode);
        } catch (IllegalStateException ignored) {
            // 配置尚未加载（例如数据包注册阶段调用）：只更新内存缓存
        }
    }

    /**
     * 轮询配置界面/文件改动：读一次当前配置值与缓存比对，变化时更新缓存并返回 {@code true}。
     * 由 {@code WeatherManager.tick} 每 20 tick 调用。
     */
    public static boolean poll() {
        WeatherMode configured;
        try {
            configured = Config.WEATHER_MODE.get();
        } catch (IllegalStateException ignored) {
            return false;
        }
        if (configured == null || configured == cached) {
            return false;
        }
        cached = configured;
        return true;
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
