package com.spectrum255.localizedrain;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackSelectionConfig;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackSource;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.event.AddPackFindersEvent;

/**
 * 允许把数据包直接放进 {@code config/localizedrain/datapacks/} 下。
 *
 * <p>每个子目录就是一个数据包，必须有 {@code pack.mcmeta}：
 * {@code <name>/pack.mcmeta} 与 {@code <name>/data/<namespace>/<registry>/...}。
 * 目录名只允许 {@code [a-z0-9_.-]}，其他子目录会被忽略并打警告。启动时会扫描目录，
 * 把每个包的目录名与 {@code pack.mcmeta} 里的显示名登记到 {@code datapacks.toml}
 * （新包默认启用），用户可以逐个关闭。是否为服务端数据包由 {@link DatapackConfig#enabled()}
 * 与逐包开关共同决定。新增数据包需要重启游戏（数据包仓库只在启动时组装；{@code /reload}
 * 只重读已选中的包）。</p>
 *
 * <p>这里只注册 {@link PackType#SERVER_DATA}：粒子、声音、语言文件等客户端资源必须走资源包。</p>
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
        if (event.getPackType() != PackType.SERVER_DATA) {
            return;
        }
        Path root = root();
        if (!Files.isDirectory(root)) {
            DatapackConfig.flush();
            return;
        }
        boolean global = DatapackConfig.enabled();
        if (!global) {
            Localizedrain.LOGGER.info("[localizedrain] config datapacks are disabled in {}",
                    DatapackConfig.file());
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
        // 目录顺序在不同文件系统上不一致；按目录名排序，保证多个包覆盖同一文件时的加载顺序稳定
        directories.sort((a, b) -> a.getFileName().toString().compareTo(b.getFileName().toString()));
        Set<String> added = new HashSet<>();
        for (Path directory : directories) {
            String name = directory.getFileName().toString();
            if (!name.matches(VALID_NAME)) {
                Localizedrain.LOGGER.warn(
                        "[localizedrain] ignoring config datapack '{}': folder names may only contain a-z, 0-9, '_', '.' and '-'",
                        name);
                continue;
            }
            Path meta = directory.resolve("pack.mcmeta");
            if (!Files.isRegularFile(meta)) {
                Localizedrain.LOGGER.warn(
                        "[localizedrain] ignoring config datapack '{}': pack.mcmeta is required", name);
                continue;
            }
            String description = descriptionOf(meta, name);
            DatapackConfig.discover(name, description);
            if (!global || !DatapackConfig.isEnabled(name)) {
                Localizedrain.LOGGER.info("[localizedrain] config datapack '{}' is disabled in {}",
                        name, DatapackConfig.file());
                continue;
            }
            if (!added.add(name)) {
                continue;
            }
            Pack pack = create(name, description, directory);
            if (pack == null) {
                Localizedrain.LOGGER.warn(
                        "[localizedrain] ignoring config datapack '{}': pack.mcmeta could not be read", name);
                continue;
            }
            event.addRepositorySource(consumer -> consumer.accept(pack));
            Localizedrain.LOGGER.info("[localizedrain] added config datapack '{}' ({}) from {}",
                    name, description, directory);
        }
        DatapackConfig.flush();
    }

    private static Pack create(String name, String description, Path directory) {
        PackLocationInfo location = new PackLocationInfo(
                ID_PREFIX + name,
                Component.literal(description),
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
        // pack.mcmeta 必须存在：readMetaAndCreate 会读取并校验它，缺失/损坏时返回 null
        return Pack.readMetaAndCreate(location, resources, PackType.SERVER_DATA,
                new PackSelectionConfig(true, Pack.Position.TOP, false));
    }

    /** 读取 pack.mcmeta 里的显示名；读不到时回退目录名（由 readMetaAndCreate 做最终校验）。 */
    private static String descriptionOf(Path meta, String fallback) {
        try (Reader reader = Files.newBufferedReader(meta, StandardCharsets.UTF_8)) {
            JsonElement root = JsonParser.parseReader(reader);
            if (!root.isJsonObject()) {
                return fallback;
            }
            JsonObject pack = root.getAsJsonObject().getAsJsonObject("pack");
            if (pack == null) {
                return fallback;
            }
            JsonElement description = pack.get("description");
            if (description == null) {
                return fallback;
            }
            if (description.isJsonPrimitive()) {
                return description.getAsString();
            }
            if (description.isJsonObject()) {
                JsonObject object = description.getAsJsonObject();
                if (object.has("text")) {
                    return object.get("text").getAsString();
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // readMetaAndCreate 会给出最终判断
        }
        return fallback;
    }
}
