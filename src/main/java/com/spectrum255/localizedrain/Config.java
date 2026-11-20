package com.spectrum255.localizedrain;

import com.electronwill.nightconfig.core.EnumGetMethod;
import com.spectrum255.localizedrain.api.IsolatedBiomeWeather;
import com.spectrum255.localizedrain.api.StormSpawnPlacement;
import com.spectrum255.localizedrain.api.WeatherMode;
import java.util.List;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * 天气/风暴设置（SERVER，每个存档一份 {@code <world>/serverconfig/localizedrain/weather.toml}）。
 *
 * <p>文件按「索引树」分节，便于配置界面逐层展开：
 * <pre>
 * [general]          模式与天气池
 * [patches]          片天气循环与存储
 * [storm.spawn]      风暴生成
 * [storm.wind]       风场浮动
 * [storm.lifecycle]  距离与半径、合并/分裂
 * [storm.terrain]    地形交互（高原/干旱群系/绕行）
 * [storm.sync]       同步与强度
 * [storm.stacking]   叠加天气
 * [compat]           原版查询代理
 * [filters]          群系过滤（原 filters.toml 并入）
 * </pre>
 * 键名、默认值与取值范围与旧版完全一致，只是路径多了节前缀；
 * {@code ConfigFiles} 会在启动时把旧的平铺键迁移进对应节，并备份原文件。</p>
 *
 * <p>{@code push}/{@code pop} 必须严格成对：少一个 {@code pop} 会让后续键落进错误节，
 * 构建期的 {@link GlobalWeatherConfig} 默认值比对与启动期的
 * {@code ConfigFiles.verifySectionCoverage()} 会兜底报错。</p>
 */
public final class Config {
    /** 天气/风暴设置：SERVER 配置，每个存档在 `<world>/serverconfig/localizedrain/weather.toml` 下独立。 */
    private static final ModConfigSpec.Builder WEATHER_BUILDER = new ModConfigSpec.Builder();

    /** 调试开关：COMMON 配置，所有存档共用（`config/localizedrain/common.toml`）。 */
    private static final ModConfigSpec.Builder DEBUG_BUILDER = new ModConfigSpec.Builder();

    // ---- [general] 模式与天气池 ----

    static {
        WEATHER_BUILDER.translation("localizedrain.configuration.general").push("general");
    }

    public static final ModConfigSpec.EnumValue<WeatherMode> WEATHER_MODE =
            WEATHER_BUILDER.comment("Weather system mode. Newly created worlds default to mobile.",
                            "Applies at runtime within about a second (or use /weather mode).",
                            "patches = existing 3D biome-patch weather",
                            "mobile  = moving storms with wind, evolution and 3D coverage",
                            "vanilla = do not take over vanilla weather at all")
                    .defineEnum("weatherMode", WeatherMode.MOBILE);

    public static final ModConfigSpec.BooleanValue ENABLE_BUILTIN_PRESETS =
            WEATHER_BUILDER.comment("Enable the extra built-in weather presets shipped with the mod",
                            "(snow, blizzard, sandstorm, fog, overcast, ashstorm, soulmist, voidstorm, voidhaze).",
                            "When false they never appear in natural weather pools or natural storms,",
                            "except no-skylight presets (Nether / End), which stay in those dimensions'",
                            "natural pools so those dimensions keep their own storms;",
                            "datapack-defined weather types and /weather commands are unaffected.",
                            "Release default: off.")
                    .define("enableBuiltinPresets", false);

    public static final ModConfigSpec.EnumValue<IsolatedBiomeWeather> ISOLATED_BIOME_WEATHER =
            WEATHER_BUILDER.comment("Weather inside biomes tagged #localizedrain:storm_isolated (mobile mode only).",
                            "clear  = always clear",
                            "patch  = keep running the biome-patch weather machine for those patches",
                            "manual = clear unless set by command/API")
                    .defineEnum("isolatedBiomeWeather", IsolatedBiomeWeather.CLEAR, EnumGetMethod.NAME_IGNORECASE);

    static {
        WEATHER_BUILDER.pop();
    }

    // ---- [patches] 片天气循环与存储 ----

    static {
        WEATHER_BUILDER.translation("localizedrain.configuration.patches").push("patches");
    }

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

