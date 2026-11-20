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
     * 标签存在（且非空）时作为**白名单交集**：档案必须同时满足「在标签内」与「{@code natural=true}」；
     * 没有标签时回退到档案里的 {@code "natural": true}（默认值）。
     */
    public static final TagKey<StormProfile> NATURAL_TAG =
            TagKey.create(REGISTRY_KEY, ResourceLocation.fromNamespaceAndPath(Localizedrain.MODID, "natural"));

    /** 1.7.0 起随模组附带的风暴档案；默认不参与自然生成。 */
    private static final java.util.Set<ResourceLocation> PRESET_IDS = java.util.Set.of(
            presetId("snow"), presetId("blizzard"), presetId("sandstorm"), presetId("fog"),
            presetId("overcast"), presetId("ashstorm"), presetId("soulmist"),
            presetId("voidstorm"), presetId("voidhaze"));

    /** 每个维度只提示一次「自然风暴池为空」，避免每次生成检查都刷日志。 */
    private static final java.util.Set<ResourceKey<Level>> WARNED_EMPTY =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    private static ResourceLocation presetId(String path) {
        return ResourceLocation.fromNamespaceAndPath(Localizedrain.MODID, path);
    }

    private StormProfiles() {}

    /** 是否为内置新增风暴预设（默认关闭，由 {@code enableBuiltinPresets} 控制是否进入自然池）。 */
    public static boolean isPreset(ResourceLocation profileId) {
        return PRESET_IDS.contains(profileId);
    }

    public static Optional<Holder.Reference<StormProfile>> byId(Level level, ResourceLocation id) {
        if (level == null || id == null) {
            return Optional.empty();    // 数据包 id 可能来自损坏存档/外部 API，null 不应变成 NPE
        }
        Registry<StormProfile> registry = level.registryAccess().registry(REGISTRY_KEY).orElse(null);
        return registry == null ? Optional.empty() : registry.getHolder(id);
    }

    /**
     * 自然生成池：标签存在时为白名单交集——档案必须同时在 {@link #NATURAL_TAG} 标签里，
     * 且档案自己的 {@code natural} 字段为 {@code true}（默认 true）；没有标签时回退到 {@code natural=true} 的档案。
     *
     * <p>标签只做「白名单追加」，不会覆盖档案自己的 {@code natural} 字段：显式写 {@code natural=false}
     * 的档案永远不进入自然池，即使被加进标签也一样。</p>
     *
     * <p>内置预设默认关闭，但只在「无天光维度」（下界/末地，{@code skylight=forbidden}）保留，
     * 避免关掉开关后这两个维度的自然风暴池变空。</p>
     */
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
            // 标签是白名单，但档案自己的 natural=false 始终优先：否则数据包作者写 false 会被标签静默覆盖
            if (!profile.natural()) {
                continue;
            }
            if (!tagged || holder.is(NATURAL_TAG)) {
                pool.add(holder);
            }
        }
        if (pool.isEmpty()) {
            if (WARNED_EMPTY.add(level.dimension())) {
                Localizedrain.LOGGER.warn("[localizedrain] natural storm pool for {} is empty "
                                + "(enableBuiltinPresets={}); no natural storms will spawn there. "
                                + "Add a non-preset profile with \"natural\": true or include it in the natural tag.",
                        level.dimension().location(), Config.builtinPresetsEnabled());
            }
        } else {
            // 池恢复非空后允许下次再提示（例如数据包修好后又被改坏）
            WARNED_EMPTY.remove(level.dimension());
        }
        return pool;
    }

    /** 服务器停止 / 配置重载 / 数据包重载后重新允许「自然池为空」告警。 */
    public static void resetWarnings() {
        WARNED_EMPTY.clear();
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
