package com.spectrum255.localizedrain.api;

import com.spectrum255.localizedrain.Config;
import com.spectrum255.localizedrain.Localizedrain;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tags.TagKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;

/** 风暴档案注册表访问器。注册表是数据包注册表，内容可随数据包重载替换，因此不缓存 Holder。 */
public final class StormProfiles {
    public static final ResourceKey<Registry<StormProfile>> REGISTRY_KEY =
            ResourceKey.createRegistryKey(ResourceLocation.fromNamespaceAndPath(Localizedrain.MODID, "storm_profile"));

    /**
     * 自然生成池标签：`data/<命名空间>/tags/localizedrain/storm_profile/natural.json`。
     * 标签存在（且非空）时以标签成员为准，否则回退到档案里的 {@code "natural": true}。
     */
    public static final TagKey<StormProfile> NATURAL_TAG =
            TagKey.create(REGISTRY_KEY, ResourceLocation.fromNamespaceAndPath(Localizedrain.MODID, "natural"));

    /** 1.7.0 起随模组附带的风暴档案；默认不参与自然生成。 */
    private static final java.util.Set<ResourceLocation> PRESET_IDS = java.util.Set.of(
            presetId("snow"), presetId("blizzard"), presetId("sandstorm"), presetId("fog"),
            presetId("overcast"), presetId("ashstorm"), presetId("soulmist"),
            presetId("voidstorm"), presetId("voidhaze"));

    private static ResourceLocation presetId(String path) {
        return ResourceLocation.fromNamespaceAndPath(Localizedrain.MODID, path);
    }

    private StormProfiles() {}

    /** 是否为内置新增风暴预设（默认关闭，由 {@code enableBuiltinPresets} 控制是否进入自然池）。 */
    public static boolean isPreset(ResourceLocation profileId) {
        return PRESET_IDS.contains(profileId);
    }

    public static Optional<Holder.Reference<StormProfile>> byId(Level level, ResourceLocation id) {
        Registry<StormProfile> registry = level.registryAccess().registry(REGISTRY_KEY).orElse(null);
        return registry == null ? Optional.empty() : registry.getHolder(id);
    }

    /** 自然生成池：优先使用 {@link #NATURAL_TAG} 标签成员，否则回退到 {@code natural=true} 的档案。 */
    public static List<Holder.Reference<StormProfile>> naturalPool(Level level) {
        Registry<StormProfile> registry = level.registryAccess().registry(REGISTRY_KEY).orElse(null);
        if (registry == null) {
            return List.of();
        }
        boolean tagged = registry.getTag(NATURAL_TAG).map(set -> set.size() > 0).orElse(false);
        List<Holder.Reference<StormProfile>> pool = new ArrayList<>();
        for (Holder.Reference<StormProfile> holder : registry.holders().toList()) {
            StormProfile profile = holder.value();
            // 内置新增预设默认关闭，但它们若只在无天光维度生成（skylight=forbidden），
            // 关掉会让下界自然风暴池变空；这类档案保留，主世界本来也看不到它们。
            if (!Config.builtinPresetsEnabled() && isPreset(holder.key().location())
                    && profile.skylight() != SkylightMode.FORBIDDEN) {
                continue;
            }
            if (tagged ? holder.is(NATURAL_TAG) : profile.natural()) {
                pool.add(holder);
            }
        }
        return pool;
    }

    /** 某个风暴档案所属的标签 id 列表（读档时由数据包决定，可用于自定义生成逻辑）。 */
    public static List<ResourceLocation> tagIds(Level level, ResourceLocation profileId) {
        Registry<StormProfile> registry = level.registryAccess().registry(REGISTRY_KEY).orElse(null);
        if (registry == null) {
            return List.of();
        }
        return registry.getHolder(profileId)
                .map(holder -> holder.tags().map(TagKey::location).toList())
                .orElse(List.of());
    }

    /** 某个标签下的风暴档案 id 列表。 */
    public static List<ResourceLocation> idsInTag(Level level, TagKey<StormProfile> tag) {
        Registry<StormProfile> registry = level.registryAccess().registry(REGISTRY_KEY).orElse(null);
        if (registry == null) {
            return List.of();
        }
        return registry.getTag(tag)
                .map(set -> set.stream()
                        .map(holder -> holder.unwrapKey().map(key -> key.location()).orElse(null))
                        .filter(Objects::nonNull)
                        .toList())
                .orElse(List.of());
    }

    public static List<ResourceLocation> ids(Level level) {
        Registry<StormProfile> registry = level.registryAccess().registry(REGISTRY_KEY).orElse(null);
        return registry == null ? List.of() : List.copyOf(registry.keySet());
    }

    /** 按 weight 加权抽一个自然档案；池为空返回 empty。 */
    public static Optional<Holder.Reference<StormProfile>> randomNatural(Level level, RandomSource random) {
        return randomNatural(level, random, profile -> true);
    }

    /** 按 weight 加权抽一个自然档案，只考虑 {@code filter} 通过的档案（洞穴风暴只接受降雨档案）。 */
    public static Optional<Holder.Reference<StormProfile>> randomNatural(Level level, RandomSource random,
                                                                        Predicate<StormProfile> filter) {
        List<Holder.Reference<StormProfile>> pool = naturalPool(level);
        int total = 0;
        for (Holder.Reference<StormProfile> holder : pool) {
            if (filter.test(holder.value())) {
                total += holder.value().weight();
            }
        }
        if (total <= 0) {
            return Optional.empty();
        }
        int roll = random.nextInt(total);
        for (Holder.Reference<StormProfile> holder : pool) {
            if (!filter.test(holder.value())) {
                continue;
            }
            roll -= holder.value().weight();
            if (roll < 0) {
                return Optional.of(holder);
            }
        }
        return Optional.empty();
    }

    /** 服务器启动时打一行日志，便于确认数据包档案是否生效。 */
    public static void logLoaded(MinecraftServer server) {
        var registry = server.registryAccess().registry(REGISTRY_KEY).orElse(null);
        if (registry == null) {
            Localizedrain.LOGGER.warn("[localizedrain] storm profile registry missing");
            return;
        }
        Localizedrain.LOGGER.info("[localizedrain] loaded {} storm profiles: {}",
                registry.size(), registry.keySet());
        // 未注册的天气类型在运行时会静默回退成晴天：启动时集中提示一次，便于定位数据包笔误
        for (Holder.Reference<StormProfile> holder : registry.holders().toList()) {
            int index = 0;
            for (StormProfile.Stage stage : holder.value().stages()) {
                if (WeatherTypes.resolve(server.overworld(), stage.type()) == null) {
                    Localizedrain.LOGGER.warn(
                            "[localizedrain] storm profile {} stage {} uses unregistered weather type {}; it will behave as clear",
                            holder.key().location(), index, stage.type());
                }
                index++;
            }
        }
    }
}
