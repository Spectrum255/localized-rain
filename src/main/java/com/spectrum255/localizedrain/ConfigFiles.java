package com.spectrum255.localizedrain;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.toml.TomlParser;
import com.spectrum255.localizedrain.api.WeatherMode;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.fml.loading.FMLPaths;

/**
 * 配置文件的辅助逻辑。
 *
 * <p>天气配置注册为 {@link net.neoforged.neoforge.common.ModConfig.Type#SERVER}：
 * <ul>
 *   <li>全局默认：{@code config/localizedrain/global-weather.toml}（COMMON，主菜单可编辑）</li>
 *   <li>每个存档：{@code <存档>/serverconfig/localizedrain/weather.toml}（FML 的 override path，存在时优先于全局文件）</li>
 * </ul>
 * FML 只在存档目录里**已有**该文件时才使用它，不会自动复制。这里在服务器启动前把全局文件复制到
 * 存档目录（仅当目标不存在），使每个存档从第一次启动起就有独立副本；之后的修改互不影响。</p>
 *
 * <p>从 1.9.0-alpha 起，天气配置按节索引（{@code [general] → [patches] → [storm.*] → [compat] → [filters]}），
 * 原来的 {@code filters.toml}/{@code global-filters.toml} 不再注册，三个过滤器列表并入天气的 {@code [filters]}。
 * 旧版本写下的平铺文件由 {@link #migrateFlatConfigsToSections()} 转成节结构（备份 {@code .flat.bak}），
 * 旧的过滤器文件由 {@link #mergeLegacyFilters()} 合并（备份 {@code .merged.bak}）。</p>
 */
public final class ConfigFiles {
    private static final String MOD_CONFIG_DIR = "localizedrain";
    private static final String LEGACY_FILE = "localizedrain-common.toml";

    /** 每存档配置：{全局默认文件（COMMON，主菜单可编辑）, 存档副本文件（SERVER）}。 */
    private static final String[][] WORLD_CONFIG_FILES = {
            {"global-weather.toml", "weather.toml"}
    };

    /** 每节的键（与 {@link Config} 的 push/pop 顺序一致，同时也是文件里的书写顺序）。 */
    private static final Map<String, List<String>> WEATHER_SECTION_KEYS = weatherSectionKeys();
    /** 全部天气键（扁平顺序），用于旧配置迁移与差异告警。 */
    private static final List<String> WEATHER_KEYS = flatten(WEATHER_SECTION_KEYS);
    /** common.toml 的键（调试开关）。 */
    private static final Map<String, List<String>> COMMON_SECTION_KEYS = Map.of(
            "logging", List.of("debugLogging", "logWeatherChanges", "logStormSpawns", "logStormSpawnsToChat"));
    private static final List<String> COMMON_KEYS = flatten(COMMON_SECTION_KEYS);
    /** 节的显示标题（写进文件作为注释）。 */
    private static final Map<String, String> SECTION_TITLES = Map.ofEntries(
            Map.entry("general", "通用"),
            Map.entry("patches", "片模式天气循环"),
            Map.entry("storm.spawn", "风暴：生成"),
            Map.entry("storm.wind", "风暴：风场"),
            Map.entry("storm.lifecycle", "风暴：生命周期"),
            Map.entry("storm.terrain", "风暴：地形"),
            Map.entry("storm.sync", "风暴：同步与强度"),
            Map.entry("storm.stacking", "风暴：叠加"),
            Map.entry("compat", "原版查询兼容"),
            Map.entry("filters", "天气过滤器（群系=天气）"),
            Map.entry("logging", "日志"));

    private static final String WEATHER_HEADER = """
            # Localized Rain — 天气与风暴配置（按节索引）
            # 新建存档时会复制成 <存档>/serverconfig/localizedrain/weather.toml。
            # 键名与默认值以本模组的配置规范为准；改默认值请同时更新 Config 与 GlobalWeatherConfig。
            """;
    private static final String COMMON_HEADER = """
            # Localized Rain — 调试开关（所有存档共用）
            """;

    /** 已删除的配置键：旧文件里可能残留，留着会让用户误以为还能改（按文件名分组）。 */
    private static final Map<String, Set<String>> REMOVED_KEYS = removedKeys();

