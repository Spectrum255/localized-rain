package com.spectrum255.localizedrain;

import com.spectrum255.localizedrain.api.WeatherMode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.fml.loading.FMLPaths;

/**
 * 配置文件的辅助逻辑。
 *
 * <p>天气与过滤器配置注册为 {@link net.neoforged.neoforge.common.ModConfig.Type#SERVER}：
 * <ul>
 *   <li>全局默认：{@code config/localizedrain/weather.toml}、{@code config/localizedrain/filters.toml}
 *       （FML 的 default base path）</li>
 *   <li>每个存档：{@code <存档>/serverconfig/localizedrain/*.toml}（FML 的 override path，存在时优先于全局文件）</li>
 * </ul>
 * FML 只在存档目录里**已有**该文件时才使用它，不会自动复制。这里在服务器启动前把全局文件复制到
 * 存档目录（仅当目标不存在），使每个存档从第一次启动起就有独立副本；之后的修改互不影响。</p>
 */
public final class ConfigFiles {
    private static final String MOD_CONFIG_DIR = "localizedrain";
    private static final String LEGACY_FILE = "localizedrain-common.toml";
    /** 需要按存档独立的 SERVER 配置文件：全局文件只作为新建存档的默认值。 */
    /**
     * 每存档配置：{全局默认文件（COMMON，主菜单可编辑）, 存档副本文件（SERVER）} 成对出现。
     * 新建存档时把全局默认文件复制成存档副本。
     */
    private static final String[][] WORLD_CONFIG_FILES = {
            {"global-weather.toml", "weather.toml"},
            {"global-filters.toml", "filters.toml"}
    };
    /** 已删除的配置键：旧文件里可能残留，留着会让用户误以为还能改（按文件名分组）。 */
    private static final Map<String, Set<String>> REMOVED_KEYS = removedKeys();

    /** 用有序映射定义，保证遍历（以及清理日志）的顺序稳定、可预期。 */
    private static Map<String, Set<String>> removedKeys() {
        Map<String, Set<String>> keys = new LinkedHashMap<>();
        Set<String> weatherKeys = Set.of("manualStormRadius");
        keys.put("weather.toml", weatherKeys);
        keys.put("global-weather.toml", weatherKeys);
        return Collections.unmodifiableMap(keys);
    }

    /**
     * 旧版单文件配置里的顶层键。迁移时按新文件拆分：
     * {@code weather.toml} 只拿天气/风暴键，{@code filters.toml} 拿三个过滤器列表，
     * {@code common.toml} 拿三个调试开关。旧文件本身保留，作为备份。
     */
    private static final List<String> WEATHER_KEYS = List.of(
            "minClearTicks", "maxClearTicks", "minRainTicks", "maxRainTicks",
            "minThunderDelayTicks", "maxThunderDelayTicks", "minThunderTicks", "maxThunderTicks",
            "weatherSimulationIntervalTicks", "scan3dSubstates", "stateRetentionDays",
            "maxStoredStates", "maxParentEntries", "maxColumnScansPerTick",
            "weatherMode", "isolatedBiomeWeather", "naturalCaveStorms", "caveStormRadius",
            "maxStormsPerDimension", "maxStormsPerPlayer", "maxManualStormsPerDimension",
            "spawnIntervalTicks", "spawnMinDistance", "spawnMaxDistance", "naturalStormSpawnChance",
            "stormSpawnPlacement",
            "windChangeIntervalTicks", "windMinSpeed", "windMaxSpeed",
            "despawnDistance", "despawnGraceTicks",
            "stormSyncIntervalTicks", "falloffRatio", "lightningMultiplier",
            "stormRadiusBase", "stormRadiusVariance");
    private static final List<String> FILTER_KEYS = List.of(
            "weatherBiomeWhitelist", "weatherBiomeBlacklist", "weatherBiomeOverrides");
    private static final List<String> COMMON_KEYS = List.of(
            "debugLogging", "logWeatherChanges", "logStormSpawns");

    private ConfigFiles() {}

