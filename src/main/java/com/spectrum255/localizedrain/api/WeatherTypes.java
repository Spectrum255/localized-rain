package com.spectrum255.localizedrain.api;

import com.spectrum255.localizedrain.Localizedrain;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 天气类型注册表。
 *
 * <p>注册表键是 {@code localizedrain:weather_type}；内置三个类型：
 * {@code clear}、{@code rain}、{@code thunder}。附属模组用
 * {@code WeatherTypes.REGISTRY.register(...)} 追加类型，然后通过
 * {@link WeatherApi#setWeather} 或 {@code /weather} 使用。</p>
 */
public final class WeatherTypes {
    public static final ResourceKey<Registry<WeatherType>> REGISTRY_KEY =
            ResourceKey.createRegistryKey(ResourceLocation.fromNamespaceAndPath(Localizedrain.MODID, "weather_type"));

    public static final DeferredRegister<WeatherType> REGISTRY =
            DeferredRegister.create(REGISTRY_KEY, Localizedrain.MODID);

    public static final ResourceLocation CLEAR_ID = id("clear");
    public static final ResourceLocation RAIN_ID = id("rain");
    public static final ResourceLocation THUNDER_ID = id("thunder");

    public static final DeferredHolder<WeatherType, WeatherType> CLEAR = REGISTRY.register("clear",
            () -> new WeatherType("weather.localizedrain.clear", false, Biome.Precipitation.NONE, false));
    public static final DeferredHolder<WeatherType, WeatherType> RAIN = REGISTRY.register("rain",
            () -> new WeatherType("weather.localizedrain.rain", true, Biome.Precipitation.RAIN, false));
    public static final DeferredHolder<WeatherType, WeatherType> THUNDER = REGISTRY.register("thunder",
            () -> new WeatherType("weather.localizedrain.thunder", true, Biome.Precipitation.RAIN, true));

    /** 按 id 缓存解析结果；注册表在运行期不变，服务器启动/配置重载时清空。 */
    private static final Map<ResourceLocation, WeatherType> CACHE = new HashMap<>();

    private WeatherTypes() {}

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(Localizedrain.MODID, path);
    }

    /**
     * 按 id 取天气类型。未知 id（例如附属模组被移除、旧存档）回退到晴天；
     * 只读，不创建任何状态。
     */
    public static WeatherType byId(ServerLevel level, ResourceLocation typeId) {
        WeatherType resolved = resolve(level, typeId);
        return resolved == null ? CLEAR.get() : resolved;
    }

    /**
     * 按 id 取天气类型；未注册时返回 {@code null}（不回落晴天）。
     * 只读，不创建任何状态。
     */
    public static WeatherType resolve(ServerLevel level, ResourceLocation typeId) {
        if (typeId == null) {
            return null;
        }
        WeatherType cached = CACHE.get(typeId);
        if (cached != null) {
            return cached;
        }
        Registry<WeatherType> registry = level.registryAccess().registry(REGISTRY_KEY).orElse(null);
        WeatherType resolved = registry == null ? null : registry.get(typeId);
        if (resolved != null) {
            CACHE.put(typeId, resolved);
        }
        return resolved;
    }

    /** 是否为内置的三个类型（内置类型走原版雨/雷通道，HUD 用原版天气行）。 */
    public static boolean isBuiltin(ResourceLocation typeId) {
        return CLEAR_ID.equals(typeId) || RAIN_ID.equals(typeId) || THUNDER_ID.equals(typeId);
    }

    /** 清空解析缓存（服务器启动、配置重载、注册表变化时调用）。 */
    public static void invalidate() {
        CACHE.clear();
    }
}
