package com.spectrum255.localizedrain.api;

import com.spectrum255.localizedrain.Localizedrain;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;

/**
 * 天气类型注册表（数据包注册表）。
 *
 * <p>注册表键是 {@code localizedrain:weather_type}；内置三个类型：
 * {@code clear}、{@code rain}、{@code thunder}。数据包可以在
 * {@code data/<namespace>/localizedrain/weather_type/<id>.json} 里新增类型，
 * 再用黑白名单/强制天气/风暴阶段或 {@link WeatherApi#setWeather} 使用。</p>
 */
public final class WeatherTypes {
    public static final ResourceKey<Registry<WeatherType>> REGISTRY_KEY =
            ResourceKey.createRegistryKey(ResourceLocation.fromNamespaceAndPath(Localizedrain.MODID, "weather_type"));

    public static final ResourceLocation CLEAR_ID = id("clear");
    public static final ResourceLocation RAIN_ID = id("rain");
    public static final ResourceLocation THUNDER_ID = id("thunder");

    /** 注册表缺失某个内置类型时的兜底实例（例如数据包删掉了内置 JSON）。 */
    public static final WeatherType CLEAR_TYPE =
            new WeatherType("weather.localizedrain.clear", false, Biome.Precipitation.NONE, false);
    public static final WeatherType RAIN_TYPE =
            new WeatherType("weather.localizedrain.rain", true, Biome.Precipitation.RAIN, false);
    public static final WeatherType THUNDER_TYPE =
            new WeatherType("weather.localizedrain.thunder", true, Biome.Precipitation.RAIN, true);

    /**
     * 天气类型标签：默认自然天气池。
     *
     * <p>本模组不内置任何标签文件；数据包可以在
     * {@code data/<namespace>/tags/localizedrain/weather_type/natural.json} 里定义它。
     * 标签不存在时回退到内置的 {@code clear}/{@code rain}/{@code thunder}，所以默认行为不变；
     * 标签一旦存在就以标签内容为准（可以是空列表，表示默认不再自然变天）。</p>
     */
    public static final TagKey<WeatherType> NATURAL =
            TagKey.create(REGISTRY_KEY, ResourceLocation.fromNamespaceAndPath(Localizedrain.MODID, "natural"));

    /** 按 id 缓存解析结果；注册表随数据包重载变化，配置重载/服务器启动时清空。 */
    private static final Map<ResourceLocation, WeatherType> CACHE = new HashMap<>();

    private WeatherTypes() {}

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(Localizedrain.MODID, path);
    }

    /**
     * 按 id 取天气类型。未知 id（例如附属模组被移除、旧存档）回退到晴天；
     * 只读，不创建任何状态。
     */
    public static WeatherType byId(Level level, ResourceLocation typeId) {
        WeatherType resolved = resolve(level, typeId);
        return resolved == null ? CLEAR_TYPE : resolved;
    }

    /**
     * 按 id 取天气类型；未注册时返回 {@code null}（不回落晴天）。
     * 只读，不创建任何状态。客户端也可用（数据包注册表会在登录时同步）。
     */
    public static WeatherType resolve(Level level, ResourceLocation typeId) {
        if (typeId == null) {
            return null;
        }
        WeatherType cached = CACHE.get(typeId);
        if (cached != null) {
            return cached;
        }
        Registry<WeatherType> registry = registry(level);
        WeatherType resolved = registry == null ? null : registry.get(typeId);
        if (resolved != null) {
            resolved = resolved.withDerivedDisplayKey(typeId);
            CACHE.put(typeId, resolved);
        }
        return resolved;
    }

    /** 注册表里的全部天气类型 id，按注册顺序（数据包合并后）。 */
    public static List<ResourceLocation> ids(Level level) {
        Registry<WeatherType> registry = registry(level);
        return registry == null ? List.of() : List.copyOf(registry.keySet());
    }

    /** 是否为内置的三个类型（内置类型走原版雨/雷通道，HUD 用原版天气行）。 */
    public static boolean isBuiltin(ResourceLocation typeId) {
        return CLEAR_ID.equals(typeId) || RAIN_ID.equals(typeId) || THUNDER_ID.equals(typeId);
    }

    /**
     * 默认自然天气池：优先使用 {@link #NATURAL} 标签；标签不存在时回退到内置的晴/雨/雷。
     * 标签存在但为空，表示默认不再自然变天。
     */
    public static List<ResourceLocation> naturalIds(Level level) {
        Registry<WeatherType> registry = registry(level);
        if (registry != null) {
            Optional<HolderSet.Named<WeatherType>> tag = registry.getTag(NATURAL);
            if (tag.isPresent()) {
                return collect(tag.get());
            }
        }
        return List.of(CLEAR_ID, RAIN_ID, THUNDER_ID);
    }

    /** 解析天气类型标签；标签未定义时返回空列表。 */
    public static List<ResourceLocation> tagIds(Level level, ResourceLocation tagId) {
        Registry<WeatherType> registry = registry(level);
        if (registry == null) {
            return List.of();
        }
        Optional<HolderSet.Named<WeatherType>> tag = registry.getTag(TagKey.create(REGISTRY_KEY, tagId));
        return tag.map(WeatherTypes::collect).orElse(List.of());
    }

    /** 清空解析缓存（服务器启动、配置重载、数据包重载时调用）。 */
    public static void invalidate() {
        CACHE.clear();
    }

    private static Registry<WeatherType> registry(Level level) {
        return level == null ? null : level.registryAccess().registry(REGISTRY_KEY).orElse(null);
    }

    private static List<ResourceLocation> collect(HolderSet.Named<WeatherType> tag) {
        List<ResourceLocation> ids = new ArrayList<>();
        for (Holder<WeatherType> holder : tag) {
            holder.unwrapKey().map(ResourceKey::location).ifPresent(ids::add);
        }
        return List.copyOf(ids);
    }
}
