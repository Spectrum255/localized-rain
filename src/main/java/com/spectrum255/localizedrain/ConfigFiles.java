package com.spectrum255.localizedrain;

import java.nio.file.Files;
import java.nio.file.Path;
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
    /** 需要按存档独立的 SERVER 配置文件：全局文件只作为新建存档的默认值。 */
    private static final String[] PER_WORLD_FILES = {"weather.toml", "filters.toml"};

    private ConfigFiles() {}

    /**
     * 为当前存档准备独立的 weather.toml / filters.toml。返回新建的文件数。
     * 失败只记警告，不影响启动（全局文件仍可作为后备）。
     */
    public static int seedWorldConfigs(MinecraftServer server) {
        int created = 0;
        for (String fileName : PER_WORLD_FILES) {
            if (seedFile(server, fileName)) {
                created++;
            }
        }
        return created;
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
}
