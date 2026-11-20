package com.spectrum255.localizedrain;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.toml.TomlParser;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code config/localizedrain/datapacks.toml}：配置目录数据包的总开关与逐包开关。
 *
 * <p>文件由模组维护：启动时扫描 {@code config/localizedrain/datapacks/} 下带
 * {@code pack.mcmeta} 的子目录，把目录名与 {@code pack.mcmeta} 里的显示名登记到这里
 * （新包默认启用）。用户可以修改每个条目的布尔值来单独关掉某个包，或把顶层
 * {@code enabled} 设为 false 忽略整个目录。改动需要重启游戏（数据包仓库只在启动时组装）。</p>
 *
 * <p>不注册为 ModConfigSpec：条目需要按实际存在的数据包动态生成。</p>
 */
public final class DatapackConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger(DatapackConfig.class);

    /** 包 id（目录名）→ 是否启用。 */
    private static final Map<String, Boolean> PACKS = new LinkedHashMap<>();
    /** 包 id → {@code pack.mcmeta} 里的显示名，用来写注释。 */
    private static final Map<String, String> NAMES = new LinkedHashMap<>();

    private static boolean globalEnabled = true;
    private static boolean loaded;
    private static boolean dirty;
    private static boolean warnedUnreadable;
    /** 文件解析失败时置位：只备份、不重写，避免把用户手改的逐包开关覆盖成全开。 */
    private static boolean preserveFile;

    private DatapackConfig() {
    }

    public static Path file() {
        return FMLPaths.CONFIGDIR.get().resolve("localizedrain").resolve("datapacks.toml");
    }

    /** 读取配置（幂等）。文件不存在或解析失败时按默认值放行并只警告一次。 */
    public static synchronized void load() {
        if (loaded) {
            return;
        }
        loaded = true;
        Path file = file();
        if (Files.notExists(file)) {
            dirty = true;
            return;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            CommentedConfig config = new TomlParser().parse(reader);
            Object enabled = config.get("enabled");
            if (enabled instanceof Boolean value) {
                globalEnabled = value;
            }
            if (config.get("packs") instanceof CommentedConfig table) {
                for (var entry : table.entrySet()) {
                    if (entry.getValue() instanceof Boolean enabledPack) {
                        PACKS.put(entry.getKey(), enabledPack);
                    }
                }
            }
        } catch (IOException | RuntimeException exception) {
            // 解析失败：保留原文件（只做一次 .broken 备份），不按默认值重写，
            // 否则会把用户手改的逐包开关覆盖成「全部启用」。
            preserveFile = true;
            dirty = true;
            if (!warnedUnreadable) {
                warnedUnreadable = true;
                LOGGER.warn("[localizedrain] could not read {}; assuming every config datapack is enabled "
                        + "and keeping the file untouched", file, exception);
            }
        }
    }

    /**
     * 整个配置目录是否启用。配置尚未加载时按 {@code true} 处理并留一次日志，
     * 避免时序问题把 {@code enabled = false} 静默忽略。
     */
    public static synchronized boolean enabled() {
        load();
        return globalEnabled;
    }

    /** 登记一个已发现的配置数据包：写入显示名；没有条目时默认启用。 */
    public static synchronized void discover(String id, String name) {
        load();
        if (name != null && !name.isBlank()) {
            NAMES.put(id, name);
        }
        if (!PACKS.containsKey(id)) {
            PACKS.put(id, Boolean.TRUE);
            dirty = true;
        }
    }

    /** 单个数据包是否启用（没有登记时默认启用）。 */
    public static synchronized boolean isEnabled(String id) {
        load();
        return PACKS.getOrDefault(id, Boolean.TRUE);
    }

    /** 把已发现的数据包写回配置：新增条目、保留用户改过的开关。 */
    public static synchronized void flush() {
        load();
        Path file = file();
        if (!dirty && Files.exists(file)) {
            return;
        }
        if (preserveFile) {
            // 解析失败：只备份一次，不覆盖原文件——用户可能只是写错了一个字符，重写会丢掉他的开关
            dirty = false;
            if (Files.exists(file)) {
                Path backup = file.resolveSibling(file.getFileName() + ".broken");
                if (Files.notExists(backup)) {
                    try {
                        Files.copy(file, backup);
                        LOGGER.warn("[localizedrain] kept unreadable {} untouched; backup written to {}",
                                file, backup);
                    } catch (IOException exception) {
                        LOGGER.warn("[localizedrain] could not back up {}", file, exception);
                    }
                }
            }
            return;
        }
        StringBuilder builder = new StringBuilder();
        builder.append("# Localized Rain - config datapacks\n");
        builder.append("# 数据包放在 config/localizedrain/datapacks/<name>/ 下，必须带 pack.mcmeta。\n");
        builder.append("# 顶层 enabled 控制整个目录；[packs] 下的条目按目录名单独开关。\n");
        builder.append("# 新增数据包会在启动时自动登记到本文件。改动需要重启游戏。\n\n");
        builder.append("enabled = ").append(globalEnabled).append("\n\n[packs]\n");
        for (Map.Entry<String, Boolean> entry : PACKS.entrySet()) {
            String name = NAMES.get(entry.getKey());
            if (name != null && !name.isBlank()) {
                builder.append("# ").append(name.replace('\n', ' ').replace('\r', ' ')).append('\n');
            }
            builder.append('"').append(escape(entry.getKey())).append("\" = ").append(entry.getValue()).append('\n');
        }
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Files.createDirectories(file.getParent());
            // 先写临时文件再原子替换：写到一半崩溃也不会留下半截 TOML
            Files.writeString(tmp, builder.toString(), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicFailed) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            dirty = false;
        } catch (IOException exception) {
            LOGGER.warn("[localizedrain] could not write {}", file, exception);
        } finally {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
                // 清理失败不影响本次写回结果
            }
        }
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
