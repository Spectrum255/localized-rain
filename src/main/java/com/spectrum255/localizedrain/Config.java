package com.spectrum255.localizedrain;

import java.util.List;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.common.ModConfigSpec;

public final class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.IntValue MIN_CLEAR_TICKS = BUILDER.defineInRange("minClearTicks", 12000, 20, 720000);
    public static final ModConfigSpec.IntValue MAX_CLEAR_TICKS = BUILDER.defineInRange("maxClearTicks", 168000, 20, 720000);
    public static final ModConfigSpec.IntValue MIN_RAIN_TICKS = BUILDER.defineInRange("minRainTicks", 12000, 20, 720000);
    public static final ModConfigSpec.IntValue MAX_RAIN_TICKS = BUILDER.defineInRange("maxRainTicks", 24000, 20, 720000);
    public static final ModConfigSpec.IntValue MIN_THUNDER_DELAY_TICKS = BUILDER.defineInRange("minThunderDelayTicks", 12000, 20, 720000);
    public static final ModConfigSpec.IntValue MAX_THUNDER_DELAY_TICKS = BUILDER.defineInRange("maxThunderDelayTicks", 180000, 20, 720000);
    public static final ModConfigSpec.IntValue MIN_THUNDER_TICKS = BUILDER.defineInRange("minThunderTicks", 3600, 20, 720000);
    public static final ModConfigSpec.IntValue MAX_THUNDER_TICKS = BUILDER.defineInRange("maxThunderTicks", 15600, 20, 720000);

    /**
     * 已加载区块的天气扫描间隔（tick）。1 = 每 tick 推进，20 = 每秒推进一次。
     * 每次扫描会把每片推进「间隔」个 tick，所以天气总速率不变，只改变推进粒度。
     */
    public static final ModConfigSpec.IntValue WEATHER_SIMULATION_INTERVAL_TICKS =
            BUILDER.comment("How often (in ticks) biome patches in loaded chunks are advanced.",
                            "Each sweep advances every patch by this many ticks, so the overall weather rate is unchanged.",
                            "1 = every tick, 20 = once per second.")
                    .defineInRange("weatherSimulationIntervalTicks", 20, 1, 200);

    /**
     * 是否同时扫描已加载区块里的 3D 群系子状态（洞穴群系、浮岛/平流层等）。
     * 开启时每个非空 section 都会采样一次；关闭则只按每个区块中心列的地表位置判定。
     */
    public static final ModConfigSpec.BooleanValue SCAN_3D_SUBSTATES =
            BUILDER.comment("Also advance weather for cave / floating-island biome sub-states inside loaded chunks.",
                            "Every non-empty 16-block section of a loaded chunk is sampled once per sweep.",
                            "Disable to scan only each chunk's surface column.")
                    .define("scan3dSubstates", true);

    /**
     * 存档清理：超过这么多游戏内天没有被推进的天气状态会被删除。0 = 不按时间清理（仍有条数上限）。
     * 一个游戏内天 = 24000 tick。
     */
    public static final ModConfigSpec.IntValue STATE_RETENTION_DAYS =
            BUILDER.comment("Delete stored weather states that have not been advanced for this many in-game days.",
                            "0 = keep them forever (the size cap below still applies).",
                            "One in-game day is 24000 ticks (20 minutes of real time).")
                    .defineInRange("stateRetentionDays", 7, 0, 365);

    /** 每个维度的天气状态条数上限；超出时按 lastTick 从旧到新删除。 */
    public static final ModConfigSpec.IntValue MAX_STORED_STATES =
            BUILDER.comment("Hard cap on stored weather states per dimension.",
                            "When exceeded, least recently advanced states are removed first.")
                    .defineInRange("maxStoredStates", 4096, 256, 65536);

    /**
     * 段并查集父表的硬上限（每个维度）。父表会随探索过的段增长，
     * 超过上限时清理会收缩「无引用、无状态」的节点，并逐出对应列缓存。
     * 默认值与 {@link #MAX_STORED_STATES}（4096）保持约 4× 的比例：
     * 太高会让大世界探索后频繁触发第三遍批量收缩。
     */
    public static final ModConfigSpec.IntValue MAX_PARENT_ENTRIES =
            BUILDER.comment("Hard cap on stored biome-segment parent entries per dimension.",
                            "The parent map is pruned down to this size when it grows beyond it;",
                            "evicting a column makes it re-register on the next scan.")
                    .defineInRange("maxParentEntries", 16384, 4096, 1000000);

    /** 每 tick 最多扫描多少个新的 16×16 列（3D 片探测）。 */
    public static final ModConfigSpec.IntValue MAX_COLUMN_SCANS_PER_TICK =
            BUILDER.comment("How many new 16x16 columns may be scanned per tick for 3D biome patches.",
                            "Each column costs a vertical biome sampling pass; lower values reduce",
                            "first-visit hitching at the cost of slower patch discovery.")
                    .defineInRange("maxColumnScansPerTick", 4, 1, 64);

    /** 调试日志：输出列扫描、分量合并与玩家所在片的解析结果。 */
    public static final ModConfigSpec.BooleanValue DEBUG_LOGGING =
            BUILDER.comment("Log 3D patch diagnostics: column scans, component merges and the player's resolved patch.",
                            "Useful for debugging weather boundaries; keep disabled in normal play.")
                    .define("debugLogging", false);

    /**
     * 白名单：命中的群系自然循环只会在列出的天气里抽取。
     * 格式 {@code <群系选择器>=<天气>}；天气可以写类型 id、{@code clear}/{@code rain}/{@code thunder} 短名，
     * 或 {@code #标签}（天气类型标签）。为空时用默认自然池（{@code #localizedrain:natural} 标签，
     * 标签不存在时为内置晴/雨/雷）。
     */
    public static final ModConfigSpec.ConfigValue<List<? extends String>> BIOME_WEATHER_WHITELIST =
            BUILDER.comment("Weather each matching biome may cycle through (whitelist).",
                            "Format: '<biome selector>=<weather>', one entry per weather, for example",
                            "'minecraft:plains=clear' and 'minecraft:plains=localizedrain:rain'.",
                            "A matching biome will never get any weather that is not listed here.",
                            "Weather may be a type id, clear/rain/thunder, or a weather type tag like",
                            "'#localizedrain:dry'; the built-in short names map to built-in types.",
                            "Empty = use the default natural pool (#localizedrain:natural, or built-in clear/rain/thunder).",
                            "Biome selectors: 'minecraft:desert', 'minecraft:*' (namespace), '#minecraft:is_ocean'.")
                    .defineListAllowEmpty("weatherBiomeWhitelist", List.of(), () -> "", Config::validBiomeWeatherEntry);

    /**
     * 黑名单：命中的群系永远不会出现列出的天气，但仍会在剩余天气里循环。
     * 例如 {@code minecraft:desert=localizedrain:rain} 让沙漠不下雨，晴天/雷暴仍可能出现。
     */
    public static final ModConfigSpec.ConfigValue<List<? extends String>> BIOME_WEATHER_BLACKLIST =
            BUILDER.comment("Weather each matching biome never gets (blacklist).",
                            "The biome still cycles through its remaining weather:",
                            "'minecraft:desert=localizedrain:rain' keeps deserts rainless but they may still thunder.",
                            "Format and biome selectors are the same as the whitelist; weather type tags are supported.",
                            "Blacklisted weather is also removed from the whitelist pool.")
                    .defineListAllowEmpty("weatherBiomeBlacklist", List.of(), () -> "", Config::validBiomeWeatherEntry);

    /**
     * 强制某些生物群系始终处于指定天气。
     * 同一群系只支持一种天气；多条规则命中时按配置顺序取<b>第一条</b>。
     * 天气可以写任意已注册类型（例如附属模组的 {@code examplemod:sandstorm}），也可写短名
     * {@code clear}/{@code rain}/{@code thunder}；类型 id 在判定时惰性解析，不要求配置加载顺序。
     */
    public static final ModConfigSpec.ConfigValue<List<? extends String>> BIOME_WEATHER_OVERRIDES =
            BUILDER.comment("Biomes that always stay in exactly one weather type.",
                            "Format: '<biome selector>=<weather>', for example 'minecraft:desert=clear'.",
                            "Only one weather per biome is supported; when several entries match,",
                            "the FIRST entry (top to bottom) wins. Weather tags are not allowed here.",
                            "The short names clear/rain/thunder map to the built-in types.",
                            "A biome forced to clear never receives weather, not even from commands or the API.")
                    .defineListAllowEmpty("weatherBiomeOverrides", List.of(), () -> "", Config::validBiomeOverride);

    // ---- 移动天气模式（仅 weatherMode=mobile 时生效） ----

    public static final ModConfigSpec.ConfigValue<String> WEATHER_MODE =
            BUILDER.comment("Weather system mode. Requires a game restart.",
                            "patches = existing 3D biome-patch weather",
                            "mobile  = moving storms with wind, evolution and 3D coverage",
                            "vanilla = do not take over vanilla weather at all")
                    .defineInList("weatherMode", "patches", List.of("patches", "mobile", "vanilla"));

    public static final ModConfigSpec.ConfigValue<String> ISOLATED_BIOME_WEATHER =
            BUILDER.comment("Weather inside biomes tagged #localizedrain:storm_isolated (mobile mode only).",
                            "clear  = always clear",
                            "patch  = keep running the biome-patch weather machine for those patches",
                            "manual = clear unless set by command/API")
                    .defineInList("isolatedBiomeWeather", "clear", List.of("clear", "patch", "manual"));

    public static final ModConfigSpec.BooleanValue NATURAL_CAVE_STORMS =
            BUILDER.comment("Allow storms to spawn naturally underground (mobile mode only).")
                    .define("naturalCaveStorms", false);

    public static final ModConfigSpec.IntValue CAVE_STORM_RADIUS =
            BUILDER.comment("Radius of naturally spawned cave storms, in blocks.")
                    .defineInRange("caveStormRadius", 64, 8, 512);

    public static final ModConfigSpec.IntValue MAX_STORMS_PER_DIMENSION =
            BUILDER.comment("Maximum number of natural storms per dimension.")
                    .defineInRange("maxStormsPerDimension", 8, 0, 64);

    public static final ModConfigSpec.IntValue MAX_STORMS_PER_PLAYER =
            BUILDER.comment("Maximum number of natural storms considered to belong to one player.")
                    .defineInRange("maxStormsPerPlayer", 2, 0, 16);

    public static final ModConfigSpec.IntValue MAX_MANUAL_STORMS_PER_DIMENSION =
            BUILDER.comment("Maximum number of manual storms per dimension (commands/API).",
                            "0 disables manual storms.")
                    .defineInRange("maxManualStormsPerDimension", 16, 0, 128);

    public static final ModConfigSpec.IntValue STORM_SPAWN_INTERVAL_TICKS =
            BUILDER.comment("How often (in ticks) a dimension attempts to spawn one natural storm.")
                    .defineInRange("spawnIntervalTicks", 200, 20, 24000);

    public static final ModConfigSpec.IntValue STORM_SPAWN_MIN_DISTANCE =
            BUILDER.comment("Minimum spawn distance from the chosen player, in blocks.")
                    .defineInRange("spawnMinDistance", 300, 32, 4096);

    public static final ModConfigSpec.IntValue STORM_SPAWN_MAX_DISTANCE =
            BUILDER.comment("Maximum spawn distance from the chosen player, in blocks.")
                    .defineInRange("spawnMaxDistance", 700, 32, 8192);

    public static final ModConfigSpec.IntValue WIND_CHANGE_INTERVAL_TICKS =
            BUILDER.comment("How often (in ticks) the dimension wind direction is re-rolled.")
                    .defineInRange("windChangeIntervalTicks", 1200, 20, 240000);

    public static final ModConfigSpec.DoubleValue WIND_MIN_SPEED =
            BUILDER.comment("Minimum wind speed in blocks per tick.")
                    .defineInRange("windMinSpeed", 0.02D, 0.0D, 1.0D);

    public static final ModConfigSpec.DoubleValue WIND_MAX_SPEED =
            BUILDER.comment("Maximum wind speed in blocks per tick.")
                    .defineInRange("windMaxSpeed", 0.08D, 0.0D, 1.0D);

    public static final ModConfigSpec.IntValue STORM_DESPAWN_DISTANCE =
            BUILDER.comment("A natural storm this far from every player starts its despawn grace timer.")
                    .defineInRange("despawnDistance", 1000, 128, 100000);

    public static final ModConfigSpec.IntValue STORM_DESPAWN_GRACE_TICKS =
            BUILDER.comment("How long a natural storm must stay too far away before it fades out.")
                    .defineInRange("despawnGraceTicks", 600, 0, 24000);

    public static final ModConfigSpec.IntValue MANUAL_STORM_RADIUS =
            BUILDER.comment("Radius used when a manual storm is created from a single position (setWeatherAt).")
                    .defineInRange("manualStormRadius", 128, 16, 2048);

    public static final ModConfigSpec.IntValue VANILLA_COMMAND_STORM_RADIUS =
            BUILDER.comment("Default radius for /weather storm global when no radius argument is given.",
                            "Vanilla /weather rain|thunder no longer uses this; profiles define their own radius range.")
                    .defineInRange("vanillaCommandStormRadius", 512, 64, 4096);

    public static final ModConfigSpec.IntValue STORM_SYNC_INTERVAL_TICKS =
            BUILDER.comment("How often (in ticks) the full storm list is re-sent to clients as a correction.")
                    .defineInRange("stormSyncIntervalTicks", 40, 5, 1200);

    public static final ModConfigSpec.DoubleValue STORM_FALLOFF_RATIO =
            BUILDER.comment("Width of the intensity falloff band, as a fraction of the storm radius.")
                    .defineInRange("falloffRatio", 0.3D, 0.05D, 1.0D);

    public static final ModConfigSpec.DoubleValue STORM_LIGHTNING_MULTIPLIER =
            BUILDER.comment("Lightning frequency multiplier for storms.")
                    .defineInRange("lightningMultiplier", 1.0D, 0.0D, 20.0D);

    static final ModConfigSpec SPEC = BUILDER.build();

    private Config() {}

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
