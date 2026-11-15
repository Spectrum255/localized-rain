package com.spectrum255.localizedrain.storm;

import com.spectrum255.localizedrain.Config;
import com.spectrum255.localizedrain.Localizedrain;
import com.spectrum255.localizedrain.api.StormProfile;
import com.spectrum255.localizedrain.api.StormProfiles;
import com.spectrum255.localizedrain.api.StormTags;
import com.spectrum255.localizedrain.api.StormSnapshot;
import com.spectrum255.localizedrain.api.StormSpawnSpec;
import com.spectrum255.localizedrain.api.WeatherType;
import com.spectrum255.localizedrain.api.WeatherTypes;
import com.spectrum255.localizedrain.api.WindState;
import com.spectrum255.localizedrain.api.event.StormRemoveEvent;
import com.spectrum255.localizedrain.api.event.StormSpawnEvent;
import com.spectrum255.localizedrain.api.event.StormStageChangeEvent;
import com.spectrum255.localizedrain.network.StormSyncPayload;
import com.spectrum255.localizedrain.network.WeatherStatePayload;
import com.spectrum255.localizedrain.weather.WeatherManager;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;

/** 维度级风暴管理：推进、查询、生成、移除。 */
public final class StormManager {
    /** 每个维度一份 StormData，避免在 tick / 判定热路径上反复走 DimensionDataStorage。 */
    private static final Map<ServerLevel, StormData> CACHE = new WeakHashMap<>();
    /** 每个维度上一次全量推送风暴快照的 gameTime。 */
    private static final Map<ServerLevel, Long> LAST_SYNC = new WeakHashMap<>();
    /** 每个玩家上一次收到的 HUD 目标状态；只在变化时发包。 */
    private static final Map<UUID, WeatherStatePayload> LAST_HUD = new HashMap<>();
    /** 同一 tick 内按 4 格立方复用 dominantAt 结果；tick 开头清空。 */
    private static final Map<ServerLevel, Map<Long, StormObject>> DOMINANT_CACHE = new WeakHashMap<>();
    private static final int DOMINANT_CACHE_LIMIT = 65536;
    /** 手动风暴达到配置上限后只警告一次。 */
    private static boolean warnedManualCap;

    private StormManager() {}

    /** 服务器停止或换存档时清空全部静态缓存，避免旧维度/旧存档的引用残留。 */
    public static void resetCaches() {
        CACHE.clear();
        LAST_SYNC.clear();
        LAST_HUD.clear();
        DOMINANT_CACHE.clear();
        warnedManualCap = false;
    }

    /** 配置重载后复位「只警告一次」的标志，让新配置下的问题仍能被记录。 */
    public static void resetWarnings() {
        warnedManualCap = false;
    }

    public static StormData data(ServerLevel level) {
        StormData data = CACHE.get(level);
        if (data == null) {
            data = level.getDataStorage().computeIfAbsent(StormData.factory(), StormData.FILE_ID);
            CACHE.put(level, data);
        }
        return data;
    }

    /** 向维度内所有玩家推送一次全量风暴快照，并记录推送时间。 */
    public static void broadcast(ServerLevel level) {
        StormSyncPayload payload = payload(level);
        for (ServerPlayer player : level.players()) {
            player.connection.send(payload);
        }
        LAST_SYNC.put(level, level.getGameTime());
    }

    /** 给单个玩家补发一次快照（登录 / 跨维度）。 */
    public static void sendTo(ServerPlayer player) {
        player.connection.send(payload(player.serverLevel()));
    }

    /** 玩家退出时清掉 HUD 缓存，重连后能重新收到完整状态。 */
    public static void forget(UUID playerId) {
        LAST_HUD.remove(playerId);
    }

