package com.spectrum255.localizedrain;

import com.spectrum255.localizedrain.api.WeatherMode;
import java.util.List;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * 全局天气默认值（COMMON）。
 *
 * <p>它只在标题页面/主菜单可编辑（SERVER 配置要到世界启动后才加载，主菜单里是灰的）；
 * 新建存档时会被复制成 {@code <世界>/serverconfig/localizedrain/weather.toml}，
 * 之后该存档的配置就独立演变，不再受这里影响。</p>
 *
 * <p>键名、默认值与 {@link Config} 的天气部分一一对应；两处必须同步修改，
 * 否则老存档读到的键与默认值会和这里不一致。</p>
 */
public final class GlobalWeatherConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    private static final List<String> ISOLATED_WEATHER_MODES = List.of("clear", "patch", "manual");
    private static final List<String> SPAWN_PLACEMENTS = List.of("near_player", "anywhere_loaded");

    /** 与 {@link Config} 天气键一一对应的全局默认值。 */
    static final ModConfigSpec SPEC;

    static {
        // 天气计时（tick）
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

        // 模式与过滤器相关
        BUILDER.defineEnum("weatherMode", WeatherMode.MOBILE);
        BUILDER.define("enableBuiltinPresets", false);
        BUILDER.define("isolatedBiomeWeather", "clear",
                value -> value instanceof String mode && ISOLATED_WEATHER_MODES.contains(mode));

        // 移动风暴
        BUILDER.define("naturalCaveStorms", false);
        BUILDER.defineInRange("caveStormRadius", 64, 8, 512);
        BUILDER.defineInRange("maxStormsPerDimension", 8, 0, 64);
        BUILDER.defineInRange("maxStormsPerPlayer", 2, 0, 16);
        BUILDER.defineInRange("maxManualStormsPerDimension", 16, 0, 128);
        BUILDER.defineInRange("spawnIntervalTicks", 200, 20, 24000);
        BUILDER.defineInRange("spawnMinDistance", 300, 32, 4096);
        BUILDER.defineInRange("spawnMaxDistance", 700, 32, 8192);
        BUILDER.defineInRange("naturalStormSpawnChance", 0.035D, 0.0D, 1.0D);
        BUILDER.define("stormSpawnPlacement", "near_player",
                value -> value instanceof String placement && SPAWN_PLACEMENTS.contains(placement));
        BUILDER.defineInRange("windChangeIntervalTicks", 1200, 20, 240000);
        BUILDER.defineInRange("windMinSpeed", 0.02D, 0.0D, 1.0D);
        BUILDER.defineInRange("windMaxSpeed", 0.08D, 0.0D, 1.0D);
        BUILDER.defineInRange("despawnDistance", 1000, 128, 100000);
        BUILDER.defineInRange("despawnGraceTicks", 600, 0, 24000);
        BUILDER.defineInRange("stormSyncIntervalTicks", 40, 5, 1200);
        BUILDER.defineInRange("falloffRatio", 0.3D, 0.05D, 1.0D);
        BUILDER.defineInRange("lightningMultiplier", 1.0D, 0.0D, 20.0D);
        BUILDER.defineInRange("stormRadiusBase", 0.0D, 0.0D, 4096.0D);
        BUILDER.defineInRange("stormRadiusVariance", 0.3D, 0.0D, 1.0D);

        SPEC = BUILDER.build();
    }

    private GlobalWeatherConfig() {}
}
