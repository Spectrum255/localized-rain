package com.spectrum255.localizedrain.storm;

import com.spectrum255.localizedrain.Config;
import com.spectrum255.localizedrain.api.StormProfile;
import com.spectrum255.localizedrain.api.StormProfiles;
import com.spectrum255.localizedrain.api.StormSpawnSpec;
import com.spectrum255.localizedrain.api.WeatherTypes;
import com.spectrum255.localizedrain.weather.WeatherBiomeFilter;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/** 自然生成：上风向选点 → 生成点过滤器 → 上限检查 → 抽档案 → 生成（可选洞穴）。 */
final class StormSpawner {
    private StormSpawner() {}

    static void tick(ServerLevel level) {
        if (!level.dimensionType().hasSkyLight() || level.dimensionType().hasCeiling()) {
            return;                     // 自然风暴只生成在有天光、无天花板的维度（下界/末地不会长风暴）
        }
        StormData data = StormManager.data(level);
        if (level.getGameTime() < data.nextSpawnTick()) {
            return;
        }
        int interval = WindField.intConfig(Config.STORM_SPAWN_INTERVAL_TICKS, 200);
        data.setNextSpawnTick(level.getGameTime() + Math.max(20, interval));
        data.setDirty();

        int maxStorms = WindField.intConfig(Config.MAX_STORMS_PER_DIMENSION, 8);
        long naturalStorms = StormManager.all(level).stream().filter(storm -> !storm.manual()).count();
        if (naturalStorms >= maxStorms) {
            return;                                     // 上限只约束自然风暴，手动风暴不占用名额
        }
        List<ServerPlayer> players = level.players();
        if (players.isEmpty()) {
            return;
        }
        RandomSource random = level.getRandom();
        ServerPlayer player = players.get(random.nextInt(players.size()));
        if (ownedBy(level, player) >= WindField.intConfig(Config.MAX_STORMS_PER_PLAYER, 2)) {
            return;
        }

        int minDistance = WindField.intConfig(Config.STORM_SPAWN_MIN_DISTANCE, 300);
        int maxDistance = Math.max(minDistance, WindField.intConfig(Config.STORM_SPAWN_MAX_DISTANCE, 700));
        double distance = minDistance + random.nextDouble() * (maxDistance - minDistance);

        // 上风向生成：把风场方向反过来
        WindField wind = data.wind();
        double speed = Math.hypot(wind.velocityX(), wind.velocityZ());
        double directionX;
        double directionZ;
        if (speed > 1.0E-4D) {
            directionX = wind.velocityX() / speed;
            directionZ = wind.velocityZ() / speed;
        } else {
            double angle = random.nextDouble() * Math.PI * 2.0D;
            directionX = Math.cos(angle);
            directionZ = Math.sin(angle);
        }
        double x = player.getX() - directionX * distance;
        double z = player.getZ() - directionZ * distance;
        BlockPos probe = BlockPos.containing(x, player.getY(), z);
        if (!level.isLoaded(probe)) {
            return;
        }
        if (StormManager.isIsolatedAt(level, probe)) {
            return;                                     // 隔离群系是绝对生成禁区
        }
        int surfaceY = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, probe).getY();

        boolean cave = WindField.boolConfig(Config.NATURAL_CAVE_STORMS, false) && random.nextFloat() < 0.4F;
        double[] band = cave ? findCaveBand(level, probe.getX(), probe.getZ(), surfaceY) : null;
        Holder.Reference<StormProfile> holder;
        if (band != null) {
            // 洞穴风暴只接受降雨档案：BAND 落雷要求落点见天，雷暴档案在洞里永远不落雷
            holder = StormProfiles.randomNatural(level, random,
                    profile -> WeatherTypes.RAIN_ID.equals(profile.stage(0).type())).orElse(null);
            if (holder == null || !filterAllows(level, probe, holder.value())) {
                return;
            }
            int radius = WindField.intConfig(Config.CAVE_STORM_RADIUS, 64);
            StormManager.spawn(level, StormSpawnSpec
                    .builder(holder.key().location(), new Vec3(x, surfaceY, z))
                    .radius(radius)
                    .verticalBand(band[0], band[1])
                    .manual(false)
                    .build());
            return;
        }
        holder = StormProfiles.randomNatural(level, random).orElse(null);
        if (holder == null) {
            return;
        }
        if (!filterAllows(level, probe, holder.value())) {
            return;
        }
        StormManager.spawn(level, StormSpawnSpec
                .builder(holder.key().location(), new Vec3(x, surfaceY, z))
                .manual(false)
                .build());
    }

    /** 生成点过滤器：只约束生成中心，不拦截后续移动（设计 §7.2）。 */
    private static boolean filterAllows(ServerLevel level, BlockPos pos, StormProfile profile) {
        // 同时遵守强制天气、锁定晴天与白/黑名单：池里没有该类型就不生成
        return WeatherBiomeFilter.stormAllowed(level, pos, profile.stage(0).type());
    }

    /** 从地表往下找一处「地板 + 5 格空气」的空间，返回可用的竖直区间。 */
    private static double[] findCaveBand(ServerLevel level, int x, int z, int surfaceY) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos(x, 0, z);
        int bottom = level.getMinBuildHeight() + 8;
        for (int y = surfaceY - 8; y >= bottom; y -= 2) {
            boolean open = true;
            for (int offset = 0; offset <= 4 && open; offset++) {
                cursor.setY(y + offset);
                open = level.getBlockState(cursor).isAir();
            }
            if (!open) {
                continue;
            }
            cursor.setY(y - 1);
            if (level.getBlockState(cursor).isAir()) {
                continue;                               // 需要地板，避免悬在洞里
            }
            return new double[] {y - 1.0D, Math.min(y + 24.0D, surfaceY - 1.0D)};
        }
        return null;
    }

    private static long ownedBy(ServerLevel level, ServerPlayer player) {
        return StormManager.all(level).stream()
                .filter(storm -> !storm.manual())
                .filter(storm -> {
                    double range = WindField.intConfig(Config.STORM_SPAWN_MAX_DISTANCE, 700) * 1.5D;
                    double dx = storm.x() - player.getX();
                    double dz = storm.z() - player.getZ();
                    return dx * dx + dz * dz <= range * range;
                })
                .count();
    }
}
