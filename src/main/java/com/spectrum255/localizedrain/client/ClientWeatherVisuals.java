package com.spectrum255.localizedrain.client;

import com.spectrum255.localizedrain.Localizedrain;
import com.spectrum255.localizedrain.api.StormTags;
import com.spectrum255.localizedrain.api.WeatherType;
import com.spectrum255.localizedrain.api.WeatherTypes;
import com.spectrum255.localizedrain.network.ClientWeatherTargets;
import com.spectrum255.localizedrain.network.WeatherStatePayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.SectionPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ViewportEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 客户端天气视觉层：把服务端下发的叠加层（或片模式的单层快照）渲染成粒子、雾效与环境音。
 *
 * <p>移动模式下服务端按配置把兼容的天气层叠起来（{@code compatibleWeather}），本层逐层渲染；
 * 主导层（强度最大）决定雾距离、天空覆盖与 {@code currentType()}，雾颜色按各层强度加权混合。
 * 同时最多两层播放环境音，避免叠满时声音糊成一片。</p>
 *
 * <p>内置类型（clear/rain/thunder）保持原版渲染不动；自定义类型只要写了 {@code particle}
 * 就用本层的简单粒子替代原版雨雪粒子（由 {@code LevelRendererMixin} 取消原版渲染）。</p>
 */
public final class ClientWeatherVisuals {
    private static final RandomSource RANDOM = RandomSource.create();
    private static final int SPAWN_RADIUS = 12;
    /** 每 tick 全部层合计的粒子上限：数据包写再大的 particle_count 也不会把客户端拖垮。 */
    private static final int MAX_PARTICLES_PER_TICK = 240;
    /** 同时播放环境音的层数上限。 */
    private static final int MAX_SOUND_LAYERS = 2;
    private static final Set<ResourceLocation> WARNED_PARTICLES = new HashSet<>();

    /** 当前叠加层，按服务端下发的顺序（强度降序）。 */
    private static List<VisualLayer> layers = List.of();
    private static WeatherType currentType;
    private static float currentIntensity;
    /** 每层环境音的独立计时（type id -> 已经过的 tick）。 */
    private static final Map<ResourceLocation, Integer> SOUND_TICKERS = new HashMap<>();

    private ClientWeatherVisuals() {}

    /** 单层视觉：类型定义、id 与 0–1 强度。 */
    record VisualLayer(WeatherType type, ResourceLocation typeId, String typeKey, float intensity) {}

    public static void tick(Minecraft minecraft) {
        ClientWeatherExposure.update(minecraft);
        updateLayers(minecraft);
        ClientLevel level = minecraft.level;
        LocalPlayer player = minecraft.player;
        if (level == null || player == null || layers.isEmpty()) {
            return;
        }
        int budget = MAX_PARTICLES_PER_TICK;
        for (VisualLayer layer : layers) {
            budget -= spawnParticles(level, player, layer, budget);
            if (budget <= 0) {
                break;
            }
        }
        playSounds(level, player);
    }

    /**
     * 网络包到达时立即刷新层列表：客户端 tick 早于本帧渲染，如果等下一 tick，
     * 切到自定义天气后的第一帧仍会显示原版雨雪。
     */
    public static void refresh(Minecraft minecraft) {
        updateLayers(minecraft);
    }

    /** 断线/换维度时清空渐变缓存（层列表由 {@link #setLayers} 另行清空）。 */
    public static void clearTransitions() {
        ClientWeatherTransitions.clear();
    }

    private static void updateLayers(Minecraft minecraft) {
        ClientLevel level = minecraft.level;
        LocalPlayer player = minecraft.player;
        if (level == null || player == null) {
            ClientWeatherTransitions.clear();
            setLayers(List.of());
            return;
        }
        boolean isolated = level.getBiome(player.blockPosition()).is(StormTags.STORM_ISOLATED);
        // 隔离群系 + patch：该处的天气由服务端逐玩家片包驱动，视觉层不要再用风暴快照覆盖
        boolean patchDriven = isolated && "patch".equals(ClientStormCache.isolatedMode());
        boolean mobile = ClientStormCache.active() && !patchDriven;

        List<VisualLayer> resolved = new ArrayList<>();
        List<WeatherStatePayload.Layer> payload = ClientWeatherLayers.layers();
        if (!payload.isEmpty()) {
            for (WeatherStatePayload.Layer layer : payload) {
                VisualLayer visual = resolve(level, layer.typeId(), layer.typeKey(), layer.intensity());
                if (visual != null) {
                    resolved.add(visual);
                }
            }
        }
        // 片模式（或移动模式尚未收到快照）没有叠加信息：回落到 targets 的单层语义。
        // 移动模式有风暴快照时不能用 targets：那可能是上一个片模式维度残留的值。
        if (resolved.isEmpty() && !mobile) {
            VisualLayer visual = resolve(level, ClientWeatherTargets.typeId(),
                    ClientWeatherTargets.typeKey(), ClientWeatherTargets.intensity());
            if (visual != null) {
                resolved.add(visual);
            }
        }
        setLayers(ClientWeatherTransitions.blend(resolved, ClientWeatherConfig.transitionTicks()));
    }

