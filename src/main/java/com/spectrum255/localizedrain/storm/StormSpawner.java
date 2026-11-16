package com.spectrum255.localizedrain.storm;

import com.spectrum255.localizedrain.Config;
import com.spectrum255.localizedrain.Localizedrain;
import com.spectrum255.localizedrain.api.StormProfile;
import com.spectrum255.localizedrain.api.StormProfiles;
import com.spectrum255.localizedrain.api.StormSpawnSpec;
import com.spectrum255.localizedrain.api.WeatherMode;
import com.spectrum255.localizedrain.api.WeatherTypes;
import com.spectrum255.localizedrain.weather.LoadedChunks;
import com.spectrum255.localizedrain.weather.WeatherBiomeFilter;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/** 自然生成：上风向选点 → 生成点过滤器 → 上限检查 → 抽档案 → 生成（可选洞穴）。 */
final class StormSpawner {
    private StormSpawner() {}

    static void tick(ServerLevel level) {
        // 双重保险：只有 MOBILE 模式才允许自然生成（StormManager.tick 已做入口检查）。
        if (WeatherMode.current() != WeatherMode.MOBILE) {
            return;
        }
        StormData data = StormManager.data(level);
        if (level.getGameTime() < data.nextSpawnTick()) {
            return;
        }
        int interval = WindField.intConfig(Config.STORM_SPAWN_INTERVAL_TICKS, 200);
        data.setNextSpawnTick(level.getGameTime() + Math.max(20, interval));
        data.setDirty();
        // 维度守卫放在节流之后：下界/末地每 tick 都会被调用，日志必须随 nextSpawnTick 一起节流，
        // 否则每 tick 一条 [LRDBG]，21 分钟能刷出 4.5 万行。
        if (!level.dimensionType().hasSkyLight() || level.dimensionType().hasCeiling()) {
            debugSpawn("dimension has no sky light or has a ceiling");
            return;                     // 自然风暴只生成在有天光、无天花板的维度（下界/末地不会长风暴）
        }

        int maxStorms = WindField.intConfig(Config.MAX_STORMS_PER_DIMENSION, 8);
        long naturalStorms = StormManager.all(level).stream().filter(storm -> !storm.manual()).count();
        if (naturalStorms >= maxStorms) {
            debugSpawn("natural storm cap reached");
            return;                                     // 上限只约束自然风暴，手动风暴不占用名额
        }
        List<ServerPlayer> players = level.players();
        if (players.isEmpty()) {
            debugSpawn("no players in the dimension");
            return;
        }
        RandomSource random = level.getRandom();

        // 生成率：每次检查只按概率真正尝试生成。默认 0.035 约等于每 4.8 分钟一个（旧实测约 1/14 分钟，约 3 倍）。
        double spawnChance = WindField.doubleConfig(Config.NATURAL_STORM_SPAWN_CHANCE, 0.035D);
        if (spawnChance <= 0.0D || random.nextDouble() >= spawnChance) {
            debugSpawn("spawn chance roll failed");
            return;
        }

        boolean anywhere = "anywhere_loaded".equals(
                WindField.stringConfig(Config.STORM_SPAWN_PLACEMENT, "near_player"));
        double x;
        double z;
        BlockPos probe;
        if (anywhere) {
            // 任意已加载区块：不受「每个玩家附近」上限约束，风暴可能出现在探索过的任何地方
            long[] loaded = LoadedChunks.snapshot(level);
            if (loaded.length == 0) {
                debugSpawn("no loaded chunks to place a storm in");
                return;
            }
            long chunkPos = loaded[random.nextInt(loaded.length)];
            int chunkX = ChunkPos.getX(chunkPos);
            int chunkZ = ChunkPos.getZ(chunkPos);
            if (!level.getChunkSource().hasChunk(chunkX, chunkZ)) {
                debugSpawn("chosen loaded chunk vanished before spawning");
                return;
            }
            x = (chunkX << 4) + 8.0D + (random.nextDouble() * 8.0D - 4.0D);
            z = (chunkZ << 4) + 8.0D + (random.nextDouble() * 8.0D - 4.0D);
            probe = new BlockPos((int) Math.floor(x), level.getMinBuildHeight(), (int) Math.floor(z));
        } else {
            // 按随机顺序遍历玩家：当前玩家达到上限时换下一个，而不是整次放弃
            int perPlayerCap = WindField.intConfig(Config.MAX_STORMS_PER_PLAYER, 2);
            int start = random.nextInt(players.size());
            ServerPlayer player = null;
            for (int offset = 0; offset < players.size() && player == null; offset++) {
                ServerPlayer candidate = players.get((start + offset) % players.size());
                if (ownedBy(level, candidate) < perPlayerCap) {
                    player = candidate;
                }
            }
            if (player == null) {
                debugSpawn("per-player natural storm cap reached");
                return;
            }
            int configuredMin = WindField.intConfig(Config.STORM_SPAWN_MIN_DISTANCE, 300);
            int configuredMax = Math.max(configuredMin, WindField.intConfig(Config.STORM_SPAWN_MAX_DISTANCE, 700));
            // 只在已加载半径内选点：300–700 格通常超过模拟距离（12 区块 = 192 格），probe 未加载会被静默丢弃。
            double loadedRadius = level.getServer().getPlayerList().getSimulationDistance() * 16.0D - 32.0D;
            if (loadedRadius < 48.0D) {
                debugSpawn("simulation distance is too small for a spawn probe");
                return;
            }
            double effectiveMax = Math.min(configuredMax, loadedRadius);
            double effectiveMin = Math.min(configuredMin, effectiveMax * 0.6D);
            double distance = effectiveMin + random.nextDouble() * Math.max(1.0D, effectiveMax - effectiveMin);

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
            x = player.getX() - directionX * distance;
            z = player.getZ() - directionZ * distance;
            probe = BlockPos.containing(x, player.getY(), z);
        }
        if (!level.isLoaded(probe)) {
            debugSpawn("spawn probe chunk is not loaded");
            return;
        }
        if (StormManager.isIsolatedAt(level, probe)) {
            debugSpawn("spawn probe is in an isolated biome");
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
                debugSpawn("no cave-capable profile or filter rejected");
                return;
            }
            int radius = WindField.intConfig(Config.CAVE_STORM_RADIUS, 64);
            if (StormManager.spawn(level, StormSpawnSpec
                    .builder(holder.key().location(), new Vec3(x, surfaceY, z))
                    .radius(radius)
                    .verticalBand(band[0], band[1])
                    .manual(false)
                    .build()) == null) {
                debugSpawn("StormManager rejected the cave storm");
            }
            return;
        }
        holder = StormProfiles.randomNatural(level, random).orElse(null);
        if (holder == null) {
            debugSpawn("no natural profile available");
            return;
        }
        if (!filterAllows(level, probe, holder.value())) {
            debugSpawn("filter rejected the spawn probe");
            return;
        }
        if (StormManager.spawn(level, StormSpawnSpec
                .builder(holder.key().location(), new Vec3(x, surfaceY, z))
                .manual(false)
                .build()) == null) {
            debugSpawn("StormManager rejected the natural storm");
        }
    }

    /** 调试开关打开时记录自然生成被跳过的原因，便于运行期排查（每个检查周期最多一条）。 */
    private static void debugSpawn(String reason) {
        try {
            if (Config.DEBUG_LOGGING.get()) {
                Localizedrain.LOGGER.info("[LRDBG] storm spawn skipped: {}", reason);
            }
        } catch (IllegalStateException ignored) {
            // 配置尚未加载：静默跳过
        }
    }

    /**
     * 生成点过滤器：只约束生成中心，不拦截后续移动（设计 §7.2）。
     *
     * <p>白名单/黑名单按档案的初始阶段类型判定（避免多阶段档案用后续阶段绕过按类型过滤）；
     * 强制天气按任意阶段匹配（雷暴档案的初始阶段也是雨）。</p>
     */
    private static boolean filterAllows(ServerLevel level, BlockPos pos, StormProfile profile) {
        List<StormProfile.Stage> stages = profile.stages();
        if (stages.isEmpty()) {
            return false;
        }
        List<ResourceLocation> stageTypes = new ArrayList<>(stages.size());
        for (StormProfile.Stage stage : stages) {
            if (!stageTypes.contains(stage.type())) {
                stageTypes.add(stage.type());
            }
        }
        return WeatherBiomeFilter.stormAllowed(level, pos, stages.get(0).type(), stageTypes);
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