    /**
     * 把旧版 {@code config/localizedrain-common.toml} 拆分迁移到 {@code config/localizedrain/} 下的三个文件。
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
        migrated += writeIfMissing(configDir.resolve("global-weather.toml"), """
                # Localized Rain — 天气与风暴默认值（全局，主菜单可编辑）
                # 新建存档时会复制成 <存档>/serverconfig/localizedrain/weather.toml。
                """, WEATHER_KEYS, values);
        migrated += writeIfMissing(configDir.resolve("global-filters.toml"), """
                # Localized Rain — 天气过滤器默认值（全局，主菜单可编辑）
                # 新建存档时会复制成 <存档>/serverconfig/localizedrain/filters.toml。
                """, FILTER_KEYS, values);
        migrated += writeIfMissing(configDir.resolve("common.toml"), """
                # Localized Rain — 调试开关（所有存档共用）
                """, COMMON_KEYS, values);
        if (migrated > 0) {
            Localizedrain.LOGGER.info("[localizedrain] migrated legacy config {} into {} new file(s) under {}",
                    legacy, migrated, configDir);
        } else {
            // 目标文件已存在（例如新版本首次启动时已生成默认值）：旧值不会自动覆盖，至少要把差异说清楚，
            // 避免「旧配置里的 weatherMode=mobile 被静默丢回 patches」。同一份旧文件只提示一次。
            if (legacyWarningDue(legacy, configDir, "global")) {
                warnLegacyDifferences(configDir.resolve("global-weather.toml"), WEATHER_KEYS, values);
                warnLegacyDifferences(configDir.resolve("global-filters.toml"), FILTER_KEYS, values);
                markLegacyWarning(legacy, configDir, "global");
            }
        }
    }

    /**
     * 旧版本把「全局默认值」放在 {@code config/localizedrain/weather.toml} 与 {@code filters.toml}（SERVER）。
     * 现在默认值单独放在 {@code global-weather.toml} / {@code global-filters.toml}（COMMON，主菜单可编辑），
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

    private static void warnLegacyDifferences(Path target, List<String> keys, Map<String, String> legacy) {
        if (!Files.exists(target)) {
            return;
        }
        try {
            Map<String, String> current = readTopLevelValues(target);
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
            for (int index = 0; index < lines.size(); index++) {
                String trimmed = lines.get(index).trim();
                int equals = trimmed.indexOf('=');
                if (equals <= 0 || !trimmed.substring(0, equals).trim().equals("weatherMode")) {
                    continue;
                }
                String current = trimmed.substring(equals + 1).trim();
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
            }
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
        return value.replace("\"", "").trim().toUpperCase(java.util.Locale.ROOT);
    }

    /** 每存档配置是否就绪，用于启动日志。 */
    public static String worldConfigStatus(MinecraftServer server) {
        Path directory = server.getWorldPath(new LevelResource("serverconfig")).resolve(MOD_CONFIG_DIR);
        return "weather=" + Files.exists(directory.resolve("weather.toml"))
                + ", filters=" + Files.exists(directory.resolve("filters.toml"));
    }

    /** 每个存档 weather.toml 上一次被读取时的修改时间，用于 weatherMode 热重载检测。 */
    private static final Map<Path, Long> WEATHER_MODE_MTIMES = new HashMap<>();

    private static Path worldWeatherFile(ServerLevel level) {
        return level.getServer().getWorldPath(new LevelResource("serverconfig"))
                .resolve(MOD_CONFIG_DIR).resolve("weather.toml");
    }

    /**
     * 把当前存档 weather.toml 的 {@code weatherMode} 行改成指定模式，供 {@code /weather mode} 持久化。
     * 文件不存在时直接返回（运行时切换仍会生效，只是重启后回退）。
     */
    public static void writeWeatherMode(ServerLevel level, WeatherMode mode) {
        Path file = worldWeatherFile(level);
        if (Files.notExists(file)) {
            return;
        }
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            boolean replaced = false;
            for (int index = 0; index < lines.size(); index++) {
                String trimmed = lines.get(index).trim();
                int equals = trimmed.indexOf('=');
                if (equals <= 0 || !trimmed.substring(0, equals).trim().equals("weatherMode")) {
                    continue;
                }
                lines.set(index, "weatherMode = \"" + mode.name() + "\"");
                replaced = true;
                break;
            }
            if (!replaced) {
                lines.add("weatherMode = \"" + mode.name() + "\"");
            }
            writeAtomically(file, String.join("\n", lines) + "\n");
            WEATHER_MODE_MTIMES.put(file, Files.getLastModifiedTime(file).toMillis());
        } catch (IOException exception) {
            Localizedrain.LOGGER.warn("[localizedrain] could not persist weatherMode to {}", file, exception);
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
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (!trimmed.startsWith("weatherMode")) {
                    continue;
                }
                int equals = trimmed.indexOf('=');
                if (equals < 0) {
                    continue;
                }
                return WeatherMode.parse(trimmed.substring(equals + 1).trim().replace("\"", ""));
            }
        } catch (IOException exception) {
            // 读取失败忽略，下一轮再试
        }
        return null;
    }

    /**
     * 为当前存档准备独立的 weather.toml / filters.toml。返回新建的文件数。
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
        stripRemovedKeys(server);
        Path directory = server.getWorldPath(new LevelResource("serverconfig")).resolve(MOD_CONFIG_DIR);
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
                warnLegacyDifferences(directory.resolve("filters.toml"), FILTER_KEYS, legacyValues);
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
                String trimmed = line.trim();
                int equals = trimmed.indexOf('=');
                String key = equals > 0 ? trimmed.substring(0, equals).trim() : "";
                if (removedKeys.contains(key)) {
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

    private static int writeIfMissing(Path target, String header, List<String> keys, Map<String, String> values) {
        if (Files.exists(target)) {
            return 0;
        }
        StringBuilder text = new StringBuilder(header);
        int written = 0;
        for (String key : keys) {
            String value = values.get(key);
            if (value == null || value.isBlank()) {
                continue;
            }
            text.append(key).append(" = ").append(value).append('\n');
            written++;
        }
        if (written == 0) {
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

    /** 读取 TOML 顶层键的原始值文本（含多行列表/引号），不解释语义。 */
    private static Map<String, String> readTopLevelValues(Path file) throws IOException {
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