    private static StormSyncPayload payload(ServerLevel level) {
        List<StormSyncPayload.StormEntry> entries = new ArrayList<>();
        for (StormObject storm : data(level).storms()) {
            StormProfile profile = profile(level, storm.profileId());
            if (profile == null) {
                continue;
            }
            entries.add(new StormSyncPayload.StormEntry(storm.id(), storm.x(), storm.z(), storm.radiusAt(),
                    storm.velocityX, storm.velocityZ, storm.minY(), storm.maxY(),
                    storm.weatherType(profile), storm.baseRain(), storm.baseThunder(),
                    storm.manual(), storm.dying(), storm.dyingTicksLeft));
        }
        return new StormSyncPayload(entries,
                WindField.stringConfig(Config.ISOLATED_BIOME_WEATHER, "clear"),
                WindField.doubleConfig(Config.STORM_FALLOFF_RATIO, 0.3D));
    }

    public static StormProfile profile(ServerLevel level, net.minecraft.resources.ResourceLocation profileId) {
        return profileId == null ? null
                : StormProfiles.byId(level, profileId).map(Holder::value).orElse(null);
    }

    public static StormObject find(ServerLevel level, UUID id) {
        for (StormObject storm : data(level).storms()) {
            if (storm.id().equals(id)) {
                return storm;
            }
        }
        return null;
    }

    /** 包内使用：直接拿可变对象。 */
    public static List<StormObject> all(ServerLevel level) {
        return List.copyOf(data(level).storms());
    }

    /** 当前维度里的手动风暴数量（命令/API 的手动上限预判用）。 */
    public static int manualStormCount(ServerLevel level) {
        return (int) data(level).storms().stream().filter(StormObject::manual).count();
    }

    /** 手动风暴到上限只警告一次，避免刷日志。 */
    private static void warnManualCap(int cap) {
        if (!warnedManualCap) {
            warnedManualCap = true;
            Localizedrain.LOGGER.warn(
                    "[localizedrain] manual storm limit reached ({}), ignoring new manual storms", cap);
        }
    }

    public static StormSnapshot snapshotOf(ServerLevel level, StormObject storm) {
        StormProfile profile = profile(level, storm.profileId());
        return profile == null ? null : storm.snapshot(profile);
    }

    /** 对外查询：按 id 取快照，不存在返回 null。 */
    public static StormSnapshot snapshotById(ServerLevel level, UUID id) {
        StormObject storm = find(level, id);
        return storm == null ? null : snapshotOf(level, storm);
    }

    /** 对外使用：不可变快照列表（StormObject 是包私有类型）。 */
    public static List<StormSnapshot> snapshots(ServerLevel level) {
        List<StormSnapshot> result = new ArrayList<>();
        for (StormObject storm : data(level).storms()) {
            StormSnapshot snapshot = snapshotOf(level, storm);
            if (snapshot != null) {
                result.add(snapshot);
            }
        }
        return result;
    }

    /** 按快照谓词批量移除，返回移除数量；默认按手动移除处理。 */
    public static int removeWhere(ServerLevel level, java.util.function.Predicate<StormSnapshot> filter) {
        return removeWhere(level, filter, StormRemoveEvent.Reason.MANUAL, true);
    }

    /** 统一移除入口：逐条发事件；批量移除时可以只广播一次，避免一次删多个就推多次。 */
    private static int removeWhere(ServerLevel level, java.util.function.Predicate<StormSnapshot> filter,
                                   StormRemoveEvent.Reason reason, boolean broadcast) {
        List<UUID> removedIds = new ArrayList<>();
        Iterator<StormObject> iterator = data(level).storms().iterator();
        while (iterator.hasNext()) {
            StormObject storm = iterator.next();
            StormSnapshot snapshot = snapshotOf(level, storm);
            if (snapshot != null && filter.test(snapshot)) {
                iterator.remove();
                removedIds.add(storm.id());
            }
        }
        if (removedIds.isEmpty()) {
            return 0;
        }
        data(level).setDirty();
        for (UUID id : removedIds) {
            NeoForge.EVENT_BUS.post(new StormRemoveEvent(level, id, reason));
        }
        if (broadcast) {
            broadcast(level);
        }
        return removedIds.size();
    }

    public static WindState wind(ServerLevel level) {
        return data(level).wind().state();
    }