    /** 片模式：天气类型到期后是否按 {@code next} 生成演化（见 {@code weather_type} 的 {@code next} 字段）。 */
    public static final ModConfigSpec.BooleanValue PATCH_WEATHER_EVOLUTION =
            WEATHER_BUILDER.comment("Advance patch weather through the 'next' list of a weather type when its timer ends.",
                            "Only applies to natural cycling patches (not forced, locked or manually set ones).",
                            "When disabled, every type change picks a random type from the biome's pool.")
                    .define("patchWeatherEvolution", true);

    /** 片模式：是否给天气强度套用 {@code lifecycle} 的淡入/淡出（0 = 关闭，强度立即到位）。 */
    public static final ModConfigSpec.BooleanValue PATCH_LIFECYCLE =
            WEATHER_BUILDER.comment("Apply the optional 'lifecycle' fade-in/fade-out of a weather type to patch intensity.",
                            "When disabled, a newly set patch weather reaches full intensity immediately.")
                    .define("patchLifecycle", true);

    static {
        WEATHER_BUILDER.pop();
    }

    /** 片模式演化开关（配置未加载时按默认 true 处理）。 */
    public static boolean patchEvolutionEnabled() {
        try {
            return PATCH_WEATHER_EVOLUTION.get();
        } catch (IllegalStateException exception) {
            return true;
        }
    }

    /** 片模式生命周期开关（配置未加载时按默认 true 处理）。 */
    public static boolean patchLifecycleEnabled() {
        try {
            return PATCH_LIFECYCLE.get();
        } catch (IllegalStateException exception) {
            return true;
        }
    }

    // ---- [storm] 风暴模式总索引 ----
    // [storm] 本身不挂键，只作为总索引；NightConfig 只会写出 [storm.spawn] 等子节。

    static {
        WEATHER_BUILDER.push("storm");
    }

    // ---- [storm.spawn] 生成 ----

    static {
        WEATHER_BUILDER.translation("localizedrain.configuration.storm.spawn").push("spawn");
    }

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

    /** 自然风暴的生成位置策略：在玩家附近，或在任意已加载区块随机生成。 */
    public static final ModConfigSpec.EnumValue<StormSpawnPlacement> STORM_SPAWN_PLACEMENT =
            WEATHER_BUILDER.comment("Where natural storms spawn.",
                            "near_player     = within the configured distance of a random player (respects the per-player cap)",
                            "anywhere_loaded = at a random loaded chunk (ignores the per-player cap)")
                    .defineEnum("stormSpawnPlacement", StormSpawnPlacement.NEAR_PLAYER, EnumGetMethod.NAME_IGNORECASE);

    static {
        WEATHER_BUILDER.pop();
    }

    // ---- [storm.wind] 风场浮动 ----

    static {
        WEATHER_BUILDER.translation("localizedrain.configuration.storm.wind").push("wind");
    }

    public static final ModConfigSpec.IntValue WIND_CHANGE_INTERVAL_TICKS =
            WEATHER_BUILDER.comment("How often (in ticks) the dimension wind direction and speed are re-rolled.")
                    .defineInRange("windChangeIntervalTicks", 1200, 20, 240000);

    /** 风场初始速度（方块/秒），同时也是「回归目标」；首次生成时直接取该值，之后在现有值上浮动。 */
    public static final ModConfigSpec.DoubleValue WIND_INITIAL_SPEED =
            WEATHER_BUILDER.comment("Initial wind speed in blocks per second (also the value the wind is pulled back to).")
                    .defineInRange("windInitialSpeed", 10.0D, 0.0D, 200.0D);

    /** 风速单次浮动量的下限（方块/秒）；每次调整时在 [min, max] 内随机取幅度，符号随机。 */
    public static final ModConfigSpec.DoubleValue WIND_SPEED_VARIATION_MIN =
            WEATHER_BUILDER.comment("Minimum wind speed fluctuation magnitude per step in blocks per second.")
                    .defineInRange("windSpeedVariationMin", 1.0D, 0.0D, 200.0D);

    /** 风速单次浮动量的上限（方块/秒）。 */
    public static final ModConfigSpec.DoubleValue WIND_SPEED_VARIATION_MAX =
            WEATHER_BUILDER.comment("Maximum wind speed fluctuation magnitude per step in blocks per second.")
                    .defineInRange("windSpeedVariationMax", 3.0D, 0.0D, 200.0D);

