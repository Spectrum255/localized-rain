package com.spectrum255.localizedrain.api;

import com.spectrum255.localizedrain.Localizedrain;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;

/** 风暴档案注册表访问器。注册表是数据包注册表，内容可随数据包重载替换，因此不缓存 Holder。 */
public final class StormProfiles {
    public static final ResourceKey<Registry<StormProfile>> REGISTRY_KEY =
            ResourceKey.createRegistryKey(ResourceLocation.fromNamespaceAndPath(Localizedrain.MODID, "storm_profile"));

    private StormProfiles() {}

    public static Optional<Holder.Reference<StormProfile>> byId(Level level, ResourceLocation id) {
        Registry<StormProfile> registry = level.registryAccess().registry(REGISTRY_KEY).orElse(null);
        return registry == null ? Optional.empty() : registry.getHolder(id);
    }

    /** natural=true 的档案，按注册顺序。 */
    public static List<Holder.Reference<StormProfile>> naturalPool(Level level) {
        Registry<StormProfile> registry = level.registryAccess().registry(REGISTRY_KEY).orElse(null);
        if (registry == null) {
            return List.of();
        }
        List<Holder.Reference<StormProfile>> pool = new ArrayList<>();
        for (Holder.Reference<StormProfile> holder : registry.holders().toList()) {
            if (holder.value().natural()) {
                pool.add(holder);
            }
        }
        return pool;
    }

    public static List<ResourceLocation> ids(Level level) {
        Registry<StormProfile> registry = level.registryAccess().registry(REGISTRY_KEY).orElse(null);
        return registry == null ? List.of() : List.copyOf(registry.keySet());
    }

    /** 按 weight 加权抽一个自然档案；池为空返回 empty。 */
    public static Optional<Holder.Reference<StormProfile>> randomNatural(Level level, RandomSource random) {
        List<Holder.Reference<StormProfile>> pool = naturalPool(level);
        int total = 0;
        for (Holder.Reference<StormProfile> holder : pool) {
            total += holder.value().weight();
        }
        if (total <= 0) {
            return Optional.empty();
        }
        int roll = random.nextInt(total);
        for (Holder.Reference<StormProfile> holder : pool) {
            roll -= holder.value().weight();
            if (roll < 0) {
                return Optional.of(holder);
            }
        }
        return Optional.of(pool.get(pool.size() - 1));
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
    }
}
