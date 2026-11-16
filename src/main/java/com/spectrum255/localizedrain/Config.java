package com.spectrum255.localizedrain;

import com.spectrum255.localizedrain.api.WeatherMode;
import java.util.List;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.common.ModConfigSpec;

public final class Config {
    /** 天气/风暴设置：SERVER 配置，每个存档在 `<world>/serverconfig/localizedrain/weather.toml` 下独立。 */
    private static final ModConfigSpec.Builder WEATHER_BUILDER = new ModConfigSpec.Builder();

    /** 调试开关：COMMON 配置，所有存档共用（`config/localizedrain/common.toml`）。 */
    private static final ModConfigSpec.Builder DEBUG_BUILDER = new ModConfigSpec.Builder();

    /**
     * 合法取值。这里用 {@code define} + 显式判空，而不是 {@code defineInList}：
     * NeoForge 校正旧配置文件时会用 {@code null} 调用校验器，而 {@code List.of(...).contains(null)}
     * 会抛 NPE，导致「旧配置缺少新键」时游戏在加载配置阶段崩溃。
     */
    private static final List<String> ISOLATED_WEATHER_MODES = List.of("clear", "patch", "manual");

    public static final ModConfigSpec.IntValue MIN_CLEAR_TICKS = WEATHER_BUILDER.defineInRange("minClearTicks", 12000, 20, 720000);
    public static final ModConfigSpec.IntValue MAX_CLEAR_TICKS = WEATHER_BUILDER.defineInRange("maxClearTicks", 168000, 20, 720000);
    public static final ModConfigSpec.IntValue MIN_RAIN_TICKS = WEATHER_BUILDER.defineInRange("minRainTicks", 12000, 20, 720000);
    public static final ModConfigSpec.IntValue MAX_RAIN_TICKS = WEATHER_BUILDER.defineInRange("maxRainTicks", 24000, 20, 720000);
    public static final ModConfigSpec.IntValue MIN_THUNDER_DELAY_TICKS = WEATHER_BUILDER.defineInRange("minThunderDelayTicks", 12000, 20, 720000);
    public static final ModConfigSpec.IntValue MAX_THUNDER_DELAY_TICKS = WEATHER_BUILDER.defineInRange("maxThunderDelayTicks", 180000, 20, 720000);
    public static final ModConfigSpec.IntValue MIN_THUNDER_TICKS = WEATHER_BUILDER.defineInRange("minThunderTicks", 3600, 20, 720000);
    public static final ModConfigSpec.IntValue MAX_THUNDER_TICKS = WEATHER_BUILDER.defineInRange("maxThunderTicks", 15600, 20, 720000);

    /**
     * 已加载区块的天气扫描间隔（tick）。1 = 每 tick 推进，20 = 每秒推进一次。
     * 每次扫描会把每片推进「间隔」个 tick，所以天气总速率不变，只改变推进粒度。
     */
    public static final ModConfigSpec.IntValue WEATHER_SIMULATION_INTERVAL_TICKS =
            WEATHER_BUILDER.comment("How often (in ticks) biome patches in loaded chunks are advanced.",
                            "Each sweep advances every patch by this many ticks, so the overall weather rate is unchanged.",
                            "1 = every tick, 20 = once per second.")
                    .defineInRange("weatherSimulationIntervalTicks", 20, 1, 200);

    /**
     * 是否同时扫描已加载区块里的 3D 群系子状态（洞穴群系、浮岛/平流层等）。
     * 开启时每个非空 section 都会采样一次；关闭则只按每个区块中心列的地表位置判定。
     */
    public static final ModConfigSpec.BooleanValue SCAN_3D_SUBSTATES =
            WEATHER_BUILDER.comment("Also advance weather for cave / floating-island biome sub-states inside loaded chunks.",
                            "Every non-empty 16-block section of a loaded chunk is sampled once per sweep.",
                            "Disable to scan only each chunk's surface column.")
                    .define("scan3dSubstates", true);

    /**
     * 存档清理：超过这么多游戏内天没有被推进的天气状态会被删除。0 = 不按时间清理（仍有条数上限）。
     * 一个游戏内天 = 24000 tick。
     */
    public static final ModConfigSpec.IntValue STATE_RETENTION_DAYS =
            WEATHER_BUILDER.comment("Delete stored weather states that have not been advanced for this many in-game days.",
                            "0 = keep them forever (the size cap below still applies).",
                            "One in-game day is 24000 ticks (20 minutes of real time).")
                    .defineInRange("stateRetentionDays", 7, 0, 365);