    /** 风速偏离上限（方块/秒）：当前速度与初始值相差超过该值时，暂停随机浮动、按回归比例拉回初始值。 */
    public static final ModConfigSpec.DoubleValue WIND_SPEED_DEVIATION_LIMIT =
            WEATHER_BUILDER.comment("If the current wind speed differs from the initial value by more than this (blocks per second), it is pulled back instead of fluctuating.")
                    .defineInRange("windSpeedDeviationLimit", 5.0D, 0.0D, 200.0D);

    /** 回归比例（0–1）：强制回归时，每次调整向初始值靠拢的差值比例。 */
    public static final ModConfigSpec.DoubleValue WIND_RETURN_RATE =
            WEATHER_BUILDER.comment("Fraction of the remaining speed gap closed per step while the wind is returning to its initial value (0-1).")
                    .defineInRange("windReturnRate", 0.25D, 0.0D, 1.0D);

    /** 风向单次浮动量的下限（度）；每次调整时在 [min, max] 内随机取幅度，符号随机。 */
    public static final ModConfigSpec.DoubleValue WIND_DIRECTION_VARIATION_MIN =
            WEATHER_BUILDER.comment("Minimum wind direction fluctuation magnitude per step in degrees.")
                    .defineInRange("windDirectionVariationMin", 10.0D, 0.0D, 180.0D);

    /** 风向单次浮动量的上限（度）。 */
    public static final ModConfigSpec.DoubleValue WIND_DIRECTION_VARIATION_MAX =
            WEATHER_BUILDER.comment("Maximum wind direction fluctuation magnitude per step in degrees.")
                    .defineInRange("windDirectionVariationMax", 30.0D, 0.0D, 180.0D);

    static {
        WEATHER_BUILDER.pop();
    }

    // ---- [storm.lifecycle] 距离与半径 ----

    static {
        WEATHER_BUILDER.translation("localizedrain.configuration.storm.lifecycle").push("lifecycle");
    }

    public static final ModConfigSpec.IntValue STORM_DESPAWN_DISTANCE =
            WEATHER_BUILDER.comment("A natural storm this far from every player starts its despawn grace timer.")
                    .defineInRange("despawnDistance", 1000, 128, 100000);

    public static final ModConfigSpec.IntValue STORM_DESPAWN_GRACE_TICKS =
            WEATHER_BUILDER.comment("How long a natural storm must stay too far away before it fades out.")
                    .defineInRange("despawnGraceTicks", 600, 0, 24000);

    /**
     * 生成风暴的基准半径（方块）。0 = 使用档案自带的 radius 区间。
     * 大于 0 时以此为中心，按 {@link #STORM_RADIUS_VARIANCE} 做上下浮动。
     */
    public static final ModConfigSpec.DoubleValue STORM_RADIUS_BASE =
            WEATHER_BUILDER.comment("Base radius for newly spawned storms, in blocks.",
                            "0 = use the profile's radius range.",
                            "When > 0, stormRadiusVariance is applied on top of this value.")
                    .defineInRange("stormRadiusBase", 0.0D, 0.0D, 4096.0D);

    /** 基准半径的浮动率：0.3 = 在基准 ±30% 范围内随机。仅在 {@link #STORM_RADIUS_BASE} > 0 时生效。 */
    public static final ModConfigSpec.DoubleValue STORM_RADIUS_VARIANCE =
            WEATHER_BUILDER.comment("Random variation applied to stormRadiusBase, as a fraction.",
                            "0.3 = +/-30%. Only used when stormRadiusBase > 0.")
                    .defineInRange("stormRadiusVariance", 0.3D, 0.0D, 1.0D);

    /** 同类型风暴靠近时是否合并。 */
    public static final ModConfigSpec.BooleanValue STORM_MERGE_ENABLED =
            WEATHER_BUILDER.comment("Merge two nearby storms of the same (or compatible) weather type.")
                    .define("mergeEnabled", true);

    /** 合并距离系数：两风暴中心距离小于 (r1 + r2) × 该值时可合并。 */
    public static final ModConfigSpec.DoubleValue STORM_MERGE_RADIUS_FACTOR =
            WEATHER_BUILDER.comment("Storms can merge when their center distance is below (r1 + r2) * this factor.",
                            "0.6 = only overlapping storms merge.")
                    .defineInRange("mergeRadiusFactor", 0.6D, 0.0D, 2.0D);

    /** 两次合并之间的最小间隔（tick）。 */
    public static final ModConfigSpec.IntValue STORM_MERGE_COOLDOWN_TICKS =
            WEATHER_BUILDER.comment("Minimum ticks between two merges in the same dimension.")
                    .defineInRange("mergeCooldownTicks", 200, 0, 24000);

