package com.spectrum255.localizedrain.storm;

import com.spectrum255.localizedrain.Config;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * 风暴与地形/群系的交互。
 *
 * <p>每个风暴按 {@code sampleIntervalTicks} 采样中心处的地表高度与群系降水，得到三个系数：</p>
 * <ul>
 *   <li>雨量：随海拔线性衰减，干旱群系再乘一个固定比例；</li>
 *   <li>雷量：随海拔线性增强（上限 1），干旱群系按另一比例衰减；</li>
 *   <li>速度：随海拔略微加快（高空风更强）。</li>
 * </ul>
 *
 * <p>结果缓存在 {@link StormObject} 上，由 {@code StormObject#baseRain()/baseThunder()} 与
 * {@code tick()} 直接消费；未加载区块或总开关关闭时保持 1.0。</p>
 */
final class StormTerrain {
    private StormTerrain() {}

    /** 每 tick 由 StormManager 在推进风暴前调用；内部按采样间隔节流。 */
    static void update(ServerLevel level, StormObject storm) {
        long now = level.getGameTime();
        long interval = Math.max(1, WindField.intConfig(Config.STORM_TERRAIN_SAMPLE_INTERVAL_TICKS, 20));
        if (storm.terrainSampledAt != 0L && now - storm.terrainSampledAt < interval) {
            return;
        }
        storm.terrainSampledAt = now;
        if (!enabled()) {
            storm.terrainRainFactor = 1.0F;
            storm.terrainThunderFactor = 1.0F;
            storm.terrainSpeedFactor = 1.0D;
            storm.terrainSteer = 0.0D;
            return;
        }
        int bx = Mth.floor(storm.x);
        int bz = Mth.floor(storm.z);
        int sampleY = Mth.floor((storm.minY + storm.maxY) * 0.5D);
        BlockPos probe = new BlockPos(bx, sampleY, bz);
        // 1.21.1 把 LevelReader#hasChunkAt(BlockPos) 标记为过时，直接按区块坐标判断
        if (!level.hasChunk(bx >> 4, bz >> 4)) {
            // 未加载：保留上一次的系数（首次采样前默认 1.0）
            return;
        }
        int surfaceY = level.getHeight(Heightmap.Types.MOTION_BLOCKING, bx, bz);
        Biome biome = level.getBiome(probe).value();
        // 1.21.1 中 Biome#getPrecipitationAt 已标记 forRemoval，改用群系级降雨标记判断干旱（沙漠/恶地等）
        boolean dry = !biome.hasPrecipitation();

        int range = Math.max(16, WindField.intConfig(Config.STORM_TERRAIN_ALTITUDE_RANGE, 128));
        // 1.21.1 把 Level#getSeaLevel 标记为过时；主世界海平面固定 63，其它维度按 63 计算并钳到 0 即可
        int seaLevel = 63;
        float altitude = Mth.clamp((surfaceY - seaLevel) / (float) range, 0.0F, 1.0F);
        double rainFalloff = WindField.doubleConfig(Config.STORM_TERRAIN_ALTITUDE_RAIN_FALLOFF, 0.5D);
        double thunderBoost = WindField.doubleConfig(Config.STORM_TERRAIN_HIGHLAND_THUNDER_BOOST, 0.25D);
        double speedBoost = WindField.doubleConfig(Config.STORM_TERRAIN_HIGHLAND_SPEED_BOOST, 0.2D);
        double dryRain = WindField.doubleConfig(Config.STORM_TERRAIN_DRY_BIOME_RAIN_FACTOR, 0.35D);
        double dryThunder = WindField.doubleConfig(Config.STORM_TERRAIN_DRY_BIOME_THUNDER_FACTOR, 0.5D);

        float rainFactor = (float) Mth.clamp(1.0D - rainFalloff * altitude, 0.0D, 1.0D);
        float thunderFactor = (float) Mth.clamp(1.0D + thunderBoost * altitude, 0.0D, 1.0F);
        double speedFactor = Math.max(0.05D, 1.0D + speedBoost * altitude);
        if (dry) {
            rainFactor *= (float) Mth.clamp(dryRain, 0.0D, 1.0D);
            thunderFactor *= (float) Mth.clamp(dryThunder, 0.0D, 1.0D);
        }
        storm.terrainSurfaceY = surfaceY;
        storm.terrainRainFactor = rainFactor;
        storm.terrainThunderFactor = thunderFactor;
        storm.terrainSpeedFactor = speedFactor;
        storm.terrainSteer = computeSteer(level, storm, bx, bz, surfaceY);
    }

    /**
     * 前方地表比中心高 8 格以上时朝地势更低的一侧偏转，幅度随抬升增大（最大约 34°）。
     * 三个探针任一未加载就按比例回正，避免在区块边界抖动。
     */
    private static double computeSteer(ServerLevel level, StormObject storm, int bx, int bz, int currentY) {
        double speed = Math.sqrt(storm.velocityX * storm.velocityX + storm.velocityZ * storm.velocityZ);
        int radius = (int) Math.round(storm.radiusAt());
        if (speed < 1.0E-3D || radius < 8) {
            return 0.0D;
        }
        double dirX = storm.velocityX / speed;
        double dirZ = storm.velocityZ / speed;
        Integer ahead = heightIfLoaded(level, bx + (int) Math.round(dirX * radius), bz + (int) Math.round(dirZ * radius));
        Integer left = heightIfLoaded(level, bx - (int) Math.round(dirZ * radius * 0.7D),
                bz + (int) Math.round(dirX * radius * 0.7D));
        Integer right = heightIfLoaded(level, bx + (int) Math.round(dirZ * radius * 0.7D),
                bz - (int) Math.round(dirX * radius * 0.7D));
        if (ahead == null || left == null || right == null) {
            return storm.terrainSteer * 0.8D;
        }
        int rise = ahead - currentY;
        if (rise < 8) {
            return storm.terrainSteer * 0.7D;
        }
        double magnitude = Mth.clamp(rise / 64.0D, 0.15D, 0.6D);
        // 速度为 +x、前方为 +x 时，+steer 把方向转向 +z，也就是 left 探针那一侧
        return left <= right ? magnitude : -magnitude;
    }

    private static Integer heightIfLoaded(ServerLevel level, int bx, int bz) {
        if (!level.hasChunk(bx >> 4, bz >> 4)) {
            return null;
        }
        return level.getHeight(Heightmap.Types.MOTION_BLOCKING, bx, bz);
    }

    private static boolean enabled() {
        try {
            return Config.STORM_TERRAIN_INFLUENCE.get();
        } catch (RuntimeException ignored) {
            return false;
        }
    }
}
