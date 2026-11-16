package com.spectrum255.localizedrain;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.server.MinecraftServer;
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
    private static final String[] PER_WORLD_FILES = {"weather.toml", "filters.toml"};
    /** 已删除的配置键：旧 weather.toml 里可能残留，留着会让用户误以为还能改。 */
    private static final String REMOVED_WEATHER_KEY = "manualStormRadius";

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
            "spawnIntervalTicks", "spawnMinDistance", "spawnMaxDistance",
            "windChangeIntervalTicks", "windMinSpeed", "windMaxSpeed",
            "despawnDistance", "despawnGraceTicks",
            "stormSyncIntervalTicks", "falloffRatio", "lightningMultiplier");
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
        migrated += writeIfMissing(configDir.resolve("weather.toml"), """
                # Localized Rain — 天气与风暴设置（全局默认）
                # 每个存档会在 <存档>/serverconfig/localizedrain/weather.toml 下有独立副本。
                """, WEATHER_KEYS, values);
        migrated += writeIfMissing(configDir.resolve("filters.toml"), """
                # Localized Rain — 天气过滤器（全局默认）
                # 每个存档会在 <存档>/serverconfig/localizedrain/filters.toml 下有独立副本。
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
                warnLegacyDifferences(configDir.resolve("weather.toml"), WEATHER_KEYS, values);
                warnLegacyDifferences(configDir.resolve("filters.toml"), FILTER_KEYS, values);
                markLegacyWarning(legacy, configDir, "global");
            }
        }
    }

    private static void warnLegacyDifferences(Path target, List<String> keys, Map<String, String> legacy) {
        if (!Files.exists(target)) {
            return;
        }
        try {
            Map<String, String> current = readTopLevelValues(target);
            List<String> differences = new java.util.ArrayList<>();
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
                Files.write(weather, lines, StandardCharsets.UTF_8);
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

    /**
     * 为当前存档准备独立的 weather.toml / filters.toml。返回新建的文件数。
     * 失败只记警告，不影响启动（全局文件仍可作为后备）。
     */
    public static int seedWorldConfigs(MinecraftServer server, boolean beforeConfigLoad) {
        int created = 0;
        for (String fileName : PER_WORLD_FILES) {
            if (seedFile(server, fileName)) {
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
     * 从全局与当前存档的 {@code weather.toml} 中删除已废弃的键。
     * NeoForge 会忽略未知键，但保留会让用户以为修改它仍有效；返回删除的行数。
     */
    private static int stripRemovedKeys(MinecraftServer server) {
        int removed = stripRemovedKeys(FMLPaths.CONFIGDIR.get().resolve(MOD_CONFIG_DIR).resolve("weather.toml"));
        Path worldWeather = server.getWorldPath(new LevelResource("serverconfig"))
                .resolve(MOD_CONFIG_DIR).resolve("weather.toml");
        removed += stripRemovedKeys(worldWeather);
        return removed;
    }

    private static int stripRemovedKeys(Path file) {
        if (Files.notExists(file)) {
            return 0;
        }
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            List<String> kept = new java.util.ArrayList<>(lines.size());
            int removed = 0;
            for (String line : lines) {
                String trimmed = line.trim();
                int equals = trimmed.indexOf('=');
                String key = equals > 0 ? trimmed.substring(0, equals).trim() : "";
                if (REMOVED_WEATHER_KEY.equals(key)) {
                    removed++;
                } else {
                    kept.add(line);
                }
            }
            if (removed > 0) {
                Files.write(file, kept, StandardCharsets.UTF_8);
                Localizedrain.LOGGER.info("[localizedrain] removed deprecated config key '{}' from {}", REMOVED_WEATHER_KEY, file);
            }
            return removed;
        } catch (IOException exception) {
            Localizedrain.LOGGER.warn("[localizedrain] could not clean deprecated keys in {}", file, exception);
            return 0;
        }
    }

    private static boolean seedFile(MinecraftServer server, String fileName) {
        try {
            Path directory = server.getWorldPath(new LevelResource("serverconfig")).resolve(MOD_CONFIG_DIR);
            Path target = directory.resolve(fileName);
            if (Files.exists(target)) {
                return false;
            }
            Path source = FMLPaths.CONFIGDIR.get().resolve(MOD_CONFIG_DIR).resolve(fileName);
            if (!Files.exists(source)) {
                return false;
            }
            Files.createDirectories(directory);
            Files.copy(source, target);
            Localizedrain.LOGGER.info("[localizedrain] created per-world config {}", target);
            return true;
        } catch (Exception exception) {
            Localizedrain.LOGGER.warn("[localizedrain] failed to prepare per-world config {}", fileName, exception);
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
