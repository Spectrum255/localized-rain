package com.spectrum255.localizedrain.weather;

import com.mojang.logging.LogUtils;
import com.spectrum255.localizedrain.Config;
import com.spectrum255.localizedrain.api.WeatherType;
import com.spectrum255.localizedrain.api.WeatherTypes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;
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
 * 生物群系天气白名单、黑名单与强制天气。
 *
 * <p>名单里的条目支持三种写法：</p>
 * <ul>
 *   <li>{@code minecraft:desert} —— 精确的群系 id；</li>
 *   <li>{@code minecraft:*} —— 整个命名空间；</li>
 *   <li>{@code #minecraft:is_ocean} —— 群系标签（标签内容在服务器启动/配置重载时解析）。</li>
 * </ul>
 *
 * <p>语义：强制规则优先于黑名单，黑名单优先于白名单；白名单非空时只有白名单里的群系会参与局地天气。
 * 被黑名单排除（或强制为晴天）的群系在自然循环中永远为晴天；指令/API 的手动设置可以在计时内
 * 临时覆盖名单（强制天气锁除外），计时结束后重新按名单判定。被强制为雨/雷的群系片每 tick 保持对应天气。
 * 名单全为空时不做任何过滤，行为与之前完全一致。</p>
 *
 * <p>按群系片缓存判定结果，热路径（isRainingAt 等）只做一次 map 查找。配置重载或服务器启动时
 * 调用 {@link #invalidate()} 清空缓存。</p>
 */
public final class WeatherBiomeFilter {
    private static final Logger LOGGER = LogUtils.getLogger();

    private static final Decision ALLOWED = new Decision(true, null, List.of(), false);
    private static final Decision EXCLUDED = new Decision(false, null, List.of(), false);
    /** 解析失败的强制类型只警告一次，避免热路径刷日志。 */
    private static final Set<ResourceLocation> WARNED_TYPES = new HashSet<>();

    private static volatile Snapshot snapshot;
    /** 每个维度一张「群系片 -> 判定」的缓存，随配置重载整体清空；超过上限按访问顺序淘汰。 */
    private static final Map<ServerLevel, Map<String, Decision>> DECISIONS = new WeakHashMap<>();
    private static final int MAX_DECISION_CACHE = 200_000;

    private WeatherBiomeFilter() {}

    /** 该群系片是否被「显式白名单」列出（白名单非空且匹配）。 */
    public static boolean explicitlyWhitelisted(ServerLevel level, String patch) {
        ResourceLocation biome = BiomePatches.patchBiome(level, patch);
        return biome != null && snapshot(level).explicitlyWhitelisted(biome);
    }

    /** 该群系片是否参与局地天气（被强制为雨/雷时也算参与）。未知群系（拿不到 id）按允许处理，避免误伤其他模组。 */
    public static boolean allows(ServerLevel level, String patch) {
        return decision(level, patch).allowed;
    }

    /** 该群系片被强制成的天气；没有强制规则（或类型未注册/为晴天）时返回 {@code null}。 */
    public static Forced forcedWeather(ServerLevel level, String patch) {
        return decision(level, patch).forced;
    }

    /** 该群系片自然循环时的候选天气；未配置时为空列表（沿用内置晴/雨/雷循环）。 */
    public static List<Candidate> candidates(ServerLevel level, String patch) {
        return decision(level, patch).candidates;
    }

    static Decision decision(ServerLevel level, String patch) {
        if (patch == null) {
            return ALLOWED;
        }
        Map<String, Decision> cache = DECISIONS.get(level);
        if (cache == null) {
            cache = new LinkedHashMap<>(256, 0.75F, true) {
                // 注意：本类里有一个 record Override，@Override 会被解析成那个类型，这里省略注解
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
        ResourceLocation biome = BiomePatches.patchBiome(level, patch);
        if (biome == null) {
            return ALLOWED;
        }
        Snapshot current = snapshot(level);
        if (current.blacklist.matches(biome)) {
            return EXCLUDED;
        }
        ResourceLocation forcedId = current.forcedWeather(biome);
        if (forcedId != null) {
            WeatherType type = WeatherTypes.resolve(level, forcedId);
            if (type == null) {
                if (WARNED_TYPES.add(forcedId)) {
                    LOGGER.warn("Ignoring weather override for biome '{}': weather type '{}' is not registered",
                            biome, forcedId);
                }
                return current.allows(biome) ? ALLOWED : EXCLUDED;
            }
            if (type.isClear()) {
                return new Decision(false, null, List.of(), true);
            }
            return new Decision(true, new Forced(forcedId, type), List.of(), true);
        }
        List<Candidate> candidates = current.candidates(biome);
        if (!candidates.isEmpty()) {
            // 候选天气本身就意味着“允许发生”，不受白名单/降水类型限制
            return new Decision(true, null, candidates, false);
        }
        return current.allows(biome) ? ALLOWED : EXCLUDED;
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
        Rules whitelist = Rules.build(level, Config.BIOME_WEATHER_WHITELIST.get(), "weatherBiomeWhitelist");
        Rules blacklist = Rules.build(level, Config.BIOME_WEATHER_BLACKLIST.get(), "weatherBiomeBlacklist");
        List<Override> overrides = buildOverrides(level);
        List<CandidateRule> candidates = buildCandidates(level);
        return new Snapshot(whitelist, blacklist, overrides, candidates);
    }

    /** 解析候选规则：{@code selector=type1|type2}，规则按配置顺序后者覆盖前者。 */
    private static List<CandidateRule> buildCandidates(ServerLevel level) {
        List<CandidateRule> result = new ArrayList<>();
        for (String raw : Config.BIOME_WEATHER_CANDIDATES.get()) {
            String entry = raw == null ? "" : raw.trim();
            if (entry.isEmpty()) {
                continue;
            }
            int separator = entry.lastIndexOf('=');
            if (separator <= 0 || separator == entry.length() - 1) {
                LOGGER.warn("Ignoring invalid candidate entry '{}' in weatherBiomeCandidates", entry);
                continue;
            }
            String selector = entry.substring(0, separator).trim();
            List<Candidate> resolved = new ArrayList<>();
            for (String token : Config.candidateTokens(entry.substring(separator + 1))) {
                ResourceLocation typeId = parseWeatherId(token.trim());
                WeatherType type = typeId == null ? null : WeatherTypes.resolve(level, typeId);
                if (type == null) {
                    LOGGER.warn("Ignoring candidate type '{}' in '{}': not registered", token.trim(), entry);
                    continue;
                }
                resolved.add(new Candidate(typeId, type.hasPrecipitation(), type.allowsThunder()));
            }
            if (resolved.isEmpty()) {
                continue;
            }
            Rules rules = Rules.buildSingle(level, selector, "weatherBiomeCandidates");
            if (rules.empty()) {
                LOGGER.warn("Ignoring candidate entry '{}': unknown biome selector", entry);
                continue;
            }
            result.add(new CandidateRule(rules, List.copyOf(resolved)));
        }
        return List.copyOf(result);
    }

    private static List<Override> buildOverrides(ServerLevel level) {
        List<Override> result = new ArrayList<>();
        for (String raw : Config.BIOME_WEATHER_OVERRIDES.get()) {
            String entry = raw == null ? "" : raw.trim();
            if (entry.isEmpty()) {
                continue;
            }
            int separator = entry.lastIndexOf('=');
            if (separator <= 0 || separator == entry.length() - 1) {
                LOGGER.warn("Ignoring invalid weather override '{}' in weatherBiomeOverrides", entry);
                continue;
            }
            String selector = entry.substring(0, separator).trim();
            ResourceLocation typeId = parseWeatherId(entry.substring(separator + 1).trim());
            if (typeId == null) {
                LOGGER.warn("Ignoring weather override '{}': expected a weather type id or clear/rain/thunder", entry);
                continue;
            }
            Rules rules = Rules.buildSingle(level, selector, "weatherBiomeOverrides");
            if (rules.empty()) {
                LOGGER.warn("Ignoring weather override '{}': unknown biome selector", entry);
                continue;
            }
            result.add(new Override(rules, typeId));
        }
        return List.copyOf(result);
    }

    /** 解析覆盖规则里的天气：三个旧短名映射到内置类型，其余按 {@link ResourceLocation} 惰性解析。 */
    private static ResourceLocation parseWeatherId(String token) {
        return switch (token.toLowerCase(Locale.ROOT)) {
            case "clear" -> WeatherTypes.CLEAR_ID;
            case "rain" -> WeatherTypes.RAIN_ID;
            case "thunder" -> WeatherTypes.THUNDER_ID;
            default -> ResourceLocation.tryParse(token);
        };
    }

    record Decision(boolean allowed, Forced forced, List<Candidate> candidates, boolean locked) {}

    /** 解析后的强制类型：注册表 id + 已解析的定义（定义在注册表可用后才有）。 */
    public record Forced(ResourceLocation id, WeatherType type) {}

    /** 候选天气：自然循环可能抽到的类型，附带降水/雷暴能力，避免热路径反复查注册表。 */
    public record Candidate(ResourceLocation id, boolean precipitation, boolean thunder) {}

    private record CandidateRule(Rules rules, List<Candidate> candidates) {}

    private record Snapshot(Rules whitelist, Rules blacklist, List<Override> overrides,
                           List<CandidateRule> candidates) {
        boolean allows(ResourceLocation biome) {
            if (this.blacklist.matches(biome)) {
                return false;
            }
            return this.whitelist.empty() || this.whitelist.matches(biome);
        }

        /** 是否被显式白名单点名（非空白名单 + 命中 + 未被黑名单排除）。 */
        boolean explicitlyWhitelisted(ResourceLocation biome) {
            return !this.blacklist.matches(biome) && !this.whitelist.empty() && this.whitelist.matches(biome);
        }

        /** 只有显式强制规则会返回非 null；多条匹配时按配置顺序后者覆盖前者。 */
        ResourceLocation forcedWeather(ResourceLocation biome) {
            ResourceLocation result = null;
            for (Override override : this.overrides) {
                if (override.rules.matches(biome)) {
                    result = override.typeId;
                }
            }
            return result;
        }

        /** 该群系的候选天气；多条规则匹配时按配置顺序后者覆盖前者。 */
        List<Candidate> candidates(ResourceLocation biome) {
            List<Candidate> result = List.of();
            for (CandidateRule rule : this.candidates) {
                if (rule.rules.matches(biome)) {
                    result = rule.candidates;
                }
            }
            return result;
        }
    }

    private record Override(Rules rules, ResourceLocation typeId) {}

    private record Rules(boolean empty, Set<ResourceLocation> ids, Set<String> namespaces) {
        boolean matches(ResourceLocation biome) {
            return this.ids.contains(biome) || this.namespaces.contains(biome.getNamespace());
        }

        static Rules build(ServerLevel level, List<? extends String> entries, String name) {
            Set<ResourceLocation> ids = new HashSet<>();
            Set<String> namespaces = new HashSet<>();
            for (String raw : entries) {
                addEntry(level, raw, name, ids, namespaces);
            }
            return new Rules(ids.isEmpty() && namespaces.isEmpty(), Set.copyOf(ids), Set.copyOf(namespaces));
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