    /** 手动风暴是否参与合并（默认不参与，避免命令生成的风暴被自动吃掉）。 */
    public static final ModConfigSpec.BooleanValue STORM_MERGE_MANUAL =
            WEATHER_BUILDER.comment("Allow manually spawned storms to merge.")
                    .define("mergeManual", false);

    /**
     * 分裂后的合并免疫期（tick）。父子风暴在分裂瞬间距离只有 0.55R，
     * 而合并阈值是 (r1 + r2) × mergeRadiusFactor，不加免疫会在下一次检查时立刻合回去。
     */
    public static final ModConfigSpec.IntValue STORM_MERGE_IMMUNITY_TICKS =
            WEATHER_BUILDER.comment("After a split, the parent and child ignore each other for this many ticks.",
                            "Prevents a fresh split from merging back on the next checks. 0 = disabled.")
                    .defineInRange("mergeImmunityTicks", 1200, 0, 24000);

    /** 大型风暴是否分裂。 */
    public static final ModConfigSpec.BooleanValue STORM_SPLIT_ENABLED =
            WEATHER_BUILDER.comment("Split an oversized storm into a child storm.")
                    .define("splitEnabled", true);

    /** 分裂半径系数：半径超过档案最大半径 × 该值时可分裂。 */
    public static final ModConfigSpec.DoubleValue STORM_SPLIT_RADIUS_FACTOR =
            WEATHER_BUILDER.comment("A storm can split when its radius exceeds profile max radius * this factor.")
                    .defineInRange("splitRadiusFactor", 1.4D, 1.0D, 4.0D);

    /** 每次检查时分裂的概率。 */
    public static final ModConfigSpec.DoubleValue STORM_SPLIT_CHANCE =
            WEATHER_BUILDER.comment("Chance for an eligible storm to split on each check.")
                    .defineInRange("splitChance", 0.25D, 0.0D, 1.0D);

    /** 两次分裂之间的最小间隔（tick）。 */
    public static final ModConfigSpec.IntValue STORM_SPLIT_COOLDOWN_TICKS =
            WEATHER_BUILDER.comment("Minimum ticks between two splits in the same dimension.")
                    .defineInRange("splitCooldownTicks", 400, 0, 24000);

    static {
        WEATHER_BUILDER.pop();
    }

    // ---- [storm.terrain] 地形与群系交互 ----

    static {
        WEATHER_BUILDER.translation("localizedrain.configuration.storm.terrain").push("terrain");
    }

    /** 地形交互总开关：关闭时所有风暴系数保持 1，行为与旧版一致。 */
    public static final ModConfigSpec.BooleanValue STORM_TERRAIN_INFLUENCE =
            WEATHER_BUILDER.comment("Let storms react to terrain height and biome precipitation.",
                            "When false every storm keeps its profile-defined rain/thunder and speed.")
                    .define("terrainInfluence", true);

    /** 海拔每升高 1 个 altitudeRange，雨量减少的比例（0.5 = 最高处少一半雨）。 */
    public static final ModConfigSpec.DoubleValue STORM_TERRAIN_ALTITUDE_RAIN_FALLOFF =
            WEATHER_BUILDER.comment("Fraction of rain lost from sea level to altitudeRange blocks up.",
                            "0.5 = storms over high terrain rain 50% less at the top of the range.")
                    .defineInRange("altitudeRainFalloff", 0.5D, 0.0D, 1.0D);

    /** 海拔升高时雷量的相对增益（0.25 = 最高处雷量 +25%）。 */
    public static final ModConfigSpec.DoubleValue STORM_TERRAIN_HIGHLAND_THUNDER_BOOST =
            WEATHER_BUILDER.comment("Relative thunder increase from sea level to altitudeRange blocks up.",
                            "0.25 = thunder intensity grows by 25% at the top of the range, capped at 1.")
                    .defineInRange("highlandThunderBoost", 0.25D, 0.0D, 1.0D);

    /** 海拔升高时移动速度的相对增益（0.2 = 最高处快 20%）。 */
    public static final ModConfigSpec.DoubleValue STORM_TERRAIN_HIGHLAND_SPEED_BOOST =
            WEATHER_BUILDER.comment("Relative speed increase from sea level to altitudeRange blocks up.",
                            "0.2 = storms over high terrain drift 20% faster.")
                    .defineInRange("highlandSpeedBoost", 0.2D, 0.0D, 1.0D);

