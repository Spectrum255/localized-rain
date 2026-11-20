package com.spectrum255.localizedrain.client;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * 客户端表现配置（{@code config/localizedrain/client.toml}）。
 *
 * <p>这些键只影响本机渲染与声音，不会同步到服务端；专用服务器上不存在该文件。</p>
 */
public final class ClientWeatherConfig {
    public static final ModConfigSpec SPEC;

    private static final ModConfigSpec.IntValue WEATHER_TRANSITION_TICKS;
    private static final ModConfigSpec.DoubleValue INDOOR_VISUAL_FACTOR;
    private static final ModConfigSpec.DoubleValue INDOOR_SOUND_FACTOR;
    private static final ModConfigSpec.BooleanValue XAERO_WEATHER_OVERLAY;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        builder.translation("localizedrain.configuration.visual").push("visual");
        WEATHER_TRANSITION_TICKS = builder
                .comment("Client-side fade duration (in ticks) when the weather type or its layers change.",
                        "0 disables the fade and switches the visuals instantly.")
                .defineInRange("weatherTransitionTicks", 40, 0, 200);
        INDOOR_VISUAL_FACTOR = builder
                .comment("Fraction of weather visuals (fog, particles, sky coverage) kept when the camera cannot see the sky.",
                        "0 = fully indoors (no fog/particles/sky override), 1 = indoors looks like outdoors.")
                .defineInRange("indoorVisualFactor", 0.0D, 0.0D, 1.0D);
        INDOOR_SOUND_FACTOR = builder
                .comment("Fraction of weather ambient sound volume kept when the camera cannot see the sky.",
                        "0.35 approximates vanilla's muffled rain heard from inside a house.")
                .defineInRange("indoorSoundFactor", 0.35D, 0.0D, 1.0D);
        XAERO_WEATHER_OVERLAY = builder
                .comment("Replace Xaero's Minimap built-in weather info line with this mod's local weather.",
                        "Only used when Xaero's Minimap is installed; takes effect on next login or game restart.")
                .define("xaeroWeatherOverlay", true);
        builder.pop();
        SPEC = builder.build();
    }

    private ClientWeatherConfig() {}

    /** 天气切换的渐变时长（tick）；0 = 立即切换。 */
    public static int transitionTicks() {
        try {
            return WEATHER_TRANSITION_TICKS.get();
        } catch (IllegalStateException exception) {
            return 40;
        }
    }

    /** 室内保留的视觉比例（0–1）。 */
    public static float indoorVisualFactor() {
        try {
            return INDOOR_VISUAL_FACTOR.get().floatValue();
        } catch (IllegalStateException exception) {
            return 0.0F;
        }
    }

    /** 室内保留的环境音音量比例（0–1）。 */
    public static float indoorSoundFactor() {
        try {
            return INDOOR_SOUND_FACTOR.get().floatValue();
        } catch (IllegalStateException exception) {
            return 0.35F;
        }
    }

    /** 是否替换 Xaero 小地图的天气行。 */
    public static boolean xaeroWeatherOverlay() {
        try {
            return XAERO_WEATHER_OVERLAY.get();
        } catch (IllegalStateException exception) {
            return true;
        }
    }
}