    private static Map<String, List<String>> weatherSectionKeys() {
        Map<String, List<String>> keys = new LinkedHashMap<>();
        keys.put("general", List.of("weatherMode", "enableBuiltinPresets", "isolatedBiomeWeather"));
        keys.put("patches", List.of(
                "minClearTicks", "maxClearTicks", "minRainTicks", "maxRainTicks",
                "minThunderDelayTicks", "maxThunderDelayTicks", "minThunderTicks", "maxThunderTicks",
                "weatherSimulationIntervalTicks", "scan3dSubstates", "stateRetentionDays",
                "maxStoredStates", "maxParentEntries", "maxColumnScansPerTick",
                "patchWeatherEvolution", "patchLifecycle"));
        keys.put("storm.spawn", List.of(
                "naturalCaveStorms", "caveStormRadius",
                "maxStormsPerDimension", "maxStormsPerPlayer", "maxManualStormsPerDimension",
                "spawnIntervalTicks", "spawnMinDistance", "spawnMaxDistance", "naturalStormSpawnChance",
                "stormSpawnPlacement"));
        keys.put("storm.wind", List.of(
                "windChangeIntervalTicks", "windInitialSpeed",
                "windSpeedVariationMin", "windSpeedVariationMax", "windSpeedDeviationLimit", "windReturnRate",
                "windDirectionVariationMin", "windDirectionVariationMax"));
        keys.put("storm.lifecycle", List.of(
                "despawnDistance", "despawnGraceTicks", "stormRadiusBase", "stormRadiusVariance",
                "mergeEnabled", "mergeRadiusFactor", "mergeCooldownTicks", "mergeManual", "mergeImmunityTicks",
                "splitEnabled", "splitRadiusFactor", "splitChance", "splitCooldownTicks"));
        keys.put("storm.terrain", List.of(
                "terrainInfluence", "altitudeRainFalloff", "highlandThunderBoost", "highlandSpeedBoost",
                "dryBiomeRainFactor", "dryBiomeThunderFactor", "altitudeRange", "sampleIntervalTicks"));
        keys.put("storm.sync", List.of("stormSyncIntervalTicks", "falloffRatio", "lightningMultiplier"));
        keys.put("storm.stacking", List.of("compatibleWeather", "blockedWeather", "maxStackedWeatherTypes"));
        keys.put("compat", List.of("compatVanillaWeatherQueries", "effectsApplyAttributes"));
        keys.put("filters", List.of(
                "weatherBiomeWhitelist", "weatherBiomeBlacklist", "weatherBiomeOverrides"));
        return Collections.unmodifiableMap(keys);
    }

    private static List<String> flatten(Map<String, List<String>> sectionKeys) {
        List<String> keys = new ArrayList<>();
        for (List<String> list : sectionKeys.values()) {
            keys.addAll(list);
        }
        return Collections.unmodifiableList(keys);
    }

    /** 用有序映射定义，保证遍历（以及清理日志）的顺序稳定、可预期。 */
    private static Map<String, Set<String>> removedKeys() {
        Map<String, Set<String>> keys = new LinkedHashMap<>();
        Set<String> weatherKeys = Set.of("manualStormRadius");
        keys.put("weather.toml", weatherKeys);
        keys.put("global-weather.toml", weatherKeys);
        return Collections.unmodifiableMap(keys);
    }

    private ConfigFiles() {}

    /**
     * 把旧版 {@code config/localizedrain-common.toml} 拆分迁移到 {@code config/localizedrain/} 下的节结构文件。
     * 目标文件已存在时不动它（避免覆盖用户已改的新配置）；旧文件保留不删除。
     */
    public static void migrateLegacyConfigs() {
        Path configDir = FMLPaths.CONFIGDIR.get().resolve(MOD_CONFIG_DIR);
        Path legacy = FMLPaths.CONFIGDIR.get().resolve(LEGACY_FILE);
        if (Files.notExists(legacy)) {
            return;
        }
        Map<String, String> values;
        try {
            values = readTopLevelValues(legacy);
        } catch (IOException exception) {
            Localizedrain.LOGGER.warn("[localizedrain] could not read legacy config {}", legacy, exception);
            return;
        }
        if (values.isEmpty()) {
            return;
        }
        int migrated = 0;
        migrated += writeSectionedIfMissing(configDir.resolve("global-weather.toml"), WEATHER_HEADER,
                WEATHER_SECTION_KEYS, values);
        migrated += writeSectionedIfMissing(configDir.resolve("common.toml"), COMMON_HEADER,
                COMMON_SECTION_KEYS, values);
        if (migrated > 0) {
            Localizedrain.LOGGER.info("[localizedrain] migrated legacy config {} into {} new file(s) under {}",
                    legacy, migrated, configDir);
        } else {
            // 目标文件已存在（例如新版本首次启动时已生成默认值）：旧值不会自动覆盖，至少要把差异说清楚，
            // 避免「旧配置里的 weatherMode=mobile 被静默丢回 patches」。同一份旧文件只提示一次。
            if (legacyWarningDue(legacy, configDir, "global")) {
                warnLegacyDifferences(configDir.resolve("global-weather.toml"), WEATHER_KEYS, values);
                markLegacyWarning(legacy, configDir, "global");
            }
        }
    }

    /**
     * 旧版本把「全局默认值」放在 {@code config/localizedrain/weather.toml}（SERVER）。
     * 现在默认值单独放在 {@code global-weather.toml}（COMMON，主菜单可编辑），
     * 新建存档时再复制成存档副本；升级时把旧全局文件复制成新的默认文件，保留用户已有设置。
     * 必须在 COMMON 配置加载之前调用（Localizedrain 构造函数里）。
     */
    public static void migrateGlobalDefaults() {
        Path directory = FMLPaths.CONFIGDIR.get().resolve(MOD_CONFIG_DIR);
        for (String[] pair : WORLD_CONFIG_FILES) {
            Path source = directory.resolve(pair[1]);
            Path target = directory.resolve(pair[0]);
            if (Files.exists(target) || Files.notExists(source)) {
                continue;
            }
            try {
                Files.copy(source, target);
                Localizedrain.LOGGER.info("[localizedrain] migrated global defaults {} -> {}", source, target);
            } catch (IOException exception) {
                Localizedrain.LOGGER.warn("[localizedrain] could not migrate global defaults {} to {}",
                        source, target, exception);
            }
        }
    }