    /** 干旱群系（无降水）的雨量乘数。 */
    public static final ModConfigSpec.DoubleValue STORM_TERRAIN_DRY_BIOME_RAIN_FACTOR =
            WEATHER_BUILDER.comment("Rain multiplier over biomes without precipitation (deserts, badlands, ...).")
                    .defineInRange("dryBiomeRainFactor", 0.35D, 0.0D, 1.0D);

    /** 干旱群系的雷量乘数。 */
    public static final ModConfigSpec.DoubleValue STORM_TERRAIN_DRY_BIOME_THUNDER_FACTOR =
            WEATHER_BUILDER.comment("Thunder multiplier over biomes without precipitation.")
                    .defineInRange("dryBiomeThunderFactor", 0.5D, 0.0D, 1.0D);

    /** 海拔系数的参考高度：地表从海平面到「海平面 + 该值」线性插值到最大效果。 */
    public static final ModConfigSpec.IntValue STORM_TERRAIN_ALTITUDE_RANGE =
            WEATHER_BUILDER.comment("Height range (in blocks above sea level) used by the altitude factors.",
                            "Altitude effects reach their full value at seaLevel + altitudeRange.")
                    .defineInRange("altitudeRange", 128, 16, 512);

    /** 每个风暴重新采样地形/群系的间隔（tick）。 */
    public static final ModConfigSpec.IntValue STORM_TERRAIN_SAMPLE_INTERVAL_TICKS =
            WEATHER_BUILDER.comment("How often (in ticks) a storm resamples terrain height and biome.")
                    .defineInRange("sampleIntervalTicks", 20, 1, 200);

    static {
        WEATHER_BUILDER.pop();
    }

    // ---- [storm.sync] 同步与强度 ----

    static {
        WEATHER_BUILDER.translation("localizedrain.configuration.storm.sync").push("sync");
    }

    public static final ModConfigSpec.IntValue STORM_SYNC_INTERVAL_TICKS =
            WEATHER_BUILDER.comment("How often (in ticks) the full storm list is re-sent to clients as a correction.")
                    .defineInRange("stormSyncIntervalTicks", 40, 5, 1200);

    public static final ModConfigSpec.DoubleValue STORM_FALLOFF_RATIO =
            WEATHER_BUILDER.comment("Width of the intensity falloff band, as a fraction of the storm radius.")
                    .defineInRange("falloffRatio", 0.3D, 0.05D, 1.0D);

    public static final ModConfigSpec.DoubleValue STORM_LIGHTNING_MULTIPLIER =
            WEATHER_BUILDER.comment("Lightning frequency multiplier for storms.")
                    .defineInRange("lightningMultiplier", 1.0D, 0.0D, 20.0D);

    static {
        WEATHER_BUILDER.pop();
    }

    // ---- [storm.stacking] 叠加天气 ----

    static {
        WEATHER_BUILDER.translation("localizedrain.configuration.storm.stacking").push("stacking");
    }

    /**
     * 移动模式下允许在同一位置叠加的天气类型。
     *
     * <p>格式：{@code <类型id>=<允许的id或#标签>[,...]}。关系是成对的：{@code A=B} 与
     * {@code B=A} 任一存在都表示 A、B 可以同时生效；{@code #tag} 会展开成该标签下的全部类型。
     * 空列表表示「全部天气类型互相兼容」，只受 {@link #MAX_STACKED_WEATHER_TYPES} 层数上限约束
     * （测试期默认行为）；填写后只有列出的组合可以叠加。</p>
     */
    public static final ModConfigSpec.ConfigValue<List<? extends String>> COMPATIBLE_WEATHER =
            WEATHER_BUILDER.comment("Weather types that may stack at the same position in mobile mode.",
                            "Format: <typeId>=<id|#tag>[,<id|#tag>...]",
                            "The relation is symmetric: A=B or B=A both allow A and B to stack.",
                            "Tags are written as #namespace:path and expand to the tagged types.",
                            "Empty list = every weather type may stack (limited only by maxStackedWeatherTypes).",
                            "When entries are present, only the listed combinations stack.")
                    .defineListAllowEmpty("compatibleWeather", List.of(), () -> "",
                            Config::validCompatibleWeather);