    /** 每 tick 由 WeatherManager 在 MOBILE 模式下调用（阶段 1 的 tickMobile 方法体）。 */
    public static void tick(ServerLevel level) {
        StormData data = data(level);
        DOMINANT_CACHE.remove(level);       // 每 tick 重新计算天气覆盖，避免跨 tick 复用
        data.wind().tick(level);
        boolean dirtyForSync = false;
        boolean removedAny = false;
        Iterator<StormObject> iterator = data.storms().iterator();
        while (iterator.hasNext()) {
            StormObject storm = iterator.next();
            StormProfile profile = profile(level, storm.profileId());
            if (profile == null) {
                // 档案被数据包移除：丢弃该风暴并记一次日志（设计 §10）
                Localizedrain.LOGGER.warn("[localizedrain] dropping storm {} with unknown profile {}",
                        storm.id(), storm.profileId());
                iterator.remove();
                removedAny = true;
                NeoForge.EVENT_BUS.post(new StormRemoveEvent(level, storm.id(),
                        StormRemoveEvent.Reason.MISSING_PROFILE));
                continue;
            }
            if (!storm.dying()) {
                if (!storm.manual()) {
                    // 自然风暴远离所有玩家后先倒计时，再进入淡出，避免玩家一走开就立刻消失
                    double despawnDistance = WindField.intConfig(Config.STORM_DESPAWN_DISTANCE, 1000);
                    if (distanceToNearestPlayer(level, storm) > despawnDistance) {
                        storm.farTicks++;
                        if (storm.farTicks >= WindField.intConfig(Config.STORM_DESPAWN_GRACE_TICKS, 600)) {
                            storm.startDying();
                            storm.farDespawn = true;
                        }
                    } else {
                        storm.farTicks = 0;
                    }
                }
                StormEvolution.tick(level, storm, profile);
                if (StormEvolution.shouldDie(storm, profile)) {
                    storm.startDying();
                } else if (StormEvolution.shouldAdvance(storm, profile)
                        && storm.stageIndex() + 1 < profile.stages().size()) {
                    storm.enterStage(profile, storm.stageIndex() + 1, profile.stage(storm.stageIndex()));
                }
            }
            storm.tick(profile, data.wind(), StormEvolution.speedFactor(storm, profile));
            if (storm.lastTransitionFrom >= 0) {
                // 阶段切换会改变天气类型/强度，需要立即推送而不是等周期校正
                int from = storm.lastTransitionFrom;
                StormSnapshot after = snapshotOf(level, storm);
                if (after != null) {
                    NeoForge.EVENT_BUS.post(new StormStageChangeEvent(level, after, from));
                }
                storm.lastTransitionFrom = -1;
                dirtyForSync = true;
            }
            if (storm.dying() && storm.dyingTicksLeft <= 0) {
                iterator.remove();
                removedAny = true;
                NeoForge.EVENT_BUS.post(new StormRemoveEvent(level, storm.id(),
                        storm.farDespawn ? StormRemoveEvent.Reason.FAR_AWAY : StormRemoveEvent.Reason.FINISHED));
            }
        }
        // 生成/移除/换阶段立即推送；没有变化时按配置间隔做一次全量校正
        int interval = WindField.intConfig(Config.STORM_SYNC_INTERVAL_TICKS, 40);
        long lastSync = LAST_SYNC.getOrDefault(level, 0L);
        if (dirtyForSync || removedAny || level.getGameTime() - lastSync >= interval) {
            broadcast(level);
        }
        // 维度级雨/雷量取所有风暴的最大值，供原版刷怪/天空亮度等维度级判定使用
        float maxRain = 0.0F;
        float maxThunder = 0.0F;
        for (StormObject storm : data.storms()) {
            maxRain = Math.max(maxRain, storm.effectiveRain());
            maxThunder = Math.max(maxThunder, storm.effectiveThunder());
        }
        level.setRainLevel(maxRain);
        level.setThunderLevel(maxThunder);
        spawnLightning(level);
        sendHud(level);
        StormSpawner.tick(level);
        data.setDirty();
    }

