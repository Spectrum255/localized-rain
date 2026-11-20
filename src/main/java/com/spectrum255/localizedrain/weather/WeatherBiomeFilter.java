package com.spectrum255.localizedrain.weather;

import com.mojang.logging.LogUtils;
import com.spectrum255.localizedrain.Config;
import com.spectrum255.localizedrain.api.WeatherType;
import com.spectrum255.localizedrain.api.WeatherTypes;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.biome.Biome;
import org.slf4j.Logger;

/**
 * 生物群系天气白名单、黑名单、强制天气与自然天气池。
 *
 * <p>三条名单都是「生物群系选择器=天气」的格式。选择器支持三种写法：</p>
 * <ul>
 *   <li>{@code minecraft:desert} —— 精确的群系 id；</li>
 *   <li>{@code minecraft:*} —— 整个命名空间；</li>
 *   <li>{@code #minecraft:is_ocean} —— 群系标签（标签内容在服务器启动/配置重载时解析）。</li>
 * </ul>
 *
 * <p>语义：</p>
 * <ul>
 *   <li><b>强制天气</b>（{@code weatherBiomeOverrides}）：命中的群系永远只有这一种天气。
 *       同一群系命中多条时按配置顺序<b>第一条</b>生效；一条规则里只能写一个天气。</li>
 *   <li><b>白名单</b>（{@code weatherBiomeWhitelist}）：命中的群系自然循环只会在列出的天气里抽取。
 *       为空时使用默认自然天气池（{@code #localizedrain:natural} 标签，标签不存在则为内置晴/雨/雷）。</li>
 *   <li><b>黑名单</b>（{@code weatherBiomeBlacklist}）：命中的群系永远不会出现列出的天气，
 *       但仍会在剩余天气里循环；不会把整个群系排除出天气循环。</li>
 * </ul>
 *
 * <p>没有显式白名单的群系，如果本身不降水（{@code Biome#hasPrecipitation() == false}，例如下界/末地），
 * 会自动把「会降水或会打雷」的天气剔除出池子，因此默认永远是晴天；一旦显式白名单点名，则以白名单为准。</p>
 *
 * <p>按群系片缓存判定结果，热路径只做一次 map 查找。配置重载或服务器启动时调用 {@link #invalidate()}。</p>
 */
public final class WeatherBiomeFilter {
    private static final Logger LOGGER = LogUtils.getLogger();

    /** 软锁定：自然循环没有非晴天天气可抽（名单剔除、干燥群系默认等），但指令/API 仍可手动覆盖。 */
    private static final Decision LOCKED_CLEAR = new Decision(null, List.of(), true, false);
    /** 硬锁定：配置强制晴天，任何人都不能在该群系制造天气（包括指令/API/自然风暴）。 */
    private static final Decision HARD_LOCKED_CLEAR = new Decision(null, List.of(), true, true);

    /** 解析失败的天气类型/标签只警告一次，避免热路径刷日志。 */
    private static final Set<ResourceLocation> WARNED_TYPES = new HashSet<>();
    private static final Set<ResourceLocation> WARNED_SKYLIGHT = new HashSet<>();
    private static final Set<ResourceLocation> WARNED_TAGS = new HashSet<>();

    private static volatile Snapshot snapshot;
    /** 每个维度一张「群系片 -> 判定」的缓存，随配置重载整体清空；超过上限按访问顺序淘汰。 */
    private static final Map<ServerLevel, Map<String, Decision>> DECISIONS = new WeakHashMap<>();
    private static final int MAX_DECISION_CACHE = 200_000;

    private WeatherBiomeFilter() {}

    /** 该群系片被强制成的天气；没有强制规则（或类型未注册/为晴天）时返回 {@code null}。 */
    public static Forced forcedWeather(ServerLevel level, String patch) {
        return decision(level, patch).forced();
    }

    /** 该群系片被强制成的天气类型；强制晴天返回 clear 的 id，没有强制规则时返回 null。 */
    public static ResourceLocation forcedType(ServerLevel level, String patch) {
        return forcedType(decision(level, patch));
    }

    /** 已持有 {@link Decision} 时复用：避免同一次判定里重复构造候选池。 */
    private static ResourceLocation forcedType(Decision decision) {
        if (decision.forced() != null) {
            return decision.forced().id();
        }
        return decision.hardLock() ? WeatherTypes.CLEAR_ID : null;
    }

    /** 该位置所在的片被强制成的天气类型；没有强制（或被软锁定）时返回 null。 */
    public static ResourceLocation forcedTypeAt(ServerLevel level, BlockPos pos) {
        String patch = BiomePatches.patchAt(level, pos);
        return patch == null ? null : forcedType(level, patch);
    }