    /**
     * 把旧版平铺写法的全局配置文件改成按节索引：{@code config/localizedrain/} 下的
     * {@code global-weather.toml}、{@code weather.toml}、{@code common.toml}。
     * 源码里的 {@code config/localizedrain/weather.toml} 是 SERVER 配置在「存档没有副本」时的后备路径，
     * 也必须迁移，否则 FML 会按分节规范把它当成缺键文件重置。原文件先备份成 {@code *.flat.bak}。
     * 存档内的 {@code weather.toml} 由 {@link #migrateWorldConfigsToSections(MinecraftServer)} 负责。
     *
     * @return 改写的文件数
     */
    public static int migrateFlatConfigsToSections() {
        int changed = 0;
        Path globalDir = FMLPaths.CONFIGDIR.get().resolve(MOD_CONFIG_DIR);
        changed += migrateFlat(globalDir.resolve("global-weather.toml"), WEATHER_HEADER, WEATHER_SECTION_KEYS);
        changed += migrateFlat(globalDir.resolve("weather.toml"), WEATHER_HEADER, WEATHER_SECTION_KEYS);
        changed += migrateFlat(globalDir.resolve("common.toml"), COMMON_HEADER, COMMON_SECTION_KEYS);
        return changed;
    }

    /** 只迁移当前存档目录里的 {@code weather.toml}（存档副本可能还是旧版平铺写法）。 */
    public static int migrateWorldConfigsToSections(MinecraftServer server) {
        Path directory = server.getWorldPath(new LevelResource("serverconfig")).resolve(MOD_CONFIG_DIR);
        return migrateFlat(directory.resolve("weather.toml"), WEATHER_HEADER, WEATHER_SECTION_KEYS);
    }