    /** 风暴中心到最近玩家的水平距离；维度内没有玩家时返回最大值。 */
    private static double distanceToNearestPlayer(ServerLevel level, StormObject storm) {
        double nearest = Double.MAX_VALUE;
        for (ServerPlayer player : level.players()) {
            nearest = Math.min(nearest, Math.hypot(player.getX() - storm.x(), player.getZ() - storm.z()));
        }
        return nearest;
    }

    /** 把「玩家被覆盖的主导风暴」折算成 HUD 目标值，只在变化时发包（复用 WeatherStatePayload）。 */
    private static void sendHud(ServerLevel level) {
        for (ServerPlayer player : level.players()) {
            StormObject storm = dominantAt(level, player.blockPosition());
            WeatherStatePayload payload;
            if (storm == null) {
                payload = new WeatherStatePayload(false, false, WeatherTypes.CLEAR.get().displayKey());
            } else {
                StormProfile profile = profile(level, storm.profileId());
                WeatherType type = profile == null ? WeatherTypes.CLEAR.get()
                        : WeatherTypes.byId(level, storm.weatherType(profile));
                payload = new WeatherStatePayload(
                        storm.effectiveRain() > 0.05F,
                        storm.effectiveThunder() > 0.05F,
                        type.displayKey());
            }
            if (!payload.equals(LAST_HUD.get(player.getUUID()))) {
                LAST_HUD.put(player.getUUID(), payload);
                player.connection.send(payload);
            }
        }
    }