    /** 每个维度的天气状态条数上限；超出时按 lastTick 从旧到新删除。 */
    public static final ModConfigSpec.IntValue MAX_STORED_STATES =
            WEATHER_BUILDER.comment("Hard cap on stored weather states per dimension.",
                            "When exceeded, least recently advanced states are removed first.")
                    .defineInRange("maxStoredStates", 4096, 256, 65536);

    /**
     * 段并查集父表的硬上限（每个维度）。父表会随探索过的段增长，
     * 超过上限时清理会收缩「无引用、无状态」的节点，并逐出对应列缓存。
     * 默认值与 {@link #MAX_STORED_STATES}（4096）保持约 4× 的比例：
     * 太高会让大世界探索后频繁触发第三遍批量收缩。
     */
    public static final ModConfigSpec.IntValue MAX_PARENT_ENTRIES =
            WEATHER_BUILDER.comment("Hard cap on stored biome-segment parent entries per dimension.",
                            "The parent map is pruned down to this size when it grows beyond it;",
                            "evicting a column makes it re-register on the next scan.")
                    .defineInRange("maxParentEntries", 16384, 4096, 1000000);

    /** 每 tick 最多扫描多少个新的 16×16 列（3D 片探测）。 */
    public static final ModConfigSpec.IntValue MAX_COLUMN_SCANS_PER_TICK =
            WEATHER_BUILDER.comment("How many new 16x16 columns may be scanned per tick for 3D biome patches.",
                            "Each column costs a vertical biome sampling pass; lower values reduce",
                            "first-visit hitching at the cost of slower patch discovery.")
                    .defineInRange("maxColumnScansPerTick", 4, 1, 64);

    /** 调试日志：输出列扫描、分量合并与玩家所在片的解析结果。 */
    public static final ModConfigSpec.BooleanValue DEBUG_LOGGING =
            DEBUG_BUILDER.comment("Log 3D patch diagnostics: column scans, component merges and the player's resolved patch.",
                            "Useful for debugging weather boundaries; keep disabled in normal play.")
                    .define("debugLogging", false);

    /** 调试日志：每个片天气变化时输出位置、群系与类型。 */
    public static final ModConfigSpec.BooleanValue LOG_WEATHER_CHANGES =
            DEBUG_BUILDER.comment("Log every biome patch weather change (type / raining / thundering),",
                            "including the patch position, biome and the previous weather signature.",
                            "Can be noisy while many patches cycle; enable only for debugging.")
                    .define("logWeatherChanges", false);

    /** 调试日志：风暴生成时的详细参数。 */
    public static final ModConfigSpec.BooleanValue LOG_STORM_SPAWNS =
            DEBUG_BUILDER.comment("Log a detailed line (dimension, id, profile, position, radius, vertical range,",
                            "stage, weather type, intensity, velocity, manual flag and lifetime) whenever a storm spawns.")
                    .define("logStormSpawns", true);

    // 过滤规则（白名单 / 黑名单 / 强制天气）已拆分到 config/localizedrain/ 下的独立文件，
    // 见 FilterConfig：whitelist.toml、blacklist.toml、overrides.toml。

    // ---- 移动天气模式（仅 weatherMode=mobile 时生效） ----

    public static final ModConfigSpec.EnumValue<WeatherMode> WEATHER_MODE =
            WEATHER_BUILDER.comment("Weather system mode. Applies at runtime within about a second (or use /weather mode).",
                            "patches = existing 3D biome-patch weather",
                            "mobile  = moving storms with wind, evolution and 3D coverage",
                            "vanilla = do not take over vanilla weather at all")
                    .defineEnum("weatherMode", WeatherMode.PATCHES);

    public static final ModConfigSpec.ConfigValue<String> ISOLATED_BIOME_WEATHER =
            WEATHER_BUILDER.comment("Weather inside biomes tagged #localizedrain:storm_isolated (mobile mode only).",
                            "clear  = always clear",
                            "patch  = keep running the biome-patch weather machine for those patches",
                            "manual = clear unless set by command/API")
                    .define("isolatedBiomeWeather", "clear",
                            value -> value instanceof String mode && ISOLATED_WEATHER_MODES.contains(mode));

    public static final ModConfigSpec.BooleanValue NATURAL_CAVE_STORMS =
            WEATHER_BUILDER.comment("Allow storms to spawn naturally underground (mobile mode only).")
                    .define("naturalCaveStorms", false);

    public static final ModConfigSpec.IntValue CAVE_STORM_RADIUS =
            WEATHER_BUILDER.comment("Radius of naturally spawned cave storms, in blocks.")
                    .defineInRange("caveStormRadius", 64, 8, 512);

    public static final ModConfigSpec.IntValue MAX_STORMS_PER_DIMENSION =
            WEATHER_BUILDER.comment("Maximum number of natural storms per dimension.")
                    .defineInRange("maxStormsPerDimension", 8, 0, 64);