    private static int migrateFlat(Path file, String header, Map<String, List<String>> sectionKeys) {
        if (Files.notExists(file)) {
            return 0;
        }
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (String line : lines) {
                if (line.trim().startsWith("[")) {
                    return 0; // 已经是节结构：不重复迁移
                }
            }
            Map<String, String> flat = readTopLevelValues(file);
            List<String> known = flatten(sectionKeys);
            boolean hasKnown = false;
            for (String key : known) {
                if (flat.containsKey(key)) {
                    hasKnown = true;
                    break;
                }
            }
            if (!hasKnown) {
                return 0;
            }
            backup(file, ".flat.bak");
            writeAtomically(file, renderSections(header, sectionKeys, flat));
            Localizedrain.LOGGER.info("[localizedrain] migrated flat config {} into sectioned layout", file);
            return 1;
        } catch (IOException exception) {
            Localizedrain.LOGGER.warn("[localizedrain] could not migrate flat config {}", file, exception);
            return 0;
        }
    }

    /**
     * 把旧的 {@code filters.toml} / {@code global-filters.toml} 里仍然存在的三个列表并入目标天气文件的
     * {@code [filters]} 节（目标已有同名键时不覆盖），然后把旧文件改名为 {@code *.merged.bak}。
     *
     * @param directory 配置目录（全局或某个存档的 serverconfig/localizedrain）
     * @param weatherFile 目标天气文件（全局是 global-weather.toml，存档里是 weather.toml）
     * @return 合并进目标文件的键数
     */
    public static int mergeLegacyFilters(Path directory, Path weatherFile) {
        Map<String, String> filters = new LinkedHashMap<>();
        // 存档里的 filters.toml 更具体，优先；全局 global-filters.toml 只补缺
        collectFilterValues(directory.resolve("filters.toml"), filters);
        collectFilterValues(directory.resolve("global-filters.toml"), filters);
        if (filters.isEmpty()) {
            renameLegacy(directory.resolve("filters.toml"), ".merged.bak");
            renameLegacy(directory.resolve("global-filters.toml"), ".merged.bak");
            return 0;
        }
        if (Files.notExists(weatherFile)) {
            // 目标还没生成（例如首次启动、NeoForge 尚未落盘）：保留旧文件，等下一次启动再并。
            Localizedrain.LOGGER.info(
                    "[localizedrain] deferred legacy filter merge: {} does not exist yet", weatherFile);
            return 0;
        }
        try {
            List<String> lines = new ArrayList<>(Files.readAllLines(weatherFile, StandardCharsets.UTF_8));
            Map<String, String> existing = readAllValues(weatherFile);
            List<String> entries = new ArrayList<>();
            List<String> conflicts = new ArrayList<>();
            for (String key : WEATHER_SECTION_KEYS.get("filters")) {
                String legacy = filters.get(key);
                if (legacy == null) {
                    continue;
                }
                String current = existing.get(key);
                if (isUnsetValue(current)) {
                    entries.add(key + " = " + legacy);
                } else if (!current.equals(legacy)) {
                    conflicts.add(key);
                }
            }
            if (!entries.isEmpty()) {
                insertSectionEntries(lines, "filters", entries);
                writeAtomically(weatherFile, String.join("\n", lines) + "\n");
                Localizedrain.LOGGER.info("[localizedrain] merged {} legacy filter key(s) into {}",
                        entries.size(), weatherFile);
            }
            if (!conflicts.isEmpty()) {
                Localizedrain.LOGGER.warn(
                        "[localizedrain] kept current values for filter key(s) {}; legacy file(s) renamed to .merged.bak",
                        conflicts);
            }
            renameLegacy(directory.resolve("filters.toml"), ".merged.bak");
            renameLegacy(directory.resolve("global-filters.toml"), ".merged.bak");
            return entries.size();
        } catch (IOException exception) {
            Localizedrain.LOGGER.warn("[localizedrain] could not merge legacy filters into {}", weatherFile, exception);
            return 0;
        }
    }

    /** 空列表在 toml 里写作 {@code []}，README 里的默认值也是它；并档时必须把「键存在但值为空」当成未设置。 */
    private static boolean isUnsetValue(String value) {
        if (value == null || value.isBlank()) {
            return true;
        }
        String compact = value.replaceAll("\\s+", "");
        return compact.equals("[]") || compact.equals("[\"\"]");
    }

    private static void collectFilterValues(Path file, Map<String, String> values) {
        if (Files.notExists(file)) {
            return;
        }
        try {
            Map<String, String> read = readAllValues(file);
            for (String key : WEATHER_SECTION_KEYS.get("filters")) {
                String value = read.get(key);
                if (value != null && !isUnsetValue(value) && !values.containsKey(key)) {
                    values.put(key, value);
                }
            }
        } catch (IOException exception) {
            Localizedrain.LOGGER.warn("[localizedrain] could not read legacy filters {}", file, exception);
        }
    }

    private static void renameLegacy(Path file, String suffix) {
        if (Files.notExists(file)) {
            return;
        }
        Path backup = file.resolveSibling(file.getFileName() + suffix);
        try {
            Files.deleteIfExists(backup);
            Files.move(file, backup, StandardCopyOption.REPLACE_EXISTING);
            Localizedrain.LOGGER.info("[localizedrain] renamed legacy config {} -> {}", file, backup);
        } catch (IOException exception) {
            Localizedrain.LOGGER.warn("[localizedrain] could not rename legacy config {}", file, exception);
        }
    }

    /**
     * 启动自检：天气配置的键数必须等于代码里登记的分节键数（漏写一节或新增键忘记登记都会在这里暴露）。
     */
    public static void verifySectionCoverage() {
        verifyCoverage("weather", Config.WEATHER_SPEC, sectionPaths(WEATHER_SECTION_KEYS));
        verifyCoverage("common", Config.COMMON_SPEC, sectionPaths(COMMON_SECTION_KEYS));
    }

    /** 把「节 → 键」展开成「节.键」形式的完整路径，覆盖比对必须按完整路径，否则挪错节也发现不了。 */
    private static List<String> sectionPaths(Map<String, List<String>> sectionKeys) {
        List<String> paths = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : sectionKeys.entrySet()) {
            for (String key : entry.getValue()) {
                paths.add(entry.getKey() + "." + key);
            }
        }
        return paths;
    }

    private static void verifyCoverage(String name, Object spec, List<String> expected) {
        if (!(spec instanceof net.neoforged.neoforge.common.ModConfigSpec configSpec)) {
            return;
        }
        Map<String, Object> leaves = leafDefaults(configSpec);
        if (leaves.size() != expected.size()) {
            Localizedrain.LOGGER.error(
                    "[localizedrain] sectioned config coverage mismatch for {}: spec has {} leaf key(s), expected {}",
                    name, leaves.size(), expected.size());
        }
        Set<String> missing = new LinkedHashSet<>(expected);
        missing.removeAll(leaves.keySet());
        if (!missing.isEmpty()) {
            Localizedrain.LOGGER.error(
                    "[localizedrain] sectioned config is missing expected key(s) for {}: {}", name, missing);
        }
    }

    /**
     * 把（可能嵌套的）配置规范摊平成「完整路径 → 默认值」的叶子映射，供自定义校验使用。
     * 注意：{@code getSpec()} 的叶子是 {@code ValueSpec}（没有默认值），必须遍历 {@code getValues()}，
     * 它的叶子才是 {@code ConfigValue}。
     */
    static Map<String, Object> leafDefaults(net.neoforged.neoforge.common.ModConfigSpec spec) {
        Map<String, Object> values = new java.util.TreeMap<>();
        collectLeaves(spec.getValues(), "", values);
        return values;
    }

    private static void collectLeaves(com.electronwill.nightconfig.core.UnmodifiableConfig config, String prefix,
            Map<String, Object> values) {
        for (com.electronwill.nightconfig.core.UnmodifiableConfig.Entry entry : config.entrySet()) {
            String key = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
            Object value = entry.getValue();
            if (value instanceof net.neoforged.neoforge.common.ModConfigSpec.ConfigValue<?> configValue) {
                try {
                    values.put(key, configValue.getDefault());
                } catch (RuntimeException ignored) {
                    values.put(key, value);
                }
            } else if (value instanceof net.neoforged.neoforge.common.ModConfigSpec.ValueSpec valueSpec) {
                // 兜底：某些路径下 getValues() 也可能给出 ValueSpec，直接取它声明的默认值。
                try {
                    values.put(key, valueSpec.getDefault());
                } catch (RuntimeException ignored) {
                    values.put(key, value);
                }
            } else if (value instanceof com.electronwill.nightconfig.core.UnmodifiableConfig nested) {
                collectLeaves(nested, key, values);
            }
        }
    }

    private static void warnLegacyDifferences(Path target, List<String> keys, Map<String, String> legacy) {
        if (!Files.exists(target)) {
            return;
        }
        try {
            Map<String, String> current = readAllValues(target);
            List<String> differences = new ArrayList<>();
            for (String key : keys) {
                String oldValue = legacy.get(key);
                String newValue = current.get(key);
                if (oldValue != null && newValue != null && !oldValue.equals(newValue)) {
                    differences.add(key + " (" + oldValue + " -> " + newValue + ")");
                }
            }
            if (!differences.isEmpty()) {
                Localizedrain.LOGGER.warn("[localizedrain] legacy config values differ from {}; edit the new file to keep them: {}",
                        target, differences);
            }
        } catch (IOException ignored) {
            // 读失败只影响这条诊断，不影响启动
        }
    }

    /** 旧文件内容指纹（mtime + size）：同一份旧文件只告警一次，避免每次启动重复刷 WARN。 */
    private static boolean legacyWarningDue(Path legacy, Path markerDir, String scope) {
        try {
            String stamp = legacyStamp(legacy);
            Path marker = warningMarker(markerDir, scope);
            return Files.notExists(marker) || !stamp.equals(Files.readString(marker, StandardCharsets.UTF_8).trim());
        } catch (IOException exception) {
            return true;
        }
    }

    private static void markLegacyWarning(Path legacy, Path markerDir, String scope) {
        try {
            Path marker = warningMarker(markerDir, scope);
            Files.createDirectories(marker.getParent());
            Files.writeString(marker, legacyStamp(legacy), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            // 标记失败只影响去重，不影响启动
        }
    }

    private static Path warningMarker(Path markerDir, String scope) {
        return markerDir.resolve(".legacy-warned-" + scope);
    }

    private static String legacyStamp(Path legacy) throws IOException {
        return Files.getLastModifiedTime(legacy).toMillis() + ":" + Files.size(legacy);
    }

    /**
     * 已有存档的 {@code weather.toml} 是旧版 alpha 预先复制进去的（当时默认值就是 PATCHES）。
     * 升级后全局文件已迁移，但这些存档的文件不会被 {@link #migrateLegacyConfigs()} 改写，
     * 于是仍按旧默认启动。这里只在「存档里的值仍是默认 PATCHES」时把旧文件的 weatherMode 写回去：
     * 用户在其他存档里另选过模式的不会被覆盖。
     */
    public static int migrateWorldWeatherMode(MinecraftServer server) {
        Path legacy = FMLPaths.CONFIGDIR.get().resolve(LEGACY_FILE);
        if (Files.notExists(legacy)) {
            return 0;
        }
        Path directory = server.getWorldPath(new LevelResource("serverconfig")).resolve(MOD_CONFIG_DIR);
        Path marker = directory.resolve(".weatherMode-migrated");
        if (Files.exists(marker)) {
            // 每个存档只迁移一次：管理员把某个存档改回 PATCHES 后，下次启动不会被旧文件再覆盖
            return 0;
        }
        Map<String, String> legacyValues;
        try {
            legacyValues = readTopLevelValues(legacy);
        } catch (IOException exception) {
            return 0;
        }
        String legacyMode = legacyValues.get("weatherMode");
        if (legacyMode == null) {
            markWeatherModeMigrated(marker, "none"); // 旧文件没有该键：标记一次，避免每次启动重复读文件
            return 0;
        }
        Path weather = directory.resolve("weather.toml");
        if (Files.notExists(weather)) {
            // 存档还没有副本：不写标记，等下次启动副本生成后再迁移
            return 0;
        }
        try {
            List<String> lines = Files.readAllLines(weather, StandardCharsets.UTF_8);
            int index = findKeyLine(lines, "general", "weatherMode");
            if (index < 0) {
                markWeatherModeMigrated(marker, legacyMode);
                return 0;
            }
            String current = valueOf(lines.get(index));
            if (normalized(current).equals(normalized(legacyMode))
                    || !normalized(current).equals("PATCHES")) {
                markWeatherModeMigrated(marker, legacyMode);
                return 0; // 已是旧值，或用户在该存档里另选过模式
            }
            lines.set(index, "weatherMode = " + legacyMode);
            // 与 stripRemovedKeys 保持一致：用 \n 拼回并原子替换，避免 Windows 上把整个文件改成 CRLF
            writeAtomically(weather, String.join("\n", lines) + "\n");
            markWeatherModeMigrated(marker, legacyMode);
            Localizedrain.LOGGER.info("[localizedrain] migrated per-world weatherMode {} -> {} in {}",
                    current, legacyMode, weather);
            return 1;
        } catch (IOException exception) {
            Localizedrain.LOGGER.warn("[localizedrain] could not migrate per-world weatherMode in {}", weather, exception);
        }
        return 0;
    }

    private static void markWeatherModeMigrated(Path marker, String mode) {
        try {
            Files.createDirectories(marker.getParent());
            Files.writeString(marker, mode, StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            // 标记失败只影响去重，不影响启动
        }
    }

    private static String normalized(String value) {
        return value.replace("\"", "").trim().toUpperCase(Locale.ROOT);
    }

    /** 每存档配置是否就绪，用于启动日志。 */
    public static String worldConfigStatus(MinecraftServer server) {
        Path directory = server.getWorldPath(new LevelResource("serverconfig")).resolve(MOD_CONFIG_DIR);
        return "weather=" + Files.exists(directory.resolve("weather.toml"));
    }

    /** 每个存档 weather.toml 上一次被读取时的修改时间，用于 weatherMode 热重载检测。 */
    private static final Map<Path, Long> WEATHER_MODE_MTIMES = new HashMap<>();
    /** 非法 weatherMode 值只警告一次，避免用户编辑期间每次轮询都刷屏。 */
    private static final Set<String> WARNED_MODES = new HashSet<>();

    private static Path worldWeatherFile(ServerLevel level) {
        return level.getServer().getWorldPath(new LevelResource("serverconfig"))
                .resolve(MOD_CONFIG_DIR).resolve("weather.toml");
    }

    /**
     * 把当前存档 weather.toml 的 {@code [general] weatherMode} 行改成指定模式，供 {@code /weather mode} 持久化。
     *
     * @return false 表示无法持久化（文件不存在、没有 {@code [general]} 节或写入失败）；运行时切换仍会生效，只是重启后回退。
     */
    public static boolean writeWeatherMode(ServerLevel level, WeatherMode mode) {
        Path file = worldWeatherFile(level);
        if (Files.notExists(file)) {
            return false;
        }
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            int index = findKeyLine(lines, "general", "weatherMode");
            if (index < 0) {
                // 节结构里没有 [general] weatherMode：不擅自追加到文件末尾（会落在别的节里），交给用户/迁移处理
                Localizedrain.LOGGER.warn(
                        "[localizedrain] could not persist weatherMode to {}: no general.weatherMode entry", file);
                return false;
            }
            lines.set(index, "weatherMode = \"" + mode.name() + "\"");
            writeAtomically(file, String.join("\n", lines) + "\n");
            WEATHER_MODE_MTIMES.put(file, Files.getLastModifiedTime(file).toMillis());
            return true;
        } catch (IOException exception) {
            Localizedrain.LOGGER.warn("[localizedrain] could not persist weatherMode to {}", file, exception);
            return false;
        }
    }

    /**
     * 检测当前存档 weather.toml 是否被外部修改；有变化时返回解析到的模式，否则返回 {@code null}。
     * 第一次看到某文件只记录时间戳（启动时的读取不算修改）。
     */
    public static WeatherMode pollWeatherMode(ServerLevel level) {
        Path file = worldWeatherFile(level);
        try {
            if (Files.notExists(file)) {
                return null;
            }
            long modified = Files.getLastModifiedTime(file).toMillis();
            Long previous = WEATHER_MODE_MTIMES.put(file, modified);
            if (previous == null || previous == modified) {
                return null;
            }
            CommentedConfig parsed;
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                parsed = new TomlParser().parse(reader);
            }
            Object value = value(parsed, "general", "weatherMode");
            if (value == null) {
                // 旧版平铺文件：顶层 weatherMode（还没迁移时仍可用）
                value = parsed.get("weatherMode");
            }
            if (value == null) {
                return null;
            }
            Optional<WeatherMode> mode = WeatherMode.strictParse(String.valueOf(value));
            if (mode.isEmpty()) {
                // 非法值不覆盖最后一次有效模式（例如编辑器写到一半或写错枚举名）；按文件区分，避免多存档互相吞掉提示
                if (WARNED_MODES.add(file + "|" + value)) {
                    Localizedrain.LOGGER.warn(
                            "[localizedrain] ignoring invalid weatherMode '{}' in {}; keeping the last valid mode",
                            value, file);
                }
                return null;
            }
            // 解析成功后清掉该文件的警告键：同一个存档修正后再写错，会再次提示
            String prefix = file + "|";
            WARNED_MODES.removeIf(key -> key.startsWith(prefix));
            return mode.get();
        } catch (IOException | RuntimeException exception) {
            // 读取/解析失败保留上一次有效模式，下一轮文件变化后再试
            if (WARNED_MODES.add(file + "|parse-failure")) {
                Localizedrain.LOGGER.warn(
                        "[localizedrain] could not parse weather mode from {}; keeping the last valid mode",
                        file, exception);
            }
            return null;
        }
    }

    private static Object value(CommentedConfig config, String section, String key) {
        Object node = config.get(section);
        if (node instanceof CommentedConfig nested) {
            return nested.get(key);
        }
        return null;
    }

    /**
     * 为当前存档准备独立的 weather.toml。返回新建的文件数。
     * 失败只记警告，不影响启动（全局文件仍可作为后备）。
     */
    public static int seedWorldConfigs(MinecraftServer server, boolean beforeConfigLoad) {
        int created = 0;
        for (String[] pair : WORLD_CONFIG_FILES) {
            if (seedFile(server, pair[0], pair[1])) {
                created++;
            }
        }
        if (created > 0) {
            Localizedrain.LOGGER.info("[localizedrain] seeded {} per-world config file(s): {}",
                    created, worldConfigStatus(server));
        }
        Path directory = server.getWorldPath(new LevelResource("serverconfig")).resolve(MOD_CONFIG_DIR);
        // 顺序：先把存档副本转成节结构，再合并旧过滤器文件，最后按旧文件迁移 weatherMode 与清理废弃键
        migrateWorldConfigsToSections(server);
        mergeLegacyFilters(directory, directory.resolve("weather.toml"));
        stripRemovedKeys(server);
        int migrated = migrateWorldWeatherMode(server);
        if (migrated > 0) {
            Localizedrain.LOGGER.info("[localizedrain] migrated per-world weatherMode from legacy; {}: {}",
                    beforeConfigLoad ? "applies to this session" : "applies next start", worldConfigStatus(server));
        }
        // 已有存档的副本不会因全局文件更新而变化：把与旧文件的差异提示一次（每份旧文件、每个存档只提示一次）。
        Path legacy = FMLPaths.CONFIGDIR.get().resolve(LEGACY_FILE);
        if (Files.exists(legacy) && legacyWarningDue(legacy, directory, "world")) {
            try {
                Map<String, String> legacyValues = readTopLevelValues(legacy);
                warnLegacyDifferences(directory.resolve("weather.toml"), WEATHER_KEYS, legacyValues);
                markLegacyWarning(legacy, directory, "world");
            } catch (IOException ignored) {
                // 诊断失败不影响启动
            }
        }
        return created;
    }

    /**
     * 按 {@link #REMOVED_KEYS} 从全局与当前存档的配置文件中删除已废弃的键。
     * NeoForge 会忽略未知键，但保留会让用户以为修改它仍有效；返回删除的行数。
     */
    private static int stripRemovedKeys(MinecraftServer server) {
        Path globalDirectory = FMLPaths.CONFIGDIR.get().resolve(MOD_CONFIG_DIR);
        Path worldDirectory = server.getWorldPath(new LevelResource("serverconfig")).resolve(MOD_CONFIG_DIR);
        int removed = 0;
        for (Map.Entry<String, Set<String>> entry : REMOVED_KEYS.entrySet()) {
            removed += stripRemovedKeys(globalDirectory.resolve(entry.getKey()), entry.getValue());
            removed += stripRemovedKeys(worldDirectory.resolve(entry.getKey()), entry.getValue());
        }
        return removed;
    }

    private static int stripRemovedKeys(Path file, Set<String> removedKeys) {
        if (removedKeys.isEmpty() || Files.notExists(file)) {
            return 0;
        }
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            List<String> kept = new ArrayList<>(lines.size());
            int removed = 0;
            for (String line : lines) {
                String key = keyOf(line);
                if (!key.isEmpty() && removedKeys.contains(key)) {
                    removed++;
                } else {
                    kept.add(line);
                }
            }
            if (removed == 0) {
                return 0;
            }
            writeAtomically(file, String.join("\n", kept) + "\n");
            Localizedrain.LOGGER.info("[localizedrain] removed {} deprecated config key(s) from {}", removed, file);
            return removed;
        } catch (IOException exception) {
            Localizedrain.LOGGER.warn("[localizedrain] could not clean deprecated keys in {}", file, exception);
            return 0;
        }
    }

    /** 固定 LF 换行写回，并通过同目录临时文件原子替换，避免截断窗口与整文件换行符变化。 */
    private static void writeAtomically(Path file, String content) throws IOException {
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Files.writeString(temporary, content, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            // 成功 move 后临时文件已不存在；失败时清理半成品，避免 .tmp 在配置目录堆积。
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException ignored) {
                // 清理失败不覆盖原始异常
            }
        }
    }

    private static void backup(Path file, String suffix) {
        Path target = file.resolveSibling(file.getFileName() + suffix);
        try {
            Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException exception) {
            Localizedrain.LOGGER.warn("[localizedrain] could not back up {} to {}", file, target, exception);
        }
    }

    private static boolean seedFile(MinecraftServer server, String sourceName, String targetName) {
        try {
            Path directory = server.getWorldPath(new LevelResource("serverconfig")).resolve(MOD_CONFIG_DIR);
            Path target = directory.resolve(targetName);
            if (Files.exists(target)) {
                return false;
            }
            Path source = FMLPaths.CONFIGDIR.get().resolve(MOD_CONFIG_DIR).resolve(sourceName);
            if (!Files.exists(source)) {
                return false;
            }
            Files.createDirectories(directory);
            Files.copy(source, target);
            Localizedrain.LOGGER.info("[localizedrain] created per-world config {}", target);
            return true;
        } catch (Exception exception) {
            Localizedrain.LOGGER.warn("[localizedrain] failed to prepare per-world config {}", targetName, exception);
            return false;
        }
    }

    /** 按节结构写出迁移后的文件：每节带标题注释，只写旧配置里实际存在的键。 */
    private static String renderSections(String header, Map<String, List<String>> sectionKeys,
            Map<String, String> values) {
        StringBuilder text = new StringBuilder(header);
        for (Map.Entry<String, List<String>> section : sectionKeys.entrySet()) {
            List<String> present = new ArrayList<>();
            for (String key : section.getValue()) {
                String value = values.get(key);
                if (value != null && !value.isBlank()) {
                    present.add(key + " = " + value);
                }
            }
            if (present.isEmpty()) {
                continue;
            }
            text.append('\n').append("# ").append(SECTION_TITLES.getOrDefault(section.getKey(), section.getKey()))
                    .append('\n');
            text.append('[').append(section.getKey()).append("]\n");
            for (String line : present) {
                text.append(line).append('\n');
            }
        }
        return text.toString();
    }

    private static int writeSectionedIfMissing(Path target, String header, Map<String, List<String>> sectionKeys,
            Map<String, String> values) {
        if (Files.exists(target)) {
            return 0;
        }
        String text = renderSections(header, sectionKeys, values);
        if (text.length() <= header.length()) {
            // 旧文件里没有对应键：交给 NeoForge 生成带默认值/注释的新文件，不创建半空文件
            return 0;
        }
        try {
            Files.createDirectories(target.getParent());
            Files.writeString(target, text, StandardCharsets.UTF_8);
            return 1;
        } catch (IOException exception) {
            Localizedrain.LOGGER.warn("[localizedrain] could not write migrated config {}", target, exception);
            return 0;
        }
    }

    /** 在指定节的末尾插入若干行（节不存在则追加到文件末尾）。 */
    private static void insertSectionEntries(List<String> lines, String section, List<String> entries) {
        int header = -1;
        for (int index = 0; index < lines.size(); index++) {
            if (lines.get(index).trim().equals("[" + section + "]")) {
                header = index;
                break;
            }
        }
        if (header < 0) {
            if (!lines.isEmpty() && !lines.get(lines.size() - 1).isBlank()) {
                lines.add("");
            }
            lines.add("[" + section + "]");
            lines.addAll(entries);
            return;
        }
        int end = lines.size();
        for (int index = header + 1; index < lines.size(); index++) {
            if (lines.get(index).trim().startsWith("[")) {
                end = index;
                break;
            }
        }
        int insert = end;
        while (insert > header + 1 && lines.get(insert - 1).isBlank()) {
            insert--;
        }
        lines.addAll(insert, entries);
    }

    /**
     * 找到某个键所在的行号：优先匹配指定节内的键，其次才回退到任何位置的同名键。
     * 混合文件（迁移残留的顶层键 + 分节键）里，写在残留顶层键上会导致「命令成功但重启回退」。
     */
    private static int findKeyLine(List<String> lines, String section, String key) {
        int fallback = -1;
        String currentSection = "";
        for (int index = 0; index < lines.size(); index++) {
            String trimmed = lines.get(index).trim();
            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                currentSection = trimmed.substring(1, trimmed.length() - 1).trim();
                continue;
            }
            String current = keyOf(lines.get(index));
            if (current.isEmpty() || !current.equals(key)) {
                continue;
            }
            if (currentSection.equals(section)) {
                return index;
            }
            if (fallback < 0) {
                fallback = index;
            }
        }
        return fallback;
    }

    private static String keyOf(String line) {
        String trimmed = stripComment(line).trim();
        int equals = trimmed.indexOf('=');
        if (equals <= 0) {
            return "";
        }
        String key = trimmed.substring(0, equals).trim();
        return key.matches("[A-Za-z0-9_\\-]+") ? key : "";
    }

    private static String valueOf(String line) {
        String trimmed = stripComment(line).trim();
        int equals = trimmed.indexOf('=');
        return equals < 0 ? "" : trimmed.substring(equals + 1).trim();
    }

    /** 读取文件里所有键（顶层 + 各节），用于旧值差异比较与「是否已有该键」判断。 */
    private static Map<String, String> readAllValues(Path file) throws IOException {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        Map<String, String> values = new LinkedHashMap<>();
        for (int index = 0; index < lines.size(); index++) {
            String line = stripComment(lines.get(index)).trim();
            if (line.isEmpty() || line.startsWith("[")) {
                continue;
            }
            int equals = line.indexOf('=');
            if (equals <= 0) {
                continue;
            }
            String key = line.substring(0, equals).trim();
            if (!key.matches("[A-Za-z0-9_\\-]+")) {
                continue;
            }
            StringBuilder value = new StringBuilder(line.substring(equals + 1).trim());
            while (!isComplete(value) && index + 1 < lines.size()) {
                index++;
                value.append(' ').append(stripComment(lines.get(index)).trim());
            }
            values.putIfAbsent(key, value.toString().trim());
        }
        return values;
    }

    /** 读取 TOML 顶层键的原始值文本（跳过各节），不解释语义。 */
    private static Map<String, String> readTopLevelValues(Path file) throws IOException {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        Map<String, String> values = new LinkedHashMap<>();
        boolean inSection = false;
        for (int index = 0; index < lines.size(); index++) {
            String line = stripComment(lines.get(index)).trim();
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("[")) {
                inSection = true;
                continue;
            }
            if (inSection) {
                continue;
            }
            int equals = line.indexOf('=');
            if (equals <= 0) {
                continue;
            }
            String key = line.substring(0, equals).trim();
            if (!key.matches("[A-Za-z0-9_\\-]+")) {
                continue;
            }
            StringBuilder value = new StringBuilder(line.substring(equals + 1).trim());
            while (!isComplete(value) && index + 1 < lines.size()) {
                index++;
                value.append(' ').append(stripComment(lines.get(index)).trim());
            }
            values.putIfAbsent(key, value.toString().trim());
        }
        return values;
    }

    /** 去掉不在字符串里的 # 注释（配置文件里没有多行字符串）。 */
    private static String stripComment(String line) {
        boolean quoted = false;
        for (int index = 0; index < line.length(); index++) {
            char character = line.charAt(index);
            if (character == '"' && (index == 0 || line.charAt(index - 1) != '\\')) {
                quoted = !quoted;
            } else if (character == '#' && !quoted) {
                return line.substring(0, index);
            }
        }
        return line;
    }

    /** 值是否已完整：括号/花括号闭合且不在字符串里。 */
    private static boolean isComplete(CharSequence value) {
        int depth = 0;
        boolean quoted = false;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == '"' && (index == 0 || value.charAt(index - 1) != '\\')) {
                quoted = !quoted;
                continue;
            }
            if (quoted) {
                continue;
            }
            if (character == '[' || character == '{') {
                depth++;
            } else if (character == ']' || character == '}') {
                depth--;
            }
        }
        return !quoted && depth <= 0;
    }
}