    /** 生成一个风暴；档案不存在时返回 null（不抛异常，调用方负责提示）。 */
    public static StormSnapshot spawn(ServerLevel level, StormSpawnSpec spec) {
        StormProfile profile = profile(level, spec.profileId());
        if (profile == null) {
            return null;
        }
        if (spec.manual()) {
            int cap = WindField.intConfig(Config.MAX_MANUAL_STORMS_PER_DIMENSION, 16);
            if (manualStormCount(level) >= cap) {
                warnManualCap(cap);
                return null;
            }
        }
        RandomSource random = level.getRandom();
        double radius = spec.radius().orElseGet(() -> {
            StormProfile.Range range = profile.radius();
            return range.min() + random.nextDouble() * (range.max() - range.min());
        });
        StormProfile.VerticalMode vertical = profile.vertical();
        double minY;
        double maxY;
        if (spec.verticalBand().isPresent()) {
            double[] band = spec.verticalBand().get();
            minY = band[0];
            maxY = band[1];
            vertical = StormProfile.VerticalMode.BAND;
        } else if (vertical == StormProfile.VerticalMode.SURFACE) {
            BlockPos center = BlockPos.containing(spec.center());
            minY = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, center).getY() - 16.0D;
            maxY = level.getMaxBuildHeight();
        } else {
            minY = level.getMinBuildHeight();
            maxY = level.getMaxBuildHeight();
        }
        int stageIndex = Math.min(spec.stage(), profile.stages().size() - 1);
        StormProfile.Stage stage = profile.stage(stageIndex);
        StormObject storm = StormObject.create(UUID.randomUUID(), spec.profileId(),
                spec.center().x, spec.center().z, radius, minY, maxY, vertical,
                spec.manual(), random.nextLong(), level.getGameTime());
        storm.stageIndex = stageIndex;
        storm.stageScale = stage.radiusScale();
        storm.radiusExplicit = spec.radius().isPresent();
        storm.stageTicksTotal = storm.stageTicksLeft =
                spec.lifetimeTicks() > 0 ? spec.lifetimeTicks() : StormObject.pickTicks(storm.seed, stage);
        storm.lifeTicksLeft = spec.lifetimeTicks();
        storm.rainIntensity = spec.rainIntensity().orElse(stage.rain().lerp(0.0F));
        storm.thunderIntensity = spec.thunderIntensity().orElse(stage.thunder().lerp(0.0F));
        spec.velocity().ifPresent(velocity -> {
            storm.velocityX = velocity[0];
            storm.velocityZ = velocity[1];
            storm.pinnedVelocity = true;
        });
        if (spec.manual() && spec.velocity().isEmpty()) {
            // 手动风暴默认固定：命令/API 没给速度时不应被风场吹走
            storm.velocityX = 0.0D;
            storm.velocityZ = 0.0D;
            storm.pinnedVelocity = true;
        }
        spec.typeOverride().ifPresent(type -> storm.typeOverride = type);
        StormSnapshot snapshot = storm.snapshot(profile);
        // 取消即拒绝生成：不入列、不推送
        if (NeoForge.EVENT_BUS.post(new StormSpawnEvent(level, snapshot)).isCanceled()) {
            return null;
        }
        data(level).storms().add(storm);
        data(level).setDirty();
        // 新风暴立即推送，客户端不必等下一次周期校正
        broadcast(level);
        return snapshot;
    }

    public static boolean remove(ServerLevel level, UUID id) {
        boolean removed = data(level).storms().removeIf(storm -> storm.id().equals(id));
        if (removed) {
            data(level).setDirty();
            NeoForge.EVENT_BUS.post(new StormRemoveEvent(level, id, StormRemoveEvent.Reason.MANUAL));
            // 移除后立即推送一次，客户端才能马上把该风暴丢掉
            broadcast(level);
        }
        return removed;
    }

    /** 原版跳过夜晚：清掉全部自然风暴，手动风暴保留（设计 §8 resetWeatherCycle）。 */
    public static void clearNatural(ServerLevel level) {
        removeWhere(level, snapshot -> !snapshot.manual(), StormRemoveEvent.Reason.CLEARED, true);
    }

    /** 推进到下一阶段；已是最后阶段则进入淡出。 */
    public static boolean advanceStage(ServerLevel level, UUID id) {
        StormObject storm = find(level, id);
        StormProfile profile = storm == null ? null : profile(level, storm.profileId());
        if (storm == null || profile == null) {
            return false;
        }
        if (storm.stageIndex() + 1 < profile.stages().size()) {
            storm.enterStage(profile, storm.stageIndex() + 1, profile.stage(storm.stageIndex()));
        } else {
            storm.startDying();
        }
        afterStageWrite(level, storm);
        return true;
    }

    /** 跳到指定阶段（自动夹到合法区间）。 */
    public static boolean setStage(ServerLevel level, UUID id, int index) {
        StormObject storm = find(level, id);
        StormProfile profile = storm == null ? null : profile(level, storm.profileId());
        if (storm == null || profile == null) {
            return false;
        }
        int target = Math.max(0, Math.min(index, profile.stages().size() - 1));
        storm.enterStage(profile, target, profile.stage(storm.stageIndex()));
        afterStageWrite(level, storm);
        return true;
    }

    /** 阶段被 API 直接改写后：立即发事件并复位标志，避免下一 tick 重复发布。 */
    private static void afterStageWrite(ServerLevel level, StormObject storm) {
        if (storm.lastTransitionFrom >= 0) {
            int from = storm.lastTransitionFrom;
            StormSnapshot after = snapshotOf(level, storm);
            if (after != null) {
                NeoForge.EVENT_BUS.post(new StormStageChangeEvent(level, after, from));
            }
            storm.lastTransitionFrom = -1;
        }
        data(level).setDirty();
        broadcast(level);
    }

    /** 固定风暴速度（不再被风场覆盖）。 */
    public static boolean setVelocity(ServerLevel level, UUID id, double x, double z) {
        StormObject storm = find(level, id);
        if (storm == null) {
            return false;
        }
        storm.velocityX = x;
        storm.velocityZ = z;
        storm.pinnedVelocity = true;
        data(level).setDirty();
        broadcast(level);
        return true;
    }

    /** 覆盖风暴雨/雷强度；下次阶段切换时恢复档案值。 */
    public static boolean setIntensity(ServerLevel level, UUID id, float rain, float thunder) {
        StormObject storm = find(level, id);
        if (storm == null) {
            return false;
        }
        storm.rainOverride = Mth.clamp(rain, 0.0F, 1.0F);
        storm.thunderOverride = Mth.clamp(thunder, 0.0F, 1.0F);
        data(level).setDirty();
        broadcast(level);
        return true;
    }

    /** 设置维度风场速度。 */
    public static void setWind(ServerLevel level, double velocityX, double velocityZ) {
        data(level).wind().set(velocityX, velocityZ);
        data(level).setDirty();
    }

    /** 该位置的群系是否在 #localizedrain:storm_isolated 内。 */
    public static boolean isIsolatedAt(ServerLevel level, BlockPos pos) {
        return level.getBiome(pos).is(StormTags.STORM_ISOLATED);
    }

    /** 覆盖该位置、强度最高的风暴；没有返回 null。结果按 4 格立方在本 tick 内复用。 */
    private static StormObject dominantAt(ServerLevel level, BlockPos pos) {
        Map<Long, StormObject> cache = DOMINANT_CACHE.computeIfAbsent(level, ignored -> new HashMap<>());
        long key = quartKey(pos);
        if (cache.containsKey(key)) {
            return cache.get(key);
        }
        StormObject result = computeDominant(level, pos);
        if (cache.size() >= DOMINANT_CACHE_LIMIT) {
            cache.clear();
        }
        cache.put(key, result);
        return result;
    }

    /** 4 方块立方坐标打包：复用 BlockPos 的 26/12/26 位布局，覆盖世界边界（不再受 21 位限制）。 */
    private static long quartKey(BlockPos pos) {
        return BlockPos.asLong(pos.getX() >> 2, pos.getY() >> 2, pos.getZ() >> 2);
    }

    private static StormObject computeDominant(ServerLevel level, BlockPos pos) {
        boolean manualOnly = false;
        if (isIsolatedAt(level, pos)) {
            // clear：绝对无天气；patch：由片天气机器接管；manual：只认手动风暴
            String isolatedMode = WindField.stringConfig(Config.ISOLATED_BIOME_WEATHER, "clear");
            if (!"manual".equals(isolatedMode)) {
                return null;
            }
            manualOnly = true;
        }
        double px = pos.getX() + 0.5D;
        double py = pos.getY() + 0.5D;
        double pz = pos.getZ() + 0.5D;
        StormObject best = null;
        float bestIntensity = 0.0F;
        for (StormObject storm : data(level).storms()) {
            if (manualOnly && !storm.manual()) {
                continue;
            }
            if (!storm.covers(px, py, pz)) {
                continue;
            }
            float intensity = Math.max(storm.effectiveRain(), storm.effectiveThunder());
            if (intensity > bestIntensity) {
                bestIntensity = intensity;
                best = storm;
            }
        }
        return best;
    }

    public static boolean isRainingAt(ServerLevel level, BlockPos pos) {
        StormObject storm = dominantAt(level, pos);
        if (storm == null) {
            return false;
        }
        StormProfile profile = profile(level, storm.profileId());
        if (profile == null || storm.effectiveRain() <= 0.05F) {
            return false;
        }
        WeatherType type = WeatherTypes.byId(level, storm.weatherType(profile));
        return type.hasPrecipitation()
                && type.precipitation() == Biome.Precipitation.RAIN
                && canPrecipitate(level, pos, storm);
    }

    public static boolean isPrecipitatingAt(ServerLevel level, BlockPos pos) {
        StormObject storm = dominantAt(level, pos);
        if (storm == null) {
            return false;
        }
        StormProfile profile = profile(level, storm.profileId());
        if (profile == null || storm.effectiveRain() <= 0.05F) {
            return false;
        }
        return WeatherTypes.byId(level, storm.weatherType(profile)).hasPrecipitation();
    }

    public static boolean isThunderingAt(ServerLevel level, BlockPos pos) {
        StormObject storm = dominantAt(level, pos);
        return storm != null && thundering(level, storm) && canPrecipitate(level, pos, storm);
    }

    public static boolean isThunderingRegionAt(ServerLevel level, BlockPos pos) {
        StormObject storm = dominantAt(level, pos);
        return storm != null && thundering(level, storm);
    }

    public static float thunderLevelAt(ServerLevel level, BlockPos pos) {
        StormObject storm = dominantAt(level, pos);
        return storm == null ? 0.0F : Mth.clamp(storm.effectiveThunder(), 0.0F, 1.0F);
    }

    public static int skyDarkenAt(ServerLevel level, BlockPos pos) {
        StormObject storm = dominantAt(level, pos);
        return WeatherManager.skyDarkenFor(level,
                storm == null ? 0.0F : storm.effectiveRain(),
                storm == null ? 0.0F : storm.effectiveThunder());
    }

    public static WeatherType weatherTypeAt(ServerLevel level, BlockPos pos) {
        StormObject storm = dominantAt(level, pos);
        if (storm == null) {
            return WeatherTypes.CLEAR.get();
        }
        StormProfile profile = profile(level, storm.profileId());
        return profile == null ? WeatherTypes.CLEAR.get()
                : WeatherTypes.byId(level, storm.weatherType(profile));
    }

    private static boolean thundering(ServerLevel level, StormObject storm) {
        StormProfile profile = profile(level, storm.profileId());
        return profile != null
                && storm.effectiveThunder() > 0.05F
                && WeatherTypes.byId(level, storm.weatherType(profile)).allowsThunder();
    }

    /** SURFACE 要求见天；BAND / COLUMN 跳过见天判定（洞穴风暴的核心）。 */
    private static boolean canPrecipitate(ServerLevel level, BlockPos pos, StormObject storm) {
        if (storm.verticalMode() != StormProfile.VerticalMode.SURFACE) {
            return true;
        }
        return level.canSeeSky(pos)
                && level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, pos).getY() <= pos.getY()
                && level.getBiome(pos).value().getPrecipitationAt(pos) == Biome.Precipitation.RAIN;
    }

    /** 原版 /weather：按每位玩家生成或清除手动风暴（设计 §11.5）。 */
    public static void setAllFromVanilla(ServerLevel level, boolean raining, boolean thundering, int duration) {
        if (!raining) {
            // 移动模式下的 /weather clear：清空该维度全部风暴（自然 + 手动），只广播一次（设计 §8）
            removeWhere(level, snapshot -> true, StormRemoveEvent.Reason.CLEARED, true);
            return;
        }
        int radius = WindField.intConfig(Config.VANILLA_COMMAND_STORM_RADIUS, 512);
        ResourceLocation typeId = thundering ? WeatherTypes.THUNDER_ID : WeatherTypes.RAIN_ID;
        for (ServerPlayer player : level.players()) {
            spawnManual(level, player.blockPosition(), typeId, radius,
                    duration <= 0 ? -1 : duration);
        }
    }

    /** 在指定位置生成单阶段手动风暴（setWeatherAt / 原版 /weather 共用）。 */
    public static StormSnapshot spawnManual(ServerLevel level, BlockPos pos, ResourceLocation typeId,
                                            int radius, int durationTicks) {
        if (WeatherTypes.CLEAR_ID.equals(typeId)) {
            // clear 的语义是「把覆盖该位置的风暴清掉」，而不是生成一个晴天风暴
            removeWhere(level, snapshot -> snapshot.covers(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D));
            return null;
        }
        int cap = WindField.intConfig(Config.MAX_MANUAL_STORMS_PER_DIMENSION, 16);
        if (manualStormCount(level) >= cap) {
            warnManualCap(cap);
            return null;
        }
        ResourceLocation profileId = StormProfiles.ids(level).stream()
                .filter(id -> profileMatches(level, id, typeId))
                .findFirst()
                .orElse(null);
        if (profileId == null) {
            return null;
        }
        // 同一位置同类型的手动风暴先替换掉，重复 /weather rain 不会越堆越多
        double px = pos.getX() + 0.5D;
        double py = pos.getY() + 0.5D;
        double pz = pos.getZ() + 0.5D;
        int replaced = removeWhere(level, snapshot -> snapshot.manual()
                && snapshot.weatherType().equals(typeId)
                && snapshot.covers(px, py, pz), StormRemoveEvent.Reason.MANUAL, false);
        StormSpawnSpec.Builder builder = StormSpawnSpec.builder(profileId, Vec3.atCenterOf(pos))
                .radius(radius)
                .typeOverride(typeId)
                .velocity(0.0D, 0.0D)       // 指令/API 定位的风暴默认固定，不被风场吹走
                .manual(true);
        if (durationTicks > 0) {
            builder.lifetime(durationTicks);
        }
        StormSnapshot snapshot = spawn(level, builder.build());
        if (snapshot == null && replaced > 0) {
            broadcast(level);               // 旧风暴已删但新风暴没生成，也要让客户端立刻丢掉旧的
        }
        return snapshot;
    }

    private static boolean profileMatches(ServerLevel level, ResourceLocation profileId, ResourceLocation typeId) {
        StormProfile profile = profile(level, profileId);
        return profile != null && profile.stage(0).type().equals(typeId);
    }

    /** 按风暴雷强抽签落雷；每次闪电都落在覆盖玩家的风暴内部。 */
    private static void spawnLightning(ServerLevel level) {
        double multiplier = WindField.doubleConfig(Config.STORM_LIGHTNING_MULTIPLIER, 1.0D);
        if (multiplier <= 0.0D) {
            return;
        }
        RandomSource random = level.getRandom();
        int view = level.getServer().getPlayerList().getViewDistance();
        int side = 2 * view + 1;
        double baseChance = Math.min(1.0D, (double) (side * side) / WeatherManager.LIGHTNING_ROLL_BASE);
        for (StormObject storm : data(level).storms()) {
            if (storm.effectiveThunder() <= 0.05F) {
                continue;
            }
            List<ServerPlayer> covered = new ArrayList<>();
            for (ServerPlayer player : level.players()) {
                if (storm.covers(player.getX(), player.getY(), player.getZ())) {
                    covered.add(player);
                }
            }
            if (covered.isEmpty()) {
                continue;                       // 每片风暴每 tick 只掷一次，闪电密度不随玩家数放大
            }
            if (random.nextDouble() >= Math.min(1.0D, baseChance * multiplier * storm.effectiveThunder())) {
                continue;
            }
            // 落点跟随一名被覆盖玩家：风暴盘可能远大于视距，均匀取点会让绝大多数闪电被 hasChunk 丢弃
            ServerPlayer anchor = covered.get(random.nextInt(covered.size()));
            double strikeRange = Math.min(storm.radiusAt(), 128.0D);
            double angle = random.nextDouble() * Math.PI * 2.0;
            double distance = Math.sqrt(random.nextDouble()) * strikeRange;
            double x = anchor.getX() + Math.cos(angle) * distance;
            double z = anchor.getZ() + Math.sin(angle) * distance;
            int minY = (int) Math.max(storm.minY(), level.getMinBuildHeight());
            int maxY = (int) Math.min(storm.maxY(), level.getMaxBuildHeight() - 1);
            if (maxY < minY) {
                continue;
            }
            if (!level.getChunkSource().hasChunk((int) Math.floor(x) >> 4, (int) Math.floor(z) >> 4)) {
                continue;                   // 不为了落雷强制加载区块
            }
            BlockPos pos;
            if (storm.verticalMode() == StormProfile.VerticalMode.SURFACE) {
                pos = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING,
                        new BlockPos((int) Math.floor(x), minY, (int) Math.floor(z)));
                if (pos.getY() > maxY || !isThunderingAt(level, pos)) {
                    continue;
                }
            } else {
                // 洞穴/柱状风暴：在风暴区间内直接取点，落点必须有空间（不再借高度图，否则必然被顶部挡掉）
                int y = minY + random.nextInt(maxY - minY + 1);
                BlockPos candidate = new BlockPos((int) Math.floor(x), y, (int) Math.floor(z));
                if (!level.getBlockState(candidate).isAir()) {
                    candidate = candidate.above();
                }
                if (!level.getBlockState(candidate).isAir()
                        || !storm.covers(candidate.getX() + 0.5D, candidate.getY() + 0.5D, candidate.getZ() + 0.5D)
                        || !isThunderingAt(level, candidate)) {
                    continue;
                }
                pos = candidate;
            }
            LightningBolt bolt = EntityType.LIGHTNING_BOLT.create(level);
            if (bolt != null) {
                bolt.moveTo(Vec3.atBottomCenterOf(pos));
                level.addFreshEntity(bolt);
            }
        }
    }
}