    private static VisualLayer resolve(ClientLevel level, String typeId, String typeKey, float rawIntensity) {
        if (typeId == null || typeId.isEmpty()) {
            return null;
        }
        float intensity = Mth.clamp(Float.isFinite(rawIntensity) ? rawIntensity : 0.0F, 0.0F, 1.0F);
        if (intensity <= 0.01F) {
            return null;
        }
        ResourceLocation id = ResourceLocation.tryParse(typeId);
        WeatherType type = id == null ? null : WeatherTypes.resolve(level, id);
        if (type == null) {
            return null;
        }
        // 与客户端雨量阈值对齐：降水类型低于 0.2 时既不算下雨，也不该继续压雾/喷粒子
        if (type.hasPrecipitation() && intensity < ClientStormWeather.VISIBLE_RAIN_THRESHOLD) {
            return null;
        }
        return new VisualLayer(type, id, typeKey == null ? "" : typeKey, intensity);
    }

    private static void setLayers(List<VisualLayer> value) {
        layers = value.isEmpty() ? List.of() : List.copyOf(value);
        VisualLayer dominant = layers.isEmpty() ? null : layers.get(0);
        currentType = dominant == null ? null : dominant.type();
        currentIntensity = dominant == null ? 0.0F : dominant.intensity();
        if (!SOUND_TICKERS.isEmpty()) {
            Set<ResourceLocation> present = new HashSet<>();
            for (VisualLayer layer : layers) {
                present.add(layer.typeId());
            }
            SOUND_TICKERS.keySet().removeIf(id -> !present.contains(id));
        }
    }

    /** 原版雨雪渲染是否应由本层接管（任一层自定义类型带粒子且强度可见）。 */
    public static boolean suppressVanillaRain() {
        for (VisualLayer layer : layers) {
            if (layer.intensity() > 0.01F && layer.type().particle().isPresent()) {
                return true;
            }
        }
        return false;
    }

    /** 当前是否有自定义天气在渲染（用于雾效等全局表现）。 */
    public static boolean active() {
        return currentType != null && currentIntensity > 0.01F;
    }

    /** 主导层天气类型；没有自定义天气时为 {@code null}。天空覆盖层用它读取 {@code visuals}。 */
    public static WeatherType currentType() {
        return currentType;
    }

    /** 主导层天气强度（0–1，已乘室内视觉系数）；天空覆盖层按它把覆盖效果从原版平滑过渡到类型定义。 */
    public static float currentIntensity() {
        return currentIntensity * ClientWeatherExposure.visualFactor();
    }

