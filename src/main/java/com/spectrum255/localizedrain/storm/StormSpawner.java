package com.spectrum255.localizedrain.storm;

import com.spectrum255.localizedrain.Config;
import com.spectrum255.localizedrain.Localizedrain;
import com.spectrum255.localizedrain.api.StormProfile;
import com.spectrum255.localizedrain.api.StormProfiles;
import com.spectrum255.localizedrain.api.StormSpawnSpec;
import com.spectrum255.localizedrain.api.WeatherMode;
import com.spectrum255.localizedrain.api.WeatherType;
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
import net.minecraft.world.level.biome.Biome;
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
        // 无天光/有天花板的维度不生成 SURFACE 风暴，但允许 BAND 风暴（生成在玩家高度附近的带里）。
        boolean skyLit = level.dimensionType().hasSkyLight() && !level.dimensionType().hasCeiling();
        boolean noSky = !skyLit;
        if (noSky) {
            debugSpawn("dimension has no sky light or has a ceiling; band storms only");
        }

        int maxStorms = WindField.intConfig(Config.MAX_STORMS_PER_DIMENSION, 8);
        long naturalStorms = StormManager.all(level).stream()
                .filter(storm -> !storm.manual())
                // 类型被数据包移除的风暴不在客户端显示，也不该继续占用生成名额
                .filter(storm -> StormManager.hasRegisteredType(level, storm))
                .count();
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

        boolean anywhere = Config.stormSpawnPlacement()
                == com.spectrum255.localizedrain.api.StormSpawnPlacement.ANYWHERE_LOADED;
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
            // 加载范围同时受模拟距离与视距限制：玩家把视距调到 2 区块时，模拟距离仍是 12，但客户端只加载 32 格。
            int simulation = level.getServer().getPlayerList().getSimulationDistance();
            int view = level.getServer().getPlayerList().getViewDistance();
            double loadedRadius = Math.min(simulation, view) * 16.0D - 32.0D;
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
        int surfaceY = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, probe).getY();
        BlockPos surfaceProbe = new BlockPos(probe.getX(), surfaceY, probe.getZ());
        // 隔离检查先于洞穴列扫描：隔离群系不该为一次注定失败的生成付出扫描成本。
        // 无天光维度没有真正的地表（高度图落在天花板附近），跳过地表隔离检查。
        if (!noSky && StormManager.isIsolatedAt(level, surfaceProbe)) {
            debugSpawn("spawn probe is in an isolated biome");
            return;
        }
        boolean cave = noSky || (WindField.boolConfig(Config.NATURAL_CAVE_STORMS, false) && random.nextFloat() < 0.4F);
        double[] band = cave
                ? (noSky ? bandAround(level, players, probe) : findCaveBand(level, probe.getX(), probe.getZ(), surfaceY))
                : null;
        // 群系/隔离/过滤器必须按最终生成点采样：SURFACE 用地表、BAND 用带中心。
        // anywhere_loaded 选点时用的最低建筑高度探针只负责判断区块是否已加载。
        BlockPos sampleProbe = band != null
                ? new BlockPos(probe.getX(), (int) Math.round((band[0] + band[1]) * 0.5D), probe.getZ())
                : surfaceProbe;
        if (band != null && StormManager.isIsolatedAt(level, sampleProbe)) {
            // 地表不隔离、带中心隔离：放弃洞穴，回退到地表生成
            band = null;
            sampleProbe = surfaceProbe;
        }
        Holder.Reference<StormProfile> holder = null;
        if (band != null) {
            // 洞穴风暴只接受「会降水、且不会打雷」的类型：BAND 落雷要求落点见天，雷暴在洞里永远不落雷
            BlockPos caveProbe = sampleProbe;
            // 无天光维度里没有「洞穴/地表」之分，任意档案都允许生成为带风暴；
            // 有天光维度沿用洞穴约束（会降水、不会打雷）。
            holder = StormProfiles.randomNatural(level, random,
                    profile -> (noSky || isCaveCapable(level, profile))
                            && spawnAllowed(level, caveProbe, profile, noSky)).orElse(null);
            if (holder == null) {
                debugSpawn("no cave-capable profile passes biome/type filter; falling back to surface");
                band = null;
                sampleProbe = surfaceProbe;
            }
        }
        if (band == null) {
            if (noSky) {
                debugSpawn("no band-capable profile in a no-skylight dimension");
                return;
            }
            BlockPos surfaceQuery = sampleProbe;
            holder = StormProfiles.randomNatural(level, random,
                    profile -> spawnAllowed(level, surfaceQuery, profile)).orElse(null);
            if (holder == null) {
                debugSpawn("no natural profile passes biome/type filter");
                return;
            }
            if (StormManager.spawn(level, StormSpawnSpec
                    .builder(holder.key().location(), new Vec3(x, surfaceY, z))
                    .manual(false)
                    .build()) == null) {
                debugSpawn("StormManager rejected the natural storm");
            }
            return;
        }
        int radius = WindField.intConfig(Config.CAVE_STORM_RADIUS, 64);
        double anchorY = noSky ? (band[0] + band[1]) * 0.5D : surfaceY;
        if (StormManager.spawn(level, StormSpawnSpec
                .builder(holder.key().location(), new Vec3(x, anchorY, z))
                .radius(radius)
                .verticalBand(band[0], band[1])
                .manual(false)
                .build()) == null) {
            debugSpawn("StormManager rejected the cave storm");
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
     * 位置相关的完整生成过滤：档案允许的生物群系 + 天气类型/白黑名单。
     *
     * <p>把过滤放进加权抽取的候选集合里，避免先抽到一个注定被拒绝的档案再浪费这次尝试。</p>
     */
    private static boolean spawnAllowed(ServerLevel level, BlockPos pos, StormProfile profile) {
        return spawnAllowed(level, pos, profile, false);
    }

    /**
     * 与 {@link #spawnAllowed(ServerLevel, BlockPos, StormProfile)} 相同，但可以跳过默认的「降水群系」规则。
     * 无天光维度（下界/自定义洞穴维度）的群系通常不降水，默认规则会把所有风暴都拒掉。
     */
    private static boolean spawnAllowed(ServerLevel level, BlockPos pos, StormProfile profile,
                                        boolean ignoreDefaultBiomeRule) {
        return profile.allowsDimension(level)
                && profile.allowsSkylight(level)
                && typesAllowSkylight(level, profile)
                && biomeAllows(level, pos, profile, ignoreDefaultBiomeRule)
                && filterAllows(level, pos, profile);
    }

    /** 无天光维度里跳过任一阶段声明了 {@code requires_skylight} 的档案。 */
    private static boolean typesAllowSkylight(ServerLevel level, StormProfile profile) {
        if (level.dimensionType().hasSkyLight()) {
            return true;
        }
        for (StormProfile.Stage stage : profile.stages()) {
            WeatherType type = WeatherTypes.resolve(level, stage.type());
            if (type != null && type.requiresSkylight()) {
                return false;
            }
        }
        return true;
    }

    /** 无天光/有天花板维度没有「地表」：在玩家高度附近取一条竖直带（向上多留空隙）。 */
    private static double[] bandAround(ServerLevel level, List<ServerPlayer> players, BlockPos probe) {
        int refY = players.isEmpty() ? probe.getY() : (int) Math.round(players.get(0).getY());
        int min = level.getMinBuildHeight() + 1;
        int max = level.getMaxBuildHeight() - 2;
        int low = Math.max(min, refY - 24);
        int high = Math.min(max, refY + 40);
        if (high - low < 8) {
            low = min;
            high = max;
        }
        return new double[]{low, high};
    }

    /**
     * 洞穴风暴的类型约束：会降水、且不会打雷。
     * 未知类型按「不合格」处理，避免数据包错误时悄悄生成一个永远不落雷的雷暴。
     */
    private static boolean isCaveCapable(ServerLevel level, StormProfile profile) {
        WeatherType type = WeatherTypes.resolve(level, profile.stage(0).type());
        return type != null && type.hasPrecipitation() && !type.allowsThunder() && !type.requiresSkylight();
    }

    /** 档案的 {@code biomes} 列表优先；未写时走默认筛选（会下雨的 SURFACE 风暴只落在会降水的群系）。 */
    private static boolean biomeAllows(ServerLevel level, BlockPos pos, StormProfile profile,
                                       boolean ignoreDefaultBiomeRule) {
        Holder<Biome> biome = level.getBiome(pos);
        return profile.restrictsBiomes() ? profile.allowsBiome(biome)
                : ignoreDefaultBiomeRule || profile.defaultBiomeAllowed(level, biome);
    }

    /** 生成点过滤器：只约束生成中心，不拦截后续移动（设计 §7.2）。
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
                .filter(storm -> StormManager.hasRegisteredType(level, storm))
                .filter(storm -> {
                    double range = Math.min(
                            WindField.intConfig(Config.STORM_SPAWN_MAX_DISTANCE, 700) * 1.5D,
                            level.getServer().getPlayerList().getSimulationDistance() * 16.0D);
                    double dx = storm.x() - player.getX();
                    double dz = storm.z() - player.getZ();
                    return dx * dx + dz * dz <= range * range;
                })
                .count();
    }
}
