package com.spectrum255.localizedrain;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackSelectionConfig;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackCompatibility;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.world.flag.FeatureFlags;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.event.AddPackFindersEvent;

/**
 * 允许把数据包直接放进 {@code config/localizedrain/datapacks/} 下。
 *
 * <p>每个子目录就是一个数据包（{@code pack.mcmeta} 可省略）：
 * {@code <name>/data/<namespace>/<registry>/...}。目录名只允许 {@code [a-z0-9_.-]}，
 * 其他子目录会被忽略并打警告。该目录在模组构造时自动创建；是否加载由
 * {@code datapacks.toml} 的 {@code loadConfigDatapacks} 控制。新增数据包需要重启游戏
 * （数据包仓库只在启动时组装；{@code /reload} 只重读已选中的包）。</p>
 */
public final class ConfigDatapacks {
    private static final String ID_PREFIX = "localizedrain/config/";
    private static final String VALID_NAME = "[a-z0-9_.-]+";

    private ConfigDatapacks() {
    }

    public static Path root() {
        return FMLPaths.CONFIGDIR.get().resolve("localizedrain").resolve("datapacks");
    }

    public static void onAddPackFinders(AddPackFindersEvent event) {
        if (event.getPackType() != PackType.SERVER_DATA || !DatapackConfig.enabled()) {
            return;
        }
        Path root = root();
        if (!Files.isDirectory(root)) {
            return;
        }
        List<Path> directories = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(root)) {
            for (Path path : stream) {
                if (Files.isDirectory(path)) {
                    directories.add(path);
                }
            }
        } catch (IOException exception) {
            Localizedrain.LOGGER.warn("[localizedrain] cannot list config datapacks in {}", root, exception);
            return;
        }
        for (Path directory : directories) {
            String name = directory.getFileName().toString();
            if (!name.matches(VALID_NAME)) {
                Localizedrain.LOGGER.warn(
                        "[localizedrain] ignoring config datapack '{}': folder names may only contain a-z, 0-9, '_', '.' and '-'",
                        name);
                continue;
            }
            Pack pack = create(name, directory);
            if (pack != null) {
                event.addRepositorySource(consumer -> consumer.accept(pack));
                Localizedrain.LOGGER.info("[localizedrain] added config datapack '{}' from {}", name, directory);
            }
        }
    }

    private static Pack create(String name, Path directory) {
        PackLocationInfo location = new PackLocationInfo(
                ID_PREFIX + name,
                Component.literal("Localized Rain: " + name),
                PackSource.BUILT_IN,
                Optional.empty());
        Pack.ResourcesSupplier resources = new Pack.ResourcesSupplier() {
            @Override
            public PackResources openPrimary(PackLocationInfo info) {
                return new PathPackResources(info, directory);
            }

            @Override
            public PackResources openFull(PackLocationInfo info, Pack.Metadata metadata) {
                return new PathPackResources(info, directory);
            }
        };
        // 直接合成 Metadata，不要求用户写 pack.mcmeta；版本按当前数据版本标记为兼容
        Pack.Metadata metadata = new Pack.Metadata(
                Component.literal("User datapack for Localized Rain: " + name),
                PackCompatibility.COMPATIBLE,
                FeatureFlags.DEFAULT_FLAGS,
                List.of(),
                false);
        return new Pack(location, resources, metadata, new PackSelectionConfig(true, Pack.Position.TOP, false));
    }
}