    public static final ModConfigSpec.IntValue MAX_STORMS_PER_PLAYER =
            WEATHER_BUILDER.comment("Maximum number of natural storms considered to belong to one player.")
                    .defineInRange("maxStormsPerPlayer", 2, 0, 16);

    public static final ModConfigSpec.IntValue MAX_MANUAL_STORMS_PER_DIMENSION =
            WEATHER_BUILDER.comment("Maximum number of manual storms per dimension (commands/API).",
                            "0 disables manual storms.")
                    .defineInRange("maxManualStormsPerDimension", 16, 0, 128);

    public static final ModConfigSpec.IntValue STORM_SPAWN_INTERVAL_TICKS =
            WEATHER_BUILDER.comment("How often (in ticks) a dimension attempts to spawn one natural storm.")
                    .defineInRange("spawnIntervalTicks", 200, 20, 24000);

    public static final ModConfigSpec.IntValue STORM_SPAWN_MIN_DISTANCE =
            WEATHER_BUILDER.comment("Minimum spawn distance from the chosen player, in blocks.")
                    .defineInRange("spawnMinDistance", 300, 32, 4096);

    public static final ModConfigSpec.IntValue STORM_SPAWN_MAX_DISTANCE =
            WEATHER_BUILDER.comment("Maximum spawn distance from the chosen player, in blocks.",
                            "The effective maximum is clamped to the current simulation distance, so the",
                            "probe chunk is loaded; with server-side simulation distance 12 this is 160 blocks.")
                    .defineInRange("spawnMaxDistance", 700, 32, 8192);

    public static final ModConfigSpec.DoubleValue NATURAL_STORM_SPAWN_CHANCE =
            WEATHER_BUILDER.comment("Chance that a scheduled natural storm spawn attempt actually spawns a storm.",
                            "Attempts happen every spawnIntervalTicks (default 200 ticks = 10 s).",
                            "0.035 is about one natural storm every 4.8 minutes, roughly 3x the old observed rate;",
                            "0 disables natural spawning, 1 spawns on every attempt.")
                    .defineInRange("naturalStormSpawnChance", 0.035D, 0.0D, 1.0D);

    public static final ModConfigSpec.IntValue WIND_CHANGE_INTERVAL_TICKS =
            WEATHER_BUILDER.comment("How often (in ticks) the dimension wind direction is re-rolled.")
                    .defineInRange("windChangeIntervalTicks", 1200, 20, 240000);

    public static final ModConfigSpec.DoubleValue WIND_MIN_SPEED =
            WEATHER_BUILDER.comment("Minimum wind speed in blocks per tick.")
                    .defineInRange("windMinSpeed", 0.02D, 0.0D, 1.0D);

    public static final ModConfigSpec.DoubleValue WIND_MAX_SPEED =
            WEATHER_BUILDER.comment("Maximum wind speed in blocks per tick.")
                    .defineInRange("windMaxSpeed", 0.08D, 0.0D, 1.0D);

    public static final ModConfigSpec.IntValue STORM_DESPAWN_DISTANCE =
            WEATHER_BUILDER.comment("A natural storm this far from every player starts its despawn grace timer.")
                    .defineInRange("despawnDistance", 1000, 128, 100000);

    public static final ModConfigSpec.IntValue STORM_DESPAWN_GRACE_TICKS =
            WEATHER_BUILDER.comment("How long a natural storm must stay too far away before it fades out.")
                    .defineInRange("despawnGraceTicks", 600, 0, 24000);

    public static final ModConfigSpec.IntValue STORM_SYNC_INTERVAL_TICKS =
            WEATHER_BUILDER.comment("How often (in ticks) the full storm list is re-sent to clients as a correction.")
                    .defineInRange("stormSyncIntervalTicks", 40, 5, 1200);

    public static final ModConfigSpec.DoubleValue STORM_FALLOFF_RATIO =
            WEATHER_BUILDER.comment("Width of the intensity falloff band, as a fraction of the storm radius.")
                    .defineInRange("falloffRatio", 0.3D, 0.05D, 1.0D);

    public static final ModConfigSpec.DoubleValue STORM_LIGHTNING_MULTIPLIER =
            WEATHER_BUILDER.comment("Lightning frequency multiplier for storms.")
                    .defineInRange("lightningMultiplier", 1.0D, 0.0D, 20.0D);

    /** 全局（所有存档共用）的调试开关。 */
    static final ModConfigSpec COMMON_SPEC = DEBUG_BUILDER.build();

    /** 每个存档独立的天气/风暴设置。 */
    static final ModConfigSpec WEATHER_SPEC = WEATHER_BUILDER.build();

    private Config() {}
}