    /**
     * 定向互斥：{@code <先出现的类型id>=<不再叠加的id或#标签>[,...]}。
     *
     * <p>与 {@link #COMPATIBLE_WEATHER} 不同，这里的关系是单向的：只有先出现的天气是左侧类型时，
     * 才会拒绝右侧类型加入同一位置；反过来仍然允许。用于「雾之后不再接雨，但雨之后可以起雾」这类规则。
     * 空列表表示不额外限制。</p>
     */
    public static final ModConfigSpec.ConfigValue<List<? extends String>> BLOCKED_WEATHER =
            WEATHER_BUILDER.comment("Directed stacking blocks in mobile mode.",
                            "Format: <firstTypeId>=<laterId|#tag>[,<laterId|#tag>...]",
                            "The rule only applies in the written direction: the first type blocks the later one.",
                            "Reverse order stays allowed: 'fog=rain' blocks rain after fog, while rain-then-fog still stacks.",
                            "Empty list = no extra restriction.")
                    .defineListAllowEmpty("blockedWeather", List.of(), () -> "",
                            Config::validCompatibleWeather);

    /** 同一位置最多渲染/叠加的天气层数。 */
    public static final ModConfigSpec.IntValue MAX_STACKED_WEATHER_TYPES =
            WEATHER_BUILDER.comment("Maximum number of weather layers at one position (mobile mode).",
                            "1 = no stacking, identical to the old behaviour.")
                    .defineInRange("maxStackedWeatherTypes", 3, 1, 8);
    static {
        WEATHER_BUILDER.pop();
        WEATHER_BUILDER.pop(); // [storm] 总索引
    }

    // ---- [compat] 原版查询代理 ----

    static {
        WEATHER_BUILDER.translation("localizedrain.configuration.compat").push("compat");
    }

    /**
     * 兼容代理：是否把局部天气暴露给原版的无坐标查询。
     *
     * <p>{@code Level#isRaining()}、{@code getRainLevel()}、{@code isThundering()}、
     * {@code getThunderLevel()} 没有位置参数，只能返回维度级值；开启后它们会返回
     * 「该维度任意位置的最大局部强度」，于是只检查 {@code level.isRaining()} 的第三方模组
     * （容器集水、作物生长、刷怪判断等）也能对局部天气作出反应。</p>
     *
     * <p>已知坐标的帧（随机刻、计划刻、方块实体、实体、降水刻、区块 tick、邻居更新）里按位置返回；
     * 其余调用回退到「该维度任意位置的最大局部强度」，这是近似值。需要按位置判断的模组应改用
     * {@code WeatherApi.rainLevelAt}/{@code isRainingAt}。默认关闭。</p>
     *
     * <p>开启后（或任何非原版模式下）本模组独占 {@code Level} 的维度级
     * {@code rainLevel/oRainLevel/thunderLevel/oThunderLevel} 字段：它们每个 tick 都会被清零，
     * 其他模组通过 {@code setRainLevel/setThunderLevel} 写入的值不会保留。</p>
     */
    public static final ModConfigSpec.BooleanValue COMPAT_VANILLA_WEATHER_QUERIES =
            WEATHER_BUILDER.comment("Expose localized weather to vanilla's coordinate-less queries:",
                            "Level#isRaining(), getRainLevel(), isThundering() and getThunderLevel().",
                            "On the server, queries made inside known position frames (random/scheduled ticks,",
                            "block entities, entities, precipitation, chunk ticks, neighbour updates) return",
                            "the intensity at that position; other calls fall back to the maximum local",
                            "intensity in the dimension. This lets mods that only check level.isRaining()/",
                            "getRainLevel() react to localized weather; use WeatherApi.rainLevelAt for",
                            "per-position values. The dimension-wide fallback is an approximation.")
                    .define("compatVanillaWeatherQueries", false);

    /**
     * 是否执行天气类型 {@code effects.attributes} 里的任意属性修饰符。
     *
     * <p>兼容层：数据包可以给玩家加任意 {@code BuiltInRegistries.ATTRIBUTE} 属性（包括其它模组注册的，
     * 例如 Apothic Attributes 的 {@code apothic_attributes:*}）。未知属性 id 只告警一次并跳过；
     * 关闭后所有属性修饰符都不生效（伤害/药水/点燃等其它效果不受影响）。</p>
     */
    public static final ModConfigSpec.BooleanValue EFFECTS_APPLY_ATTRIBUTES =
            WEATHER_BUILDER.comment("Apply the arbitrary attribute modifiers declared in weather type",
                            "'effects.attributes' entries. Attributes are resolved by id at runtime",
                            "(including attributes registered by other mods). Unknown ids are skipped",
                            "with a single warning. Turning this off leaves damage/potions/ignite",
                            "and the other effects untouched.")
                    .define("effectsApplyAttributes", true);