    /** 相机是否处于水下/岩浆/细雪等由原版接管雾效的流体中。 */
    private static boolean inFluidFog() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.level != null
                && minecraft.gameRenderer.getMainCamera().getFluidInCamera() != FogType.NONE;
    }

    /**
     * 雾颜色：按各层强度加权混合出目标色，再按总强度从原版雾色插值过去；
     * 只有主导层有雾色时行为与单层版本一致，避免进出风暴时硬跳。
     */
    public static void applyFogColor(ViewportEvent.ComputeFogColor event) {
        if (inFluidFog()) {
            return;             // 水下/岩浆/细雪雾由原版与其它模组接管，天气雾不应覆盖
        }
        float visual = ClientWeatherExposure.visualFactor();
        if (visual <= 0.01F) {
            return;             // 室内：不引入天气雾，窗外看起来仍是原版雾
        }
        float weight = 0.0F;
        float red = 0.0F;
        float green = 0.0F;
        float blue = 0.0F;
        for (VisualLayer layer : layers) {
            WeatherType.Visuals visuals = layer.type().visuals();
            if (visuals.fogColor().isEmpty()) {
                continue;
            }
            int color = visuals.fogColor().get();
            float intensity = layer.intensity();
            weight += intensity;
            red += ((color >> 16) & 0xFF) / 255.0F * intensity;
            green += ((color >> 8) & 0xFF) / 255.0F * intensity;
            blue += (color & 0xFF) / 255.0F * intensity;
        }
        if (weight <= 0.01F) {
            return;
        }
        float blend = Mth.clamp(weight * ClientWeatherExposure.visualFactor(), 0.0F, 1.0F);
        event.setRed(Mth.lerp(blend, event.getRed(), red / weight));
        event.setGreen(Mth.lerp(blend, event.getGreen(), green / weight));
        event.setBlue(Mth.lerp(blend, event.getBlue(), blue / weight));
    }

    /**
     * 雾距离：多层叠加时视距取各层中最小值（雾最浓的那层），避免不同天气的视距互相覆盖；
     * near 与 far 都来自同一个胜出层，并按该层强度在「原版值」与类型定义之间插值。
     */
    public static void applyFogPlanes(ViewportEvent.RenderFog event) {
        if (inFluidFog()) {
            return;
        }
        float visual = ClientWeatherExposure.visualFactor();
        if (visual <= 0.01F) {
            return;
        }
        float baseNear = event.getNearPlaneDistance();
        float baseFar = event.getFarPlaneDistance();
        float bestNear = baseNear;
        float bestFar = Float.MAX_VALUE;
        boolean found = false;
        for (VisualLayer layer : layers) {
            WeatherType.Visuals visuals = layer.type().visuals();
            if (visuals.fogNear().isEmpty() && visuals.fogFar().isEmpty()) {
                continue;
            }
            float intensity = Mth.clamp(layer.intensity() * visual, 0.0F, 1.0F);
            if (intensity <= 0.01F) {
                continue;
            }
            float near = visuals.fogNear().orElse(baseNear);
            float far = visuals.fogFar().orElse(baseFar);
            float blendedFar = Mth.lerp(intensity, baseFar, far);
            if (blendedFar < bestFar) {
                bestFar = blendedFar;
                bestNear = Mth.lerp(intensity, baseNear, near);
                found = true;
            }
        }
        if (!found) {
            return;
        }
        event.setNearPlaneDistance(bestNear);
        event.setFarPlaneDistance(bestFar);
        event.setCanceled(true);
    }

    /** 渲染一层的粒子，返回实际消耗的数量（全局预算由调用方控制）。 */
    private static int spawnParticles(ClientLevel level, LocalPlayer player, VisualLayer layer, int budget) {
        WeatherType type = layer.type();
        float visual = ClientWeatherExposure.visualFactor();
        if (type.particle().isEmpty() || type.particleCount() <= 0 || budget <= 0 || visual <= 0.01F) {
            return 0;
        }
        ParticleOptions options = null;
        var particleType = BuiltInRegistries.PARTICLE_TYPE.getOptional(type.particle().get()).orElse(null);
        if (particleType instanceof SimpleParticleType simple) {
            options = simple;
        }
        if (options == null) {
            if (WARNED_PARTICLES.add(type.particle().get())) {
                Localizedrain.LOGGER.warn(
                        "[localizedrain] weather type '{}' uses particle '{}' which is {}; "
                                + "the built-in visuals can only spawn simple particles",
                        type.displayKey(), type.particle().get(),
                        particleType == null ? "not registered" : "not a simple particle");
            }
            return 0;
        }
        int count = Mth.ceil(type.particleCount() * Math.max(0.02F, layer.intensity() * visual));
        count = Math.min(count, budget);
        WeatherType.Motion motion = type.visuals().particleMotion().orElse(WeatherType.Motion.DEFAULT);
        Vec3 center = player.position();
        int spawned = 0;
        for (int i = 0; i < count; i++) {
            double x = center.x + (RANDOM.nextDouble() - 0.5D) * SPAWN_RADIUS * 2.0D;
            double z = center.z + (RANDOM.nextDouble() - 0.5D) * SPAWN_RADIUS * 2.0D;
            double y = center.y + RANDOM.nextDouble() * 8.0D - 2.0D;
            if (!level.getChunkSource().hasChunk(
                    SectionPos.blockToSectionCoord(Mth.floor(x)), SectionPos.blockToSectionCoord(Mth.floor(z)))) {
                continue;
            }
            level.addParticle(options, x, y, z, motion.x(), motion.y(), motion.z());
            spawned++;
        }
        return spawned;
    }

    /** 最多 {@link #MAX_SOUND_LAYERS} 层同时发声，每层独立计时，避免叠满时声音糊成一片。 */
    private static void playSounds(ClientLevel level, LocalPlayer player) {
        int playing = 0;
        for (VisualLayer layer : layers) {
            if (playing >= MAX_SOUND_LAYERS) {
                break;
            }
            WeatherType type = layer.type();
            if (type.sound().isEmpty() || type.soundIntervalTicks() <= 0) {
                continue;
            }
            int ticks = SOUND_TICKERS.merge(layer.typeId(), 1, Integer::sum);
            if (ticks < type.soundIntervalTicks()) {
                continue;
            }
            SOUND_TICKERS.put(layer.typeId(), 0);
            SoundEvent sound = BuiltInRegistries.SOUND_EVENT.getOptional(type.sound().get()).orElse(null);
            if (sound == null) {
                continue;
            }
            float volume = 0.35F * layer.intensity() * ClientWeatherExposure.soundFactor();
            if (volume <= 0.01F) {
                continue;
            }
            Vec3 center = player.position();
            level.playLocalSound(center.x, center.y, center.z, sound, SoundSource.WEATHER,
                    volume, ClientWeatherExposure.pitch(), false);
            playing++;
        }
    }
}
