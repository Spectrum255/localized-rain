package com.spectrum255.localizedrain;

import com.electronwill.nightconfig.core.EnumGetMethod;
import com.spectrum255.localizedrain.api.IsolatedBiomeWeather;
import com.spectrum255.localizedrain.api.StormSpawnPlacement;
import com.spectrum255.localizedrain.api.WeatherMode;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * 全局天气默认值（COMMON，{@code config/localizedrain/global-weather.toml}）。
 *
 * <p>本文件是新存档的模板：主菜单即可编辑（SERVER 配置要到世界启动后才加载），
 * 新建存档时会被复制成 {@code <世界>/serverconfig/localizedrain/weather.toml}，
 * 之后该存档的配置就独立演变，不再受这里影响。</p>
 *
 * <p>结构（节名、顺序、键名、默认值）必须与 {@link Config#WEATHER_SPEC} 完全一致：
 * 两边都按 {@code [general] → [patches] → [storm.spawn/wind/lifecycle/terrain/sync/stacking] → [compat] → [filters]}
 * 组织；任何一边漏键或默认值分叉都会被启动时的 {@link #verifyMatchesWeatherSpec()} 报出来。</p>
 */
public final class GlobalWeatherConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    /** 与 {@link Config} 天气键一一对应的全局默认值。 */
    static final ModConfigSpec SPEC;

    static {
        BUILDER.translation("localizedrain.configuration.general").push("general");
        BUILDER.defineEnum("weatherMode", WeatherMode.MOBILE);
        BUILDER.define("enableBuiltinPresets", false);
        BUILDER.defineEnum("isolatedBiomeWeather", IsolatedBiomeWeather.CLEAR, EnumGetMethod.NAME_IGNORECASE);
        BUILDER.pop();

        BUILDER.translation("localizedrain.configuration.patches").push("patches");
        BUILDER.defineInRange("minClearTicks", 12000, 20, 720000);
        BUILDER.defineInRange("maxClearTicks", 168000, 20, 720000);
        BUILDER.defineInRange("minRainTicks", 12000, 20, 720000);
        BUILDER.defineInRange("maxRainTicks", 24000, 20, 720000);
        BUILDER.defineInRange("minThunderDelayTicks", 12000, 20, 720000);
        BUILDER.defineInRange("maxThunderDelayTicks", 180000, 20, 720000);
        BUILDER.defineInRange("minThunderTicks", 3600, 20, 720000);
        BUILDER.defineInRange("maxThunderTicks", 15600, 20, 720000);
        BUILDER.defineInRange("weatherSimulationIntervalTicks", 20, 1, 200);
        BUILDER.define("scan3dSubstates", true);
        BUILDER.defineInRange("stateRetentionDays", 7, 0, 365);
        BUILDER.defineInRange("maxStoredStates", 4096, 256, 65536);
        BUILDER.defineInRange("maxParentEntries", 16384, 4096, 1000000);
        BUILDER.defineInRange("maxColumnScansPerTick", 4, 1, 64);
        BUILDER.define("patchWeatherEvolution", true);
        BUILDER.define("patchLifecycle", true);
        BUILDER.pop();

        BUILDER.push("storm");

        BUILDER.translation("localizedrain.configuration.storm.spawn").push("spawn");
        BUILDER.define("naturalCaveStorms", false);
        BUILDER.defineInRange("caveStormRadius", 64, 8, 512);
        BUILDER.defineInRange("maxStormsPerDimension", 8, 0, 64);
        BUILDER.defineInRange("maxStormsPerPlayer", 2, 0, 16);
        BUILDER.defineInRange("maxManualStormsPerDimension", 16, 0, 128);
        BUILDER.defineInRange("spawnIntervalTicks", 200, 20, 24000);
        BUILDER.defineInRange("spawnMinDistance", 300, 32, 4096);
        BUILDER.defineInRange("spawnMaxDistance", 700, 32, 8192);
        BUILDER.defineInRange("naturalStormSpawnChance", 0.035D, 0.0D, 1.0D);
        BUILDER.defineEnum("stormSpawnPlacement", StormSpawnPlacement.NEAR_PLAYER, EnumGetMethod.NAME_IGNORECASE);
        BUILDER.pop();

        BUILDER.translation("localizedrain.configuration.storm.wind").push("wind");
        BUILDER.defineInRange("windChangeIntervalTicks", 1200, 20, 240000);
        BUILDER.defineInRange("windInitialSpeed", 10.0D, 0.0D, 200.0D);
        BUILDER.defineInRange("windSpeedVariationMin", 1.0D, 0.0D, 200.0D);
        BUILDER.defineInRange("windSpeedVariationMax", 3.0D, 0.0D, 200.0D);
        BUILDER.defineInRange("windSpeedDeviationLimit", 5.0D, 0.0D, 200.0D);
        BUILDER.defineInRange("windReturnRate", 0.25D, 0.0D, 1.0D);
        BUILDER.defineInRange("windDirectionVariationMin", 10.0D, 0.0D, 180.0D);
        BUILDER.defineInRange("windDirectionVariationMax", 30.0D, 0.0D, 180.0D);
        BUILDER.pop();

        BUILDER.translation("localizedrain.configuration.storm.lifecycle").push("lifecycle");
        BUILDER.defineInRange("despawnDistance", 1000, 128, 100000);
        BUILDER.defineInRange("despawnGraceTicks", 600, 0, 24000);
        BUILDER.defineInRange("stormRadiusBase", 0.0D, 0.0D, 4096.0D);
        BUILDER.defineInRange("stormRadiusVariance", 0.3D, 0.0D, 1.0D);
        BUILDER.define("mergeEnabled", true);
        BUILDER.defineInRange("mergeRadiusFactor", 0.6D, 0.0D, 2.0D);
        BUILDER.defineInRange("mergeCooldownTicks", 200, 0, 24000);
        BUILDER.define("mergeManual", false);
        BUILDER.defineInRange("mergeImmunityTicks", 1200, 0, 24000);
        BUILDER.define("splitEnabled", true);
        BUILDER.defineInRange("splitRadiusFactor", 1.4D, 1.0D, 4.0D);
        BUILDER.defineInRange("splitChance", 0.25D, 0.0D, 1.0D);
        BUILDER.defineInRange("splitCooldownTicks", 400, 0, 24000);
        BUILDER.pop();

        BUILDER.translation("localizedrain.configuration.storm.terrain").push("terrain");
        BUILDER.define("terrainInfluence", true);
        BUILDER.defineInRange("altitudeRainFalloff", 0.5D, 0.0D, 1.0D);
        BUILDER.defineInRange("highlandThunderBoost", 0.25D, 0.0D, 1.0D);
        BUILDER.defineInRange("highlandSpeedBoost", 0.2D, 0.0D, 1.0D);
        BUILDER.defineInRange("dryBiomeRainFactor", 0.35D, 0.0D, 1.0D);
        BUILDER.defineInRange("dryBiomeThunderFactor", 0.5D, 0.0D, 1.0D);
        BUILDER.defineInRange("altitudeRange", 128, 16, 512);
        BUILDER.defineInRange("sampleIntervalTicks", 20, 1, 200);
        BUILDER.pop();

        BUILDER.translation("localizedrain.configuration.storm.sync").push("sync");
        BUILDER.defineInRange("stormSyncIntervalTicks", 40, 5, 1200);
        BUILDER.defineInRange("falloffRatio", 0.3D, 0.05D, 1.0D);
        BUILDER.defineInRange("lightningMultiplier", 1.0D, 0.0D, 20.0D);
        BUILDER.pop();

        BUILDER.translation("localizedrain.configuration.storm.stacking").push("stacking");
        BUILDER.defineListAllowEmpty("compatibleWeather", java.util.List.of(), () -> "",
                Config::validCompatibleWeather);
        BUILDER.defineListAllowEmpty("blockedWeather", java.util.List.of(), () -> "",
                Config::validCompatibleWeather);
        BUILDER.defineInRange("maxStackedWeatherTypes", 3, 1, 8);
        BUILDER.pop();

        BUILDER.pop(); // [storm]

        BUILDER.translation("localizedrain.configuration.compat").push("compat");
        BUILDER.define("compatVanillaWeatherQueries", false);
        BUILDER.define("effectsApplyAttributes", true);
        BUILDER.pop();

        BUILDER.translation("localizedrain.configuration.filters").push("filters");
        BUILDER.defineListAllowEmpty("weatherBiomeWhitelist", java.util.List.of(), () -> "",
                Config::validBiomeWeatherEntry);
        BUILDER.defineListAllowEmpty("weatherBiomeBlacklist", java.util.List.of(), () -> "",
                Config::validBiomeWeatherEntry);
        BUILDER.defineListAllowEmpty("weatherBiomeOverrides", java.util.List.of(), () -> "",
                Config::validBiomeOverride);
        BUILDER.pop();

        SPEC = BUILDER.build();
        verifyMatchesWeatherSpec();
    }

    /**
     * 全局默认值与 {@link Config#WEATHER_SPEC} 的键与默认值必须一一对应（见类注释）。
     * 启动时比对一次：任何一边漏改键、或改了默认值，都会立刻在日志里暴露，而不是等到老存档读到不一致的值才发现。
     *
     * <p>默认值比较对数字按数值比较（容忍 {@code float}/{@code double} 字面量差异），其余类型按
     * {@link Objects#equals}；两边都是 {@code define*} 的默认值，不经过配置文件加载。trae 第十轮审查 G4
     * 记录的风险（键名一致但默认值已经分叉）由这里兜底。</p>
     */
    private static void verifyMatchesWeatherSpec() {
        Map<String, Object> global = ConfigFiles.leafDefaults(SPEC);
        Map<String, Object> perWorld = ConfigFiles.leafDefaults(Config.WEATHER_SPEC);

        Set<String> missing = new TreeSet<>(perWorld.keySet());
        missing.removeAll(global.keySet());
        Set<String> extra = new TreeSet<>(global.keySet());
        extra.removeAll(perWorld.keySet());
        if (!missing.isEmpty() || !extra.isEmpty()) {
            Localizedrain.LOGGER.error(
                    "[localizedrain] global weather config keys diverged from Config.WEATHER_SPEC; missing={}, extra={}",
                    missing, extra);
        }

        Set<String> valueMismatch = new TreeSet<>();
        for (Map.Entry<String, Object> entry : global.entrySet()) {
            String key = entry.getKey();
            if (!perWorld.containsKey(key)) {
                continue;
            }
            Object left = entry.getValue();
            Object right = perWorld.get(key);
            if (!sameDefault(left, right)) {
                valueMismatch.add(key + " (global=" + left + ", perWorld=" + right + ")");
            }
        }
        if (!valueMismatch.isEmpty()) {
            Localizedrain.LOGGER.error(
                    "[localizedrain] global weather config defaults diverged from Config.WEATHER_SPEC: {}",
                    valueMismatch);
        }
    }

    private static boolean sameDefault(Object left, Object right) {
        if (left instanceof Number a && right instanceof Number b) {
            return Math.abs(a.doubleValue() - b.doubleValue()) <= 1e-9D;
        }
        return Objects.equals(left, right);
    }

    private GlobalWeatherConfig() {}
}
