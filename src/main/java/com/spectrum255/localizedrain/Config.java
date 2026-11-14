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
     * 候选天气：这些群系/标签自然循环时从候选集中随机抽取天气类型。
     * 格式 {@code <群系选择器>={<类型1>, <类型2>}}，例如
     * {@code minecraft:desert={weatherplus:sandstorm}} 表示沙漠只会出现沙尘暴。
     * 候选规则只表达“可能发生”，不会锁定天气；未配置的群系沿用原版晴/雨/雷循环。
     */
    public static final ModConfigSpec.ConfigValue<List<? extends String>> BIOME_WEATHER_CANDIDATES =
            BUILDER.comment("Weather types that may occur naturally in matching biomes.",
                            "Format: '<biome selector>=<type>|<type>...', for example",
                            "'minecraft:desert=weatherplus:sandstorm' (desert may only get sandstorms).",
                            "Candidates express possibility, not a permanent lock; unlisted biomes keep the vanilla cycle.",
                            "The candidate list replaces the built-in rain/thunder choice for that biome; add",
                            "'localizedrain:rain' or 'localizedrain:thunder' explicitly if you still want them.")
                    .defineListAllowEmpty("weatherBiomeCandidates", List.of(), () -> "", Config::validCandidateEntry);

    /** 空列表表示所有生物群系都参与局地天气。 */
    public static final ModConfigSpec.ConfigValue<List<? extends String>> BIOME_WEATHER_WHITELIST =
            BUILDER.comment("Biomes that may receive localized weather. Empty = every biome is allowed.",
                            "The blacklist always takes precedence over the whitelist.",
                            "Entries: 'minecraft:desert' (exact id), 'minecraft:*' (whole namespace),",
                            "or a biome tag such as '#minecraft:is_ocean'.")
                    .defineListAllowEmpty("weatherBiomeWhitelist", List.of(), () -> "", Config::validBiomeEntry);

    /** 这些生物群系永远保持晴天，不参与天气循环，也不会生成闪电。 */
    public static final ModConfigSpec.ConfigValue<List<? extends String>> BIOME_WEATHER_BLACKLIST =
            BUILDER.comment("Biomes that never receive localized weather (always clear).",
                            "The blacklist always takes precedence over the whitelist.",
                            "Entries: 'minecraft:desert' (exact id), 'minecraft:*' (whole namespace),",
                            "or a biome tag such as '#minecraft:is_ocean'.")
                    .defineListAllowEmpty("weatherBiomeBlacklist", List.of(), () -> "", Config::validBiomeEntry);

    /**
     * 强制某些生物群系始终处于指定天气；格式为 “群系选择器=天气类型 id”。
     * 天气可以写任意已注册的天气类型（例如附属模组的 {@code examplemod:sandstorm}），
     * 也可以写旧的短名 {@code clear}/{@code rain}/{@code thunder}（等价于 {@code localizedrain:} 下的内置类型）。
     * 类型 id 在判定时惰性解析，因此配置加载顺序不影响自定义类型的可用性。
     */
    public static final ModConfigSpec.ConfigValue<List<? extends String>> BIOME_WEATHER_OVERRIDES =
            BUILDER.comment("Biomes that are always kept in a fixed weather type.",
                            "Format: '<biome selector>=<weather type id>', for example",
                            "'minecraft:desert=clear', 'minecraft:*=rain', '#minecraft:is_ocean=thunder',",
                            "or an addon type such as 'minecraft:desert=examplemod:sandstorm'.",
                            "The short names clear/rain/thunder map to the built-in types.",
                            "Overrides take precedence over the whitelist and blacklist;",
                            "when several entries match, the last one wins.")
                    .defineListAllowEmpty("weatherBiomeOverrides", List.of(), () -> "", Config::validBiomeOverride);

    static final ModConfigSpec SPEC = BUILDER.build();

    private Config() {}

    private static boolean validBiomeEntry(Object entry) {
        return entry instanceof String value && !value.isBlank();
    }

    private static boolean validBiomeOverride(Object entry) {
        if (!(entry instanceof String value)) {
            return false;
        }
        return parseOverride(value) != null;
    }

    private static boolean validCandidateEntry(Object entry) {
        if (!(entry instanceof String value)) {
            return false;
        }
        int separator = value.lastIndexOf('=');
        if (separator <= 0 || separator == value.length() - 1 || value.substring(0, separator).isBlank()) {
            return false;
        }
        for (String raw : candidateTokens(value.substring(separator + 1))) {
            String weather = raw.trim();
            if (weather.isEmpty() || ResourceLocation.tryParse(weather) == null) {
                return false;
            }
        }
        return true;
    }

    /** 拆分候选类型列表：{@code {a, b}} 或 {@code {a}}，逗号分隔；兼容旧写法 {@code a|b}。 */
    public static String[] candidateTokens(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.startsWith("{") && value.endsWith("}")) {
            value = value.substring(1, value.length() - 1);
        }
        return value.split("[|,]");
    }

    /** 校验并返回 {@code selector=weather} 里的 selector；格式非法返回 null。 */
    private static String parseOverride(String value) {
        int separator = value.lastIndexOf('=');
        if (separator <= 0 || separator == value.length() - 1 || value.substring(0, separator).isBlank()) {
            return null;
        }
        String weather = value.substring(separator + 1).trim();
        // 旧写法 clear/rain/thunder 仍然有效；其余必须是合法的 ResourceLocation（类型 id 在判定时惰性解析，
        // 这里不检查注册表，避免配置加载早于注册表可用时误判）。
        if (weather.equals("clear") || weather.equals("rain") || weather.equals("thunder")
                || ResourceLocation.tryParse(weather) != null) {
            return value.substring(0, separator).trim();
        }
        return null;
    }
}
