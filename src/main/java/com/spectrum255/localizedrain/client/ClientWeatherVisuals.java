package com.spectrum255.localizedrain.client;

import com.spectrum255.localizedrain.Localizedrain;
import com.spectrum255.localizedrain.api.WeatherType;
import com.spectrum255.localizedrain.api.WeatherTypes;
import com.spectrum255.localizedrain.network.ClientWeatherTargets;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ViewportEvent;

import java.util.HashSet;
import java.util.Set;

/**
 * 客户端天气视觉层：读取当前天气类型（移动模式优先风暴快照，片模式用服务端下发的快照），
 * 消费 {@code particle}/{@code sound}/{@code visuals} 字段，负责自定义粒子、环境音与雾效。
 *
 * <p>内置类型（clear/rain/thunder）保持原版渲染不动；自定义类型只要写了 {@code particle}
 * 就用本层的简单粒子替代原版雨雪粒子（由 {@code LevelRendererMixin} 取消原版渲染）。</p>
 */
public final class ClientWeatherVisuals {
    private static final RandomSource RANDOM = RandomSource.create();
    private static final int SPAWN_RADIUS = 12;
    /** 每 tick 每名玩家的粒子上限：数据包写再大的 particle_count 也不会把客户端拖垮。 */
    private static final int MAX_PARTICLES_PER_TICK = 80;
    private static final Set<ResourceLocation> WARNED_PARTICLES = new HashSet<>();

    private static WeatherType currentType;
    private static float currentIntensity;
    private static int soundTicker;

    private ClientWeatherVisuals() {}

    public static void tick(Minecraft minecraft) {
        ClientLevel level = minecraft.level;
        LocalPlayer player = minecraft.player;
        if (level == null || player == null) {
            currentType = null;
            currentIntensity = 0.0F;
            return;
        }
        String typeId = currentTypeId(level, player);
        float intensity = currentIntensity;
        if (typeId == null || typeId.isEmpty()) {
            currentType = null;
            currentIntensity = 0.0F;
            soundTicker = 0;
            return;
        }
        ResourceLocation id = ResourceLocation.tryParse(typeId);
        WeatherType type = id == null ? null : WeatherTypes.resolve(level, id);
        currentType = type;
        currentIntensity = type == null ? 0.0F : Mth.clamp(intensity, 0.0F, 1.0F);
        if (type == null || currentIntensity <= 0.01F) {
            return;
        }
        spawnParticles(level, player, type, currentIntensity);
        playSound(level, player, type, currentIntensity);
    }

    /**
     * 网络包到达时立即刷新当前类型：客户端 tick 早于本帧渲染，如果等下一 tick，
     * 切到自定义天气后的第一帧仍会显示原版雨雪。
     */
    public static void refresh(Minecraft minecraft) {
        ClientLevel level = minecraft.level;
        LocalPlayer player = minecraft.player;
        if (level == null || player == null) {
            currentType = null;
            currentIntensity = 0.0F;
            return;
        }
        String typeId = currentTypeId(level, player);
        if (typeId == null || typeId.isEmpty()) {
            currentType = null;
            currentIntensity = 0.0F;
            soundTicker = 0;
            return;
        }
        ResourceLocation id = ResourceLocation.tryParse(typeId);
        currentType = id == null ? null : WeatherTypes.resolve(level, id);
    }

    /** 原版雨雪渲染是否应由本层接管（自定义类型带粒子时）。 */
    public static boolean suppressVanillaRain() {        WeatherType type = currentType;
        return type != null && currentIntensity > 0.01F && type.particle().isPresent();
    }

    /** 当前是否有一个自定义天气在渲染（用于雾效等全局表现）。 */
    public static boolean active() {
        return currentType != null && currentIntensity > 0.01F;
    }

    /** 雾颜色：只覆盖有 {@code visuals.fog_color} 的自定义类型。 */
    public static void applyFogColor(ViewportEvent.ComputeFogColor event) {
        WeatherType type = currentType;
        if (type == null || currentIntensity <= 0.01F) {
            return;
        }
        type.visuals().fogColor().ifPresent(color -> {
            event.setRed(((color >> 16) & 0xFF) / 255.0F);
            event.setGreen(((color >> 8) & 0xFF) / 255.0F);
            event.setBlue((color & 0xFF) / 255.0F);
        });
    }

