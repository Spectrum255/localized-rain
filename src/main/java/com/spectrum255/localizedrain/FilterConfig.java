package com.spectrum255.localizedrain;

import java.util.List;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * 过滤器配置：白名单 / 黑名单 / 强制天气共用一个 {@code config/localizedrain/filters.toml}，
 * 三项都在文件根级（不再嵌套 section，配置界面直接显示三条列表，少一层入口）。
 * 与核心调试配置 {@code common.toml}、天气参数 {@code weather.toml} 分开；和 weather.toml 一样是
 * SERVER 配置：{@code config/localizedrain/filters.toml} 是全局默认，存档内
 * {@code serverconfig/localizedrain/filters.toml} 是该存档的独立副本。
 *
 * <p>条目格式：{@code <群系选择器>=<天气>}；群系选择器支持 {@code minecraft:desert}、
 * {@code minecraft:*}（命名空间）与 {@code #minecraft:is_ocean}（群系标签）；天气支持类型 id、
 * {@code clear}/{@code rain}/{@code thunder} 短名，白/黑名单额外支持 {@code #标签}。</p>
 */
public final class FilterConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    /** 白名单：命中的群系只会循环列出的天气；为空时使用默认自然池。 */
    public static final ModConfigSpec.ConfigValue<List<? extends String>> BIOME_WEATHER_WHITELIST;

    /** 黑名单：命中的群系永远不会出现列出的天气，但仍会在剩余天气里循环。 */
    public static final ModConfigSpec.ConfigValue<List<? extends String>> BIOME_WEATHER_BLACKLIST;

    /** 强制天气：命中的群系始终保持该种天气；同一群系多条命中时取第一条。 */
    public static final ModConfigSpec.ConfigValue<List<? extends String>> BIOME_WEATHER_OVERRIDES;

    static final ModConfigSpec FILTERS_SPEC;

    static {
        BIOME_WEATHER_WHITELIST = BUILDER.comment("Weather whitelist: biomes only cycle the weather listed here.",
                        "Format: '<biome selector>=<weather>', one entry per weather, for example",
                        "'minecraft:plains=clear' and 'minecraft:plains=localizedrain:rain'.",
                        "A matching biome will never get any weather that is not listed here.",
                        "Weather may be a type id, clear/rain/thunder, or a weather type tag like",
                        "'#localizedrain:dry'; the built-in short names map to built-in types.",
                        "Empty = use the default natural pool (#localizedrain:natural, or built-in clear/rain/thunder).",
                        "Biome selectors: 'minecraft:desert', 'minecraft:*' (namespace), '#minecraft:is_ocean'.")
                .defineListAllowEmpty("weatherBiomeWhitelist", List.of(), () -> "", FilterConfig::validBiomeWeatherEntry);

        BIOME_WEATHER_BLACKLIST = BUILDER.comment("Weather blacklist: biomes never get the weather listed here.",
                        "The biome still cycles through its remaining weather:",
                        "'minecraft:desert=localizedrain:rain' keeps deserts rainless but they may still thunder.",
                        "Format and biome selectors are the same as the whitelist; weather type tags are supported.",
                        "Blacklisted weather is also removed from the whitelist pool.")
                .defineListAllowEmpty("weatherBiomeBlacklist", List.of(), () -> "", FilterConfig::validBiomeWeatherEntry);

        BIOME_WEATHER_OVERRIDES = BUILDER.comment("Forced weather: biomes that always stay in exactly one weather type.",
                        "Format: '<biome selector>=<weather>', for example 'minecraft:desert=clear'.",
                        "Only one weather per biome is supported; when several entries match,",
                        "the FIRST entry (top to bottom) wins. Weather tags are not allowed here.",
                        "The short names clear/rain/thunder map to the built-in types.",
                        "A biome forced to clear never receives weather, not even from commands or the API.")
                .defineListAllowEmpty("weatherBiomeOverrides", List.of(), () -> "", FilterConfig::validBiomeOverride);

        FILTERS_SPEC = BUILDER.build();
    }

    private FilterConfig() {}

    private static boolean validBiomeWeatherEntry(Object entry) {
        return entry instanceof String value && parseWeatherEntry(value, true) != null;
    }

    private static boolean validBiomeOverride(Object entry) {
        return entry instanceof String value && parseWeatherEntry(value, false) != null;
    }

    /**
     * 校验 {@code <群系选择器>=<天气>}，返回选择器（原样）；格式非法返回 {@code null}。
     * 天气类型 id 在判定时惰性解析，这里不查注册表，避免配置加载早于注册表可用时误判。
     */
    private static String parseWeatherEntry(String value, boolean allowTag) {
        int separator = value.lastIndexOf('=');
        if (separator <= 0 || separator == value.length() - 1 || value.substring(0, separator).isBlank()) {
            return null;
        }
        String weather = value.substring(separator + 1).trim();
        if (weather.isEmpty()) {
            return null;
        }
        if (weather.startsWith("#")) {
            return allowTag && weather.length() > 1
                    && ResourceLocation.tryParse(weather.substring(1).trim()) != null
                    ? value.substring(0, separator).trim()
                    : null;
        }
        if (weather.equals("clear") || weather.equals("rain") || weather.equals("thunder")
                || ResourceLocation.tryParse(weather) != null) {
            return value.substring(0, separator).trim();
        }
        return null;
    }
}