    static {
        WEATHER_BUILDER.pop();
    }

    // ---- [filters] 群系过滤（原 filters.toml 并入） ----

    static {
        WEATHER_BUILDER.translation("localizedrain.configuration.filters").push("filters");
    }

    /** 白名单：命中的群系只会循环列出的天气；为空时使用默认自然池。 */
    public static final ModConfigSpec.ConfigValue<List<? extends String>> BIOME_WEATHER_WHITELIST =
            WEATHER_BUILDER.comment("Weather whitelist: biomes only cycle the weather listed here.",
                            "Format: '<biome selector>=<weather>', one entry per weather, for example",
                            "'minecraft:plains=clear' and 'minecraft:plains=localizedrain:rain'.",
                            "A matching biome will never get any weather that is not listed here.",
                            "Weather may be a type id, clear/rain/thunder, or a weather type tag like",
                            "'#localizedrain:dry'; the built-in short names map to built-in types.",
                            "Empty = use the default natural pool (#localizedrain:natural, or built-in clear/rain/thunder).",
                            "Biome selectors: 'minecraft:desert', 'minecraft:*' (namespace), '#minecraft:is_ocean'.")
                    .defineListAllowEmpty("weatherBiomeWhitelist", List.of(), () -> "",
                            Config::validBiomeWeatherEntry);

    /** 黑名单：命中的群系永远不会出现列出的天气，但仍会在剩余天气里循环。 */
    public static final ModConfigSpec.ConfigValue<List<? extends String>> BIOME_WEATHER_BLACKLIST =
            WEATHER_BUILDER.comment("Weather blacklist: biomes never get the weather listed here.",
                            "The biome still cycles through its remaining weather:",
                            "'minecraft:desert=localizedrain:rain' keeps deserts rainless but they may still thunder.",
                            "Format and biome selectors are the same as the whitelist; weather type tags are supported.",
                            "Blacklisted weather is also removed from the whitelist pool.")
                    .defineListAllowEmpty("weatherBiomeBlacklist", List.of(), () -> "",
                            Config::validBiomeWeatherEntry);

    /** 强制天气：命中的群系始终保持该种天气；同一群系多条命中时取第一条。 */
    public static final ModConfigSpec.ConfigValue<List<? extends String>> BIOME_WEATHER_OVERRIDES =
            WEATHER_BUILDER.comment("Forced weather: biomes that always stay in exactly one weather type.",
                            "Format: '<biome selector>=<weather>', for example 'minecraft:desert=clear'.",
                            "Only one weather per biome is supported; when several entries match,",
                            "the FIRST entry (top to bottom) wins. Weather tags are not allowed here.",
                            "The short names clear/rain/thunder map to the built-in types.",
                            "A biome forced to clear never receives weather, not even from commands or the API.")
                    .defineListAllowEmpty("weatherBiomeOverrides", List.of(), () -> "",
                            Config::validBiomeOverride);

    static {
        WEATHER_BUILDER.pop();
    }

    // ---- [logging] 调试日志（common.toml） ----