    /** 雾距离：按强度在「原版值」与类型定义之间插值，避免切换类型时跳变。 */
    public static void applyFogPlanes(ViewportEvent.RenderFog event) {
        WeatherType type = currentType;
        if (type == null || currentIntensity <= 0.01F) {
            return;
        }
        WeatherType.Visuals visuals = type.visuals();
        if (visuals.fogNear().isEmpty() && visuals.fogFar().isEmpty()) {
            return;
        }
        float near = visuals.fogNear().orElse(event.getNearPlaneDistance());
        float far = visuals.fogFar().orElse(event.getFarPlaneDistance());
        event.setNearPlaneDistance(Mth.lerp(currentIntensity, event.getNearPlaneDistance(), near));
        event.setFarPlaneDistance(Mth.lerp(currentIntensity, event.getFarPlaneDistance(), far));
        event.setCanceled(true);
    }

    /** 当前天气类型 id；移动模式取覆盖玩家的最强风暴，否则取服务端下发的快照。 */
    private static String currentTypeId(ClientLevel level, LocalPlayer player) {
        if (ClientStormCache.active()) {
            String best = null;
            float bestIntensity = 0.0F;
            double falloffRatio = ClientStormCache.falloffRatio();
            for (ClientStormCache.Storm storm : ClientStormCache.storms()) {
                double distance = Math.hypot(player.getX() - storm.x(), player.getZ() - storm.z());
                double falloff = Math.max(1.0D, storm.radius() * falloffRatio);
                double horizontal = Mth.clamp((storm.radius() + falloff - distance) / falloff, 0.0D, 1.0D);
                if (horizontal <= 0.0D || player.getY() < storm.minY() || player.getY() > storm.maxY()) {
                    continue;
                }
                float intensity = storm.hasPrecipitation()
                        ? (float) (storm.rain() * horizontal)
                        : (Math.max(storm.rain(), storm.thunder()) > 0.01F ? (float) horizontal : 0.0F);
                if (intensity > bestIntensity) {
                    bestIntensity = intensity;
                    best = storm.typeId().toString();
                }
            }
            currentIntensity = bestIntensity;
            return best;
        }
        currentIntensity = ClientWeatherTargets.intensity();
        return ClientWeatherTargets.typeId();
    }

    private static void spawnParticles(ClientLevel level, LocalPlayer player, WeatherType type, float intensity) {
        if (type.particle().isEmpty() || type.particleCount() <= 0) {
            return;
        }
        ParticleOptions options = null;
        var particleType = BuiltInRegistries.PARTICLE_TYPE.getOptional(type.particle().get()).orElse(null);
        if (particleType instanceof SimpleParticleType simple) {
            options = simple;
        }
        if (options == null) {
            if (particleType != null && WARNED_PARTICLES.add(type.particle().get())) {
                Localizedrain.LOGGER.warn(
                        "[localizedrain] weather type '{}' uses particle '{}' which is not a simple particle; "
                                + "the built-in visuals can only spawn simple particles",
                        type.displayKey(), type.particle().get());
            }
            return;
        }
        int count = Mth.ceil(type.particleCount() * Math.max(0.02F, intensity));
        count = Math.min(count, MAX_PARTICLES_PER_TICK);
        Vec3 center = player.position();
        for (int i = 0; i < count; i++) {
            double x = center.x + (RANDOM.nextDouble() - 0.5D) * SPAWN_RADIUS * 2.0D;
            double z = center.z + (RANDOM.nextDouble() - 0.5D) * SPAWN_RADIUS * 2.0D;
            double y = center.y + RANDOM.nextDouble() * 8.0D - 2.0D;
            if (!level.getChunkSource().hasChunk(
                    SectionPos.blockToSectionCoord(Mth.floor(x)), SectionPos.blockToSectionCoord(Mth.floor(z)))) {
                continue;
            }
            level.addParticle(options, x, y, z, 0.0D, -0.05D, 0.0D);
        }
    }

    private static void playSound(ClientLevel level, LocalPlayer player, WeatherType type, float intensity) {
        if (type.sound().isEmpty() || type.soundIntervalTicks() <= 0) {
            return;
        }
        if (++soundTicker < type.soundIntervalTicks()) {
            return;
        }
        soundTicker = 0;
        SoundEvent sound = BuiltInRegistries.SOUND_EVENT.getOptional(type.sound().get()).orElse(null);
        if (sound == null) {
            return;
        }
        Vec3 center = player.position();
        level.playLocalSound(center.x, center.y, center.z, sound, SoundSource.WEATHER,
                0.35F * intensity, 1.0F, false);
    }
}