    /**
     * 移动模式下手动设置是否被阻止：只认强制晴天。
     *
     * <p>移动模式的降水完全来自风暴，强制到具体天气（雨/雷/自定义类型）只会过滤自然风暴的生成点，
     * 不会在群系里产生常驻降水；因此这里不再拦下命令/API 的手动设置，否则该群系会变成
     * 「永远无天气且不可改写」。强制晴天仍然阻止手动设置。</p>
     */
    public static boolean manualBlockedInMobile(ServerLevel level, BlockPos pos) {
        return WeatherTypes.CLEAR_ID.equals(forcedTypeAt(level, pos));
    }

    /**
     * 自然风暴能否在该位置生成：白名单/黑名单按「初始阶段类型」判定，强制天气按「档案任意阶段」判定。
     *
     * <p>拆开两种判定是为了同时满足：</p>
     * <ul>
     *   <li>黑名单 {@code localizedrain:rain} 必须拦住 stage 0 为雨的多阶段档案（例如雷暴），
     *       否则后续阶段会绕过按类型过滤；</li>
     *   <li>强制雷暴的群系必须能生成雷暴档案，而雷暴档案的 stage 0 也是雨。</li>
     * </ul>
     *
     * @param initialType 档案第一个阶段的天气类型
     * @param stageTypes  档案所有阶段的类型（去重）
     */
    public static boolean stormAllowed(ServerLevel level, BlockPos pos, ResourceLocation initialType,
                                       List<ResourceLocation> stageTypes) {
        String patch = BiomePatches.patchAt(level, pos);
        if (patch == null) {
            return true;
        }
        Decision decision = decision(level, patch);
        ResourceLocation forced = forcedType(decision);
        if (forced != null) {
            // 强制天气：任意阶段匹配即可；强制晴天拒绝一切自然风暴
            return !WeatherTypes.CLEAR_ID.equals(forced) && stageTypes.contains(forced);
        }
        if (WeatherTypes.CLEAR_ID.equals(initialType)) {
            return false;
        }
        // 白名单/黑名单只按初始阶段判定；复用上面取得的 Decision，避免二次构造候选池
        for (Candidate candidate : decision.pool()) {
            if (!candidate.clear() && candidate.id().equals(initialType)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 单类型便利重载：强制类型精确匹配、名单按该类型判定。
     *
     * <p>它会同时把该类型当作「初始阶段类型」和「全部阶段类型」；不知道档案阶段时请用四参重载。
     * 例如强制雷暴的群系里，雷暴档案的初始阶段是雨，用本重载会被判为不允许。</p>
     */
    public static boolean stormAllowed(ServerLevel level, BlockPos pos, ResourceLocation typeId) {
        return stormAllowed(level, pos, typeId, List.of(typeId));
    }

    /** 该群系片自然循环时的天气池；被锁定为晴天时为空。 */
    public static List<Candidate> pool(ServerLevel level, String patch) {
        return decision(level, patch).pool();
    }

    static Decision decision(ServerLevel level, String patch) {
        if (patch == null) {
            return LOCKED_CLEAR;
        }
        Map<String, Decision> cache = DECISIONS.get(level);
        if (cache == null) {
            cache = new LinkedHashMap<>(256, 0.75F, true) {
                protected boolean removeEldestEntry(Map.Entry<String, Decision> eldest) {
                    return size() > MAX_DECISION_CACHE;
                }
            };
            DECISIONS.put(level, cache);
        }
        Decision cached = cache.get(patch);
        if (cached != null) {
            return cached;
        }
        Decision decision = compute(level, patch);
        cache.put(patch, decision);
        return decision;
    }

    private static Decision compute(ServerLevel level, String patch) {
        Snapshot current = snapshot(level);
        ResourceLocation biome = BiomePatches.patchBiome(level, patch);
        if (biome == null) {
            // 未知群系（拿不到 id）按「全部自然天气」放行，避免误伤其他模组。
            return new Decision(null, resolvePool(level, current.naturalPool(), Set.of(), true, true), false, false);
        }
        ResourceLocation forcedId = current.forcedWeather(biome);
        if (forcedId != null) {
            WeatherType type = WeatherTypes.resolve(level, forcedId);
            if (type == null) {
                if (WARNED_TYPES.add(forcedId)) {
                    LOGGER.warn("Ignoring weather override for biome '{}': weather type '{}' is not registered",
                            biome, forcedId);
                }
            } else if (WeatherTypes.CLEAR_ID.equals(forcedId)) {
                return HARD_LOCKED_CLEAR;
            } else {
                boolean hasSkyLight = level.dimensionType().hasSkyLight();
                if (type.skylight().allows(hasSkyLight) && (!type.requiresSkylight() || hasSkyLight)) {
                    return new Decision(new Forced(forcedId, type), List.of(), false, false);
                }
                // 类型声明了天光要求：当前维度不允许时忽略这条强制规则，退回自然判定
                if (WARNED_SKYLIGHT.add(forcedId)) {
                    LOGGER.warn("Ignoring weather override '{}': the type is not allowed in this dimension by skylight",
                            forcedId);
                }
            }
        }
        Set<ResourceLocation> whitelist = current.whitelistTypes(biome);
        boolean explicit = !whitelist.isEmpty();
        List<ResourceLocation> base = explicit ? List.copyOf(whitelist) : current.naturalPool();
        List<Candidate> pool = resolvePool(level, base, current.blacklistTypes(biome), explicit,
                biomeHasPrecipitation(level, biome));
        for (Candidate candidate : pool) {
            if (!candidate.clear()) {
                return new Decision(null, pool, false, false);
            }
        }
        return LOCKED_CLEAR;
    }

    private static List<Candidate> resolvePool(ServerLevel level, List<ResourceLocation> ids,
                                               Set<ResourceLocation> excluded, boolean explicit, boolean precipitates) {
        List<Candidate> result = new ArrayList<>(ids.size());
        Set<ResourceLocation> seen = new HashSet<>();
        for (ResourceLocation id : ids) {
            if (id == null || !seen.add(id) || excluded.contains(id)) {
                continue;
            }
            WeatherType type = WeatherTypes.resolve(level, id);
            if (type == null) {
                if (WARNED_TYPES.add(id)) {
                    LOGGER.warn("Ignoring weather type '{}': not registered", id);
                }
                continue;
            }
            if (!explicit && !precipitates && (type.hasPrecipitation() || type.allowsThunder())) {
                continue;
            }
            boolean hasSkyLight = level.dimensionType().hasSkyLight();
            if (!type.skylight().allows(hasSkyLight)
                    || (type.requiresSkylight() && !hasSkyLight)) {
                continue;                   // 声明了 skylight / requires_skylight 的类型不出现在不匹配的维度
            }
            result.add(new Candidate(id, type.hasPrecipitation(), type.allowsThunder(),
                    WeatherTypes.CLEAR_ID.equals(id)));
        }
        return List.copyOf(result);
    }

    /** 群系自身是否降水；查不到群系时按「降水」处理，保持旧行为。 */
    private static boolean biomeHasPrecipitation(ServerLevel level, ResourceLocation biomeId) {
        Registry<Biome> registry = level.registryAccess().registry(Registries.BIOME).orElse(null);
        Biome biome = registry == null ? null : registry.get(biomeId);
        return biome == null || biome.hasPrecipitation();
    }

    /** 配置重载、服务器启动（标签可能变化）时调用。 */
    public static void invalidate() {
        snapshot = null;
        synchronized (DECISIONS) {
            DECISIONS.clear();
        }
        WeatherManager.invalidateLookups();
    }

    private static Snapshot snapshot(ServerLevel level) {
        Snapshot current = snapshot;
        if (current != null) {
            return current;
        }
        synchronized (WeatherBiomeFilter.class) {
            if (snapshot == null) {
                snapshot = build(level);
            }
            return snapshot;
        }
    }

    private static Snapshot build(ServerLevel level) {
        List<TypeRule> whitelist = buildTypeRules(level, Config.BIOME_WEATHER_WHITELIST.get(),
                "weatherBiomeWhitelist");
        List<TypeRule> blacklist = buildTypeRules(level, Config.BIOME_WEATHER_BLACKLIST.get(),
                "weatherBiomeBlacklist");
        List<OverrideRule> overrides = buildOverrides(level);
        List<ResourceLocation> naturalPool = WeatherTypes.naturalIds(level);
        if (naturalPool.isEmpty()) {
            LOGGER.warn("Weather type tag '{}' is defined but empty: no biome will cycle weather by default",
                    WeatherTypes.NATURAL.location());
        }
        if (!Config.builtinPresetsEnabled()) {
            // 内置新增预设默认关闭：不进入自然池，避免默认就出现暴风雪/沙尘暴等
            naturalPool = naturalPool.stream()
                    .filter(typeId -> !WeatherTypes.isPreset(typeId))
                    .toList();
            if (naturalPool.isEmpty()) {
                // 过滤前只有预设类型：此时自然池会静默变空，必须在启动日志里说清楚
                LOGGER.warn("No natural weather types left after excluding built-in presets: "
                        + "enable 'enableBuiltinPresets' or add non-preset types to the '{}' tag, "
                        + "otherwise no biome will cycle weather by default",
                        WeatherTypes.NATURAL.location());
            }
        }
        return new Snapshot(overrides, whitelist, blacklist, naturalPool);
    }

    /** 解析白/黑名单：{@code selector=weather}，天气可以写 {@code #标签}。 */
    private static List<TypeRule> buildTypeRules(ServerLevel level, List<? extends String> entries, String name) {
        List<TypeRule> result = new ArrayList<>();
        for (String raw : entries) {
            String entry = raw == null ? "" : raw.trim();
            if (entry.isEmpty()) {
                continue;
            }
            int separator = entry.lastIndexOf('=');
            if (separator <= 0 || separator == entry.length() - 1) {
                LOGGER.warn("Ignoring invalid entry '{}' in {}: expected '<biome selector>=<weather type>'",
                        entry, name);
                continue;
            }
            String selector = entry.substring(0, separator).trim();
            Rules rules = Rules.buildSingle(level, selector, name);
            if (rules.empty()) {
                LOGGER.warn("Ignoring entry '{}' in {}: unknown biome selector", entry, name);
                continue;
            }
            List<ResourceLocation> types = resolveTypeTokens(level, entry.substring(separator + 1).trim(), name, true);
            if (types.isEmpty()) {
                continue;
            }
            result.add(new TypeRule(rules, types));
        }
        return List.copyOf(result);
    }

    /** 解析强制天气：{@code selector=weather}，不支持天气标签，多条命中时按顺序取第一条。 */
    private static List<OverrideRule> buildOverrides(ServerLevel level) {
        List<OverrideRule> result = new ArrayList<>();
        for (String raw : Config.BIOME_WEATHER_OVERRIDES.get()) {
            String entry = raw == null ? "" : raw.trim();
            if (entry.isEmpty()) {
                continue;
            }
            int separator = entry.lastIndexOf('=');
            if (separator <= 0 || separator == entry.length() - 1) {
                LOGGER.warn("Ignoring invalid weather override '{}' in weatherBiomeOverrides: expected "
                        + "'<biome selector>=<weather type>'", entry);
                continue;
            }
            String selector = entry.substring(0, separator).trim();
            ResourceLocation typeId = parseWeatherId(entry.substring(separator + 1).trim());
            if (typeId == null) {
                LOGGER.warn("Ignoring weather override '{}': expected a weather type id or clear/rain/thunder",
                        entry);
                continue;
            }
            Rules rules = Rules.buildSingle(level, selector, "weatherBiomeOverrides");
            if (rules.empty()) {
                LOGGER.warn("Ignoring weather override '{}': unknown biome selector", entry);
                continue;
            }
            result.add(new OverrideRule(rules, typeId));
        }
        return List.copyOf(result);
    }

    private static List<ResourceLocation> resolveTypeTokens(ServerLevel level, String raw, String name, boolean allowTag) {
        String token = raw.trim();
        if (token.isEmpty()) {
            return List.of();
        }
        if (token.startsWith("#")) {
            if (!allowTag) {
                LOGGER.warn("Ignoring weather tag '{}' in {}: tags are only supported in the whitelist and blacklist",
                        token, name);
                return List.of();
            }
            ResourceLocation tagId = ResourceLocation.tryParse(token.substring(1).trim());
            if (tagId == null) {
                LOGGER.warn("Ignoring invalid weather tag '{}' in {}", token, name);
                return List.of();
            }
            List<ResourceLocation> ids = WeatherTypes.tagIds(level, tagId);
            if (ids.isEmpty() && WARNED_TAGS.add(tagId)) {
                LOGGER.warn("Weather type tag '{}' in {} is not defined or empty; nothing will match it",
                        tagId, name);
            }
            return ids;
        }
        ResourceLocation id = parseWeatherId(token);
        if (id == null) {
            LOGGER.warn("Ignoring weather type '{}' in {}: expected a weather type id or clear/rain/thunder",
                    token, name);
            return List.of();
        }
        return List.of(id);
    }

    /** 解析规则里的天气：三个旧短名映射到内置类型，其余按 {@link ResourceLocation} 惰性解析。 */
    private static ResourceLocation parseWeatherId(String token) {
        return switch (token.toLowerCase(Locale.ROOT)) {
            case "clear" -> WeatherTypes.CLEAR_ID;
            case "rain" -> WeatherTypes.RAIN_ID;
            case "thunder" -> WeatherTypes.THUNDER_ID;
            default -> ResourceLocation.tryParse(token);
        };
    }

    /** {@code locked} 表示自然循环没有非晴天可抽；{@code hardLock} 表示配置强制晴天，连手动天气也不允许。 */
    record Decision(Forced forced, List<Candidate> pool, boolean locked, boolean hardLock) {}

    /** 解析后的强制类型：注册表 id + 已解析的定义（定义在注册表可用后才有）。 */
    public record Forced(ResourceLocation id, WeatherType type) {}

    /** 天气池里的一项：类型 id + 降水/雷暴能力，避免热路径反复查注册表。 */
    public record Candidate(ResourceLocation id, boolean precipitation, boolean thunder, boolean clear) {}

    private record TypeRule(Rules rules, List<ResourceLocation> types) {}

    private record OverrideRule(Rules rules, ResourceLocation typeId) {}

    private record Snapshot(List<OverrideRule> overrides, List<TypeRule> whitelist, List<TypeRule> blacklist,
                            List<ResourceLocation> naturalPool) {
        /** 命中的第一条强制规则；没有则返回 null。 */
        ResourceLocation forcedWeather(ResourceLocation biome) {
            for (OverrideRule rule : this.overrides) {
                if (rule.rules().matches(biome)) {
                    return rule.typeId();
                }
            }
            return null;
        }

        /** 白名单命中规则里列出的天气并集（保持配置顺序）。 */
        Set<ResourceLocation> whitelistTypes(ResourceLocation biome) {
            Set<ResourceLocation> result = new LinkedHashSet<>();
            for (TypeRule rule : this.whitelist) {
                if (rule.rules().matches(biome)) {
                    result.addAll(rule.types());
                }
            }
            return result;
        }

        /** 黑名单命中规则里列出的天气并集。 */
        Set<ResourceLocation> blacklistTypes(ResourceLocation biome) {
            Set<ResourceLocation> result = new HashSet<>();
            for (TypeRule rule : this.blacklist) {
                if (rule.rules().matches(biome)) {
                    result.addAll(rule.types());
                }
            }
            return result;
        }
    }

    private record Rules(boolean empty, Set<ResourceLocation> ids, Set<String> namespaces) {
        boolean matches(ResourceLocation biome) {
            return this.ids.contains(biome) || this.namespaces.contains(biome.getNamespace());
        }

        static Rules buildSingle(ServerLevel level, String selector, String name) {
            Set<ResourceLocation> ids = new HashSet<>();
            Set<String> namespaces = new HashSet<>();
            addEntry(level, selector, name, ids, namespaces);
            return new Rules(ids.isEmpty() && namespaces.isEmpty(), Set.copyOf(ids), Set.copyOf(namespaces));
        }

        private static void addEntry(ServerLevel level, String raw, String name,
                                     Set<ResourceLocation> ids, Set<String> namespaces) {
            String entry = raw == null ? "" : raw.trim();
            if (entry.isEmpty()) {
                return;
            }
            if (entry.startsWith("#")) {
                addTag(level, entry.substring(1).trim(), name, ids);
            } else if (entry.endsWith(":*")) {
                String namespace = entry.substring(0, entry.length() - 2).trim();
                if (namespace.isEmpty()) {
                    LOGGER.warn("Ignoring invalid biome entry '{}' in {}", entry, name);
                } else {
                    namespaces.add(namespace);
                }
            } else {
                ResourceLocation id = ResourceLocation.tryParse(entry);
                if (id == null) {
                    LOGGER.warn("Ignoring invalid biome entry '{}' in {}", entry, name);
                } else {
                    ids.add(id);
                }
            }
        }

        private static void addTag(ServerLevel level, String tagName, String name, Set<ResourceLocation> ids) {
            ResourceLocation tagId = ResourceLocation.tryParse(tagName);
            if (tagId == null) {
                LOGGER.warn("Ignoring invalid biome tag '#{}' in {}", tagName, name);
                return;
            }
            TagKey<Biome> tag = TagKey.create(Registries.BIOME, tagId);
            Registry<Biome> registry = level.registryAccess().registryOrThrow(Registries.BIOME);
            Optional<HolderSet.Named<Biome>> holders = registry.getTag(tag);
            if (holders.isEmpty()) {
                LOGGER.warn("Biome tag '{}' in {} is not defined; ignoring it", tagId, name);
                return;
            }
            for (Holder<Biome> holder : holders.get()) {
                holder.unwrapKey().map(ResourceKey::location).ifPresent(ids::add);
            }
        }
    }
}