    static {
        DEBUG_BUILDER.translation("localizedrain.configuration.logging").push("logging");
    }

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
                            "stage, weather type, intensity, velocity, manual flag and lifetime) whenever a storm spawns.",
                            "Release default: off.")
                    .define("logStormSpawns", false);

    /** 调试：风暴生成时在聊天栏向玩家报告中心坐标与类型。 */
    public static final ModConfigSpec.BooleanValue LOG_STORM_SPAWNS_TO_CHAT =
            DEBUG_BUILDER.comment("Report every storm spawn in the chat bar (center coordinates and weather type).",
                            "Natural storms are infrequent, but disable this if you do not want the messages.",
                            "Release default: off.")
                    .define("logStormSpawnsToChat", false);

    static {
        DEBUG_BUILDER.pop();
    }

    /** 全局（所有存档共用）的调试开关。 */
    static final ModConfigSpec COMMON_SPEC = DEBUG_BUILDER.build();

    /** 每个存档独立的天气/风暴设置。 */
    static final ModConfigSpec WEATHER_SPEC = WEATHER_BUILDER.build();

    private Config() {}

    /**
     * 内置新增天气预设（雪/暴风雪/沙尘暴/雾/阴/灰烬风暴/灵魂薄雾/虚空风暴/虚空霾）是否参与自然生成。
     *
     * <p>默认关闭；配置尚未加载或读取失败时按关闭处理，避免加载早期意外启用。
     * 只影响自然池与自然风暴，{@code /weather type}、{@code /weather storm spawn} 与数据包定义的天气不受影响。</p>
     */
    public static boolean builtinPresetsEnabled() {
        try {
            return ENABLE_BUILTIN_PRESETS.get();
        } catch (RuntimeException exception) {
            return false;
        }
    }

    /** 隔离群系（{@code #localizedrain:storm_isolated}）的天气语义；配置尚未加载时回退晴天。 */
    public static IsolatedBiomeWeather isolatedBiomeWeather() {
        try {
            return ISOLATED_BIOME_WEATHER.get();
        } catch (RuntimeException exception) {
            return IsolatedBiomeWeather.CLEAR;
        }
    }

    /** 自然风暴的生成位置策略；配置尚未加载时回退玩家附近。 */
    public static StormSpawnPlacement stormSpawnPlacement() {
        try {
            return STORM_SPAWN_PLACEMENT.get();
        } catch (RuntimeException exception) {
            return StormSpawnPlacement.NEAR_PLAYER;
        }
    }

    /** 是否把局部天气暴露给原版无坐标查询；配置尚未加载时按关闭处理。 */
    public static boolean compatVanillaWeatherQueries() {
        try {
            return COMPAT_VANILLA_WEATHER_QUERIES.get();
        } catch (RuntimeException exception) {
            return false;
        }
    }

    /** 是否执行 {@code effects.attributes} 的任意属性修饰符；配置尚未加载时按开启处理。 */
    public static boolean effectsApplyAttributes() {
        try {
            return EFFECTS_APPLY_ATTRIBUTES.get();
        } catch (RuntimeException exception) {
            return true;
        }
    }

    /** 移动模式允许叠加的天气类型对；配置尚未加载时按「全部可叠加」处理（与空列表语义一致）。 */
    public static List<? extends String> compatibleWeather() {
        try {
            return COMPATIBLE_WEATHER.get();
        } catch (RuntimeException exception) {
            return List.of();
        }
    }

    /** 单向互斥规则；配置尚未加载时按空处理。 */
    public static List<? extends String> blockedWeather() {
        try {
            return BLOCKED_WEATHER.get();
        } catch (RuntimeException exception) {
            return List.of();
        }
    }

    /** 同一位置最多叠加的天气层数；配置尚未加载时按 1 处理。 */
    public static int maxStackedWeatherTypes() {
        try {
            return MAX_STACKED_WEATHER_TYPES.get();
        } catch (RuntimeException exception) {
            return 1;
        }
    }

    /**
     * 兼容天气条目：{@code 类型id=id或#tag,...}。
     *
     * <p>左侧必须是合法资源 id；右侧至少一个可解析项，{@code #} 前缀表示标签。
     * 任一侧不合法都会让整条条目被拒绝，避免主菜单/服务端启动时静默丢配置。</p>
     */
    public static boolean validCompatibleWeather(Object value) {
        if (!(value instanceof String entry)) {
            return false;
        }
        int split = entry.indexOf('=');
        if (split <= 0 || split == entry.length() - 1) {
            return false;
        }
        if (ResourceLocation.tryParse(entry.substring(0, split).trim()) == null) {
            return false;
        }
        for (String token : entry.substring(split + 1).split(",")) {
            String trimmed = token.trim();
            if (trimmed.isEmpty()) {
                return false;
            }
            String id = trimmed.startsWith("#") ? trimmed.substring(1) : trimmed;
            if (ResourceLocation.tryParse(id) == null) {
                return false;
            }
        }
        return true;
    }

    /** 过滤条目校验：{@code <群系选择器>=<天气>}，允许天气标签。 */
    static boolean validBiomeWeatherEntry(Object entry) {
        return entry instanceof String value && parseWeatherEntry(value, true) != null;
    }

    /** 强制天气条目校验：{@code <群系选择器>=<天气>}，不允许天气标签。 */
    static boolean validBiomeOverride(Object entry) {
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
