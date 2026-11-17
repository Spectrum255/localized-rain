package com.spectrum255.localizedrain.storm;

import com.spectrum255.localizedrain.Config;
import com.spectrum255.localizedrain.Localizedrain;
import com.spectrum255.localizedrain.api.StormProfile;
import com.spectrum255.localizedrain.api.StormProfiles;
import com.spectrum255.localizedrain.api.StormTags;
import com.spectrum255.localizedrain.api.StormSnapshot;
import com.spectrum255.localizedrain.api.StormSpawnSpec;
import com.spectrum255.localizedrain.api.WeatherMode;
import com.spectrum255.localizedrain.api.WeatherType;
import com.spectrum255.localizedrain.api.WeatherTypes;
import com.spectrum255.localizedrain.api.WindState;
import com.spectrum255.localizedrain.api.event.StormRemoveEvent;
import com.spectrum255.localizedrain.api.event.StormSpawnEvent;
import com.spectrum255.localizedrain.api.event.StormStageChangeEvent;
import com.spectrum255.localizedrain.weather.WeatherLightning;
import com.spectrum255.localizedrain.weather.WeatherBiomeFilter;
import com.spectrum255.localizedrain.weather.WeatherManager;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
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
    private static final Map<UUID, StormSyncData.Hud> LAST_HUD = new HashMap<>();
    /** 同一 tick 内按 4 格立方复用 dominantAt 结果；tick 开头清空。 */
    private static final Map<ServerLevel, Map<Long, StormObject>> DOMINANT_CACHE = new WeakHashMap<>();
    private static final int DOMINANT_CACHE_LIMIT = 65536;
    /** 未显式给 band 的 BAND 档案默认带半高（以生成点为中心）。 */
    private static final int BAND_HALF_HEIGHT = 48;
    /** 手动风暴达到配置上限后只警告一次。 */
    private static boolean warnedManualCap;
    /** 网络层启动时安装的发包实现；领域层不直接构造网络包。 */
    private static final StormSyncSink NOOP_SINK = new StormSyncSink() {
        @Override
        public void broadcast(ServerLevel level, StormSyncData data) {}

        @Override
        public void sendTo(ServerPlayer player, StormSyncData data) {}

        @Override
        public void sendHud(ServerPlayer player, StormSyncData.Hud hud) {}
    };
    private static volatile StormSyncSink syncSink = NOOP_SINK;

    private StormManager() {}

    public static void setSyncSink(StormSyncSink sink) {
        syncSink = sink == null ? NOOP_SINK : sink;
    }

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

    /**
     * 数据包重载后按新的天气类型定义刷新已有风暴的能力位（同一 ID 修改 JSON 也会生效）。
     * 返回处理过的风暴数。
     */
    public static int refreshCapabilities(ServerLevel level) {
        int changed = 0;
        for (StormObject storm : data(level).storms()) {
            StormProfile profile = storm.resolveProfile(level);
            if (profile == null) {
                continue;
            }
            ResourceLocation typeId = storm.weatherType(profile);
            storm.capabilityType = typeId;
            storm.applyCapabilities(WeatherTypes.resolve(level, typeId));
            changed++;
        }
        return changed;
    }

    /** 数据包重载后清空每片风暴缓存的档案引用，避免继续使用旧注册表对象。 */
    public static void invalidateProfiles() {
        for (StormData data : CACHE.values()) {
            for (StormObject storm : data.storms()) {
                storm.clearProfileCache();
            }
        }
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
        syncSink.broadcast(level, syncData(level));
        LAST_SYNC.put(level, level.getGameTime());
    }

    /** 给单个玩家补发一次快照（登录 / 跨维度）。 */
    public static void sendTo(ServerPlayer player) {
        syncSink.sendTo(player, syncData(player.serverLevel()));
    }

    /** 天气模式变化后立刻向所有维度重推一次，让客户端知道风暴系统是否仍接管天气。 */
    public static void refreshAll(net.minecraft.server.MinecraftServer server) {
        for (ServerLevel level : server.getAllLevels()) {
            broadcast(level);
        }
    }

    /** 玩家退出时清掉 HUD 缓存，重连后能重新收到完整状态。 */
    public static void forget(UUID playerId) {
        LAST_HUD.remove(playerId);
    }

    /** 领域对象的只读投影；由安装的 {@link StormSyncSink} 编码并发送。 */
    private static StormSyncData syncData(ServerLevel level) {
        List<StormSyncData.Entry> entries = new ArrayList<>();
        for (StormObject storm : data(level).storms()) {
            StormProfile profile = storm.resolveProfile(level);
            if (profile == null) {
                continue;
            }
            WeatherType type = WeatherTypes.byId(level, storm.weatherType(profile));
            entries.add(new StormSyncData.Entry(storm.id(), storm.x(), storm.z(), storm.radiusAt(),
                    storm.velocityX, storm.velocityZ, storm.minY(), storm.maxY(),
                    storm.weatherType(profile), type.hasPrecipitation(), type.allowsThunder(),
                    storm.baseRain(), storm.baseThunder(),
                    storm.manual(), storm.dying(), storm.dyingTicksLeft));
        }
        return new StormSyncData(entries,
                WindField.stringConfig(Config.ISOLATED_BIOME_WEATHER, "clear"),
                WindField.doubleConfig(Config.STORM_FALLOFF_RATIO, 0.3D),
                com.spectrum255.localizedrain.api.WeatherMode.current()
                        == com.spectrum255.localizedrain.api.WeatherMode.MOBILE);
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
        StormProfile profile = storm.resolveProfile(level);
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

    /** 清空该维度全部风暴（自然 + 手动），返回移除数量；只广播一次。 */
    public static int clearAllStorms(ServerLevel level) {
        return removeWhere(level, snapshot -> true, StormRemoveEvent.Reason.CLEARED, true);
    }

    /** 只按水平距离清除风暴，不检查 Y 区间。
     * 命令按区域清除时使用：玩家站在高空也能清掉脚下的地表风暴。
     */
    public static int clearAtColumn(ServerLevel level, BlockPos pos, double radius) {
        double px = pos.getX() + 0.5D;
        double pz = pos.getZ() + 0.5D;
        double reach = Math.max(0.0D, radius);
        return removeWhere(level, snapshot -> {
            double dx = snapshot.x() - px;
            double dz = snapshot.z() - pz;
            double limit = snapshot.radius() + reach;
            return dx * dx + dz * dz <= limit * limit;
        }, StormRemoveEvent.Reason.CLEARED, true);
    }

    public static WindState wind(ServerLevel level) {
        return data(level).wind().state();
    }

    /** 每 tick 由 WeatherManager 在 MOBILE 模式下调用（阶段 1 的 tickMobile 方法体）。 */
    public static void tick(ServerLevel level) {
        // 防御：非 MOBILE 模式下不应推进风暴。若配置在运行期被改回 PATCHES，
        // 这里提前返回，避免 StormSpawner 之外的其他入口在本模式下产生自然风暴。
        // 已有风暴留在 StormData 中，切回 MOBILE 后会继续推进。
        if (WeatherMode.current() != WeatherMode.MOBILE) {
            return;
        }
        StormData data = data(level);
        DOMINANT_CACHE.remove(level);       // 每 tick 重新计算天气覆盖，避免跨 tick 复用
        data.wind().tick(level);
        boolean dirtyForSync = false;
        boolean removedAny = false;
        Iterator<StormObject> iterator = data.storms().iterator();
        while (iterator.hasNext()) {
            StormObject storm = iterator.next();
            StormProfile profile = storm.resolveProfile(level);
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
            // 类型变化（阶段推进 / 手动覆盖 / 读档后）时刷新能力位，保证雷量不会在「雨天」里残留
            ResourceLocation effectiveType = storm.weatherType(profile);
            if (!effectiveType.equals(storm.capabilityType)) {
                storm.capabilityType = effectiveType;
                storm.applyCapabilities(WeatherTypes.resolve(level, effectiveType));
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
        // 不再把风暴最大值写回维度级 rainLevel/thunderLevel：避免 Level#isRaining() 带动第三方全维度逻辑。
        // 客户端风暴渲染由 StormSyncPayload + ClientStormWeather 负责；第三方应改用 WeatherApi。
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
            StormSyncData.Hud hud;
            if (storm == null) {
                hud = new StormSyncData.Hud(false, false, WeatherTypes.byId(level, WeatherTypes.CLEAR_ID).displayKey());
            } else {
                StormProfile profile = storm.resolveProfile(level);
                WeatherType type = profile == null ? WeatherTypes.byId(level, WeatherTypes.CLEAR_ID)
                        : WeatherTypes.byId(level, storm.weatherType(profile));
                hud = new StormSyncData.Hud(
                        type.hasPrecipitation() && storm.effectiveRain() > 0.2F,
                        type.allowsThunder() && storm.effectiveThunder() > 0.9F,
                        type.displayKey());
            }
            if (!hud.equals(LAST_HUD.get(player.getUUID()))) {
                LAST_HUD.put(player.getUUID(), hud);
                syncSink.sendHud(player, hud);
            }
        }
    }

    /** 生成一个风暴；档案不存在时返回 null（不抛异常，调用方负责提示）。 */
    public static StormSnapshot spawn(ServerLevel level, StormSpawnSpec spec) {
        StormProfile profile = profile(level, spec.profileId());
        if (profile == null) {
            return null;
        }
        boolean surfaceOnly = spec.verticalBand().isEmpty()
                && profile.vertical() == StormProfile.VerticalMode.SURFACE;
        if (spec.manual() && surfaceOnly && !allowsManualStorms(level)) {
            // 无天光 / 有天花板的维度渲染不出 SURFACE 雨雪；BAND/COLUMN 洞穴风暴仍然允许
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
            double base = WindField.doubleConfig(Config.STORM_RADIUS_BASE, 0.0D);
            if (base > 0.0D) {
                double variance = Math.max(0.0D, Math.min(1.0D,
                        WindField.doubleConfig(Config.STORM_RADIUS_VARIANCE, 0.3D)));
                double factor = 1.0D + (random.nextDouble() * 2.0D - 1.0D) * variance;
                return Math.max(16.0D, base * factor);
            }
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
        } else if (vertical == StormProfile.VerticalMode.BAND) {
            // BAND 档案未显式指定区间时，以生成点为中心给一个默认带，而不是退化成整列
            int centerY = Mth.floor(spec.center().y);
            minY = Math.max(level.getMinBuildHeight(), centerY - BAND_HALF_HEIGHT);
            maxY = Math.min(level.getMaxBuildHeight(), centerY + BAND_HALF_HEIGHT);
        } else {
            // COLUMN：整列（洞穴/柱状风暴）
            minY = level.getMinBuildHeight();
            maxY = level.getMaxBuildHeight();
        }
        int stageIndex = Math.min(spec.stage(), profile.stages().size() - 1);
        StormProfile.Stage stage = profile.stage(stageIndex);
        ResourceLocation effectiveType = spec.typeOverride().orElse(stage.type());
        if (!allowsManualStorms(level) && vertical != StormProfile.VerticalMode.SURFACE
                && (WeatherTypes.byId(level, effectiveType).allowsThunder() || profile.hasThunderStage(level))) {
            // 无天光 / 有天花板的维度里洞穴风暴永不落雷：直接拒绝雷暴类型（命令与 API 一致）
            return null;
        }
        StormObject storm = StormObject.create(UUID.randomUUID(), spec.profileId(),
                spec.center().x, spec.center().z, radius, minY, maxY, vertical,
                spec.manual(), random.nextLong(), level.getGameTime());
        storm.stageIndex = stageIndex;
        storm.stageScale = stage.radiusScale();
        storm.radiusExplicit = spec.radius().isPresent();
        storm.stageTicksTotal = storm.stageTicksLeft =
                spec.lifetimeTicks() > 0 ? spec.lifetimeTicks() : StormObject.pickTicks(storm.seed, stage);
        storm.lifeTicksLeft = spec.lifetimeTicks();
        WeatherType resolvedType = WeatherTypes.resolve(level, effectiveType);
        // 立刻用阶段强度初始化，否则生成后第一次 broadcast 的强度仍是 0
        storm.rainIntensity = spec.rainIntensity().orElse(stage.rain().lerp(0.0F));
        storm.thunderIntensity = spec.thunderIntensity().orElse(stage.thunder().lerp(0.0F));
        // typeOverride 只改类型：能力位存进风暴，阶段推进与强度覆盖时都会再次钳制
        storm.capabilityType = effectiveType;
        storm.applyCapabilities(resolvedType);
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
        if (logStormSpawns()) {
            Localizedrain.LOGGER.info(
                    "[localizedrain] storm spawned dim={} id={} profile={} x={} z={} radius={} vertical={} minY={} maxY={} stage={}/{} type={} rain={} thunder={} velocity={},{} manual={} lifetimeTicks={} stageTicks={}",
                    level.dimension().location(), storm.id, spec.profileId(), storm.x, storm.z, storm.radius,
                    vertical, storm.minY, storm.maxY, storm.stageIndex, profile.stages().size(),
                    storm.weatherType(profile), storm.baseRain(), storm.baseThunder(),
                    storm.velocityX, storm.velocityZ, storm.manual, storm.lifeTicksLeft, storm.stageTicksLeft);
        }
        // 新风暴立即推送，客户端不必等下一次周期校正
        broadcast(level);
        reportSpawnToChat(level, spec.profileId(), storm.x, storm.z, storm.minY, storm.maxY, storm.radius,
                storm.weatherType(profile));
        return snapshot;
    }

    /** 调试开关：风暴生成时是否输出详细参数；配置未加载时默认开（本模组自身的诊断信息）。 */
    private static boolean logStormSpawns() {
        try {
            return Config.LOG_STORM_SPAWNS.get();
        } catch (IllegalStateException ignored) {
            return true;
        }
    }

    /** 调试：把风暴生成信息发到该维度所有玩家的聊天栏（中心坐标与类型）。 */
    private static void reportSpawnToChat(ServerLevel level, ResourceLocation profileId,
                                          double x, double z, double minY, double maxY, double radius,
                                          ResourceLocation typeId) {
        boolean enabled;
        try {
            enabled = Config.LOG_STORM_SPAWNS_TO_CHAT.get();
        } catch (IllegalStateException ignored) {
            return;
        }
        if (!enabled) {
            return;
        }
        Component message = Component.translatable("message.localizedrain.storm.spawn_report",
                profileId.toString(),
                String.format(Locale.ROOT, "%.1f", x),
                String.format(Locale.ROOT, "%.1f", z),
                String.format(Locale.ROOT, "%.0f", minY),
                String.format(Locale.ROOT, "%.0f", maxY),
                String.format(Locale.ROOT, "%.1f", radius),
                typeId.toString());
        for (ServerPlayer player : level.players()) {
            player.sendSystemMessage(message);
        }
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
        StormProfile profile = storm == null ? null : storm.resolveProfile(level);
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
        StormProfile profile = storm == null ? null : storm.resolveProfile(level);
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
            // 非降水自定义天气的强度恒为 0，但仍然是覆盖该位置的有效风暴。
            if (best == null || intensity > bestIntensity) {
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
        StormProfile profile = storm.resolveProfile(level);
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
        StormProfile profile = storm.resolveProfile(level);
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
            return WeatherTypes.byId(level, WeatherTypes.CLEAR_ID);
        }
        StormProfile profile = storm.resolveProfile(level);
        return profile == null ? WeatherTypes.byId(level, WeatherTypes.CLEAR_ID)
                : WeatherTypes.byId(level, storm.weatherType(profile));
    }

    private static boolean thundering(ServerLevel level, StormObject storm) {
        StormProfile profile = storm.resolveProfile(level);
        return profile != null
                && storm.effectiveThunder() > 0.05F
                && WeatherTypes.byId(level, storm.weatherType(profile)).allowsThunder();
    }

    /** 只约束 SURFACE 手动风暴：无天光/带天花板的维度不允许 SURFACE 雨雪，但 BAND/COLUMN 洞穴风暴仍可。 */
    public static boolean allowsManualStorms(ServerLevel level) {
        return level.dimensionType().hasSkyLight() && !level.dimensionType().hasCeiling();
    }

    /** 是否有风暴正在降水（供天气模式分派与睡觉清天气使用）。 */
    public static boolean anyPrecipitation(ServerLevel level) {
        for (StormObject storm : data(level).storms()) {
            if (storm.effectiveRain() > 0.2F) {
                return true;
            }
        }
        return false;
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
            clearAllStorms(level);
            return;
        }
        ResourceLocation typeId = thundering ? WeatherTypes.THUNDER_ID : WeatherTypes.RAIN_ID;
        boolean surfaceVisible = allowsManualStorms(level);
        // 匹配任意阶段：thunderstorm 的 thunder 在 stage 1，只比对 stage 0 会让 /weather thunder 静默失效
        ProfileMatch match = findProfile(level, typeId, !surfaceVisible);
        if (match == null) {
            String key = !surfaceVisible
                    ? (thundering ? "commands.localizedrain.storm.thunder_unavailable"
                    : "commands.localizedrain.storm.no_skylight_blocked")
                    : "commands.localizedrain.storm.no_profile";
            Component message = Component.translatable(key);
            for (ServerPlayer player : level.players()) {
                player.sendSystemMessage(message);
            }
            return;
        }
        // 半径交给档案自己的区间（cave_rain 是 48–96）；0 表示「不覆盖」
        int radius = 0;
        int players = level.players().size();
        if (players == 0) {
            return;
        }
        int spawned = 0;
        int skipped = 0;
        for (ServerPlayer player : level.players()) {
            // 与模组 /weather 的移动模式入口保持一致：只有强制晴天会拦下手动设置
            if (WeatherBiomeFilter.manualBlockedInMobile(level, player.blockPosition())) {
                skipped++;
                continue;
            }
            if (spawnManual(level, player.blockPosition(), typeId, match, radius,
                    duration <= 0 ? -1 : duration) != null) {
                spawned++;
            }
        }
        int candidates = players - skipped;
        if (spawned < candidates) {
            // 手动上限截断：告诉玩家是「部分生效」，避免被当成命令失效
            Component message = Component.translatable(
                    "commands.localizedrain.storm.partial_spawned", spawned, candidates);
            for (ServerPlayer player : level.players()) {
                player.sendSystemMessage(message);
            }
        }
        if (skipped > 0) {
            Component message = Component.translatable(
                    "commands.localizedrain.storm.players_skipped", skipped);
            for (ServerPlayer player : level.players()) {
                player.sendSystemMessage(message);
            }
        }
    }

    /** 在指定位置生成单阶段手动风暴（setWeatherAt / 原版 /weather 共用）。 */
    public static StormSnapshot spawnManual(ServerLevel level, BlockPos pos, ResourceLocation typeId,
                                            int radius, int durationTicks) {
        ProfileMatch match = findProfile(level, typeId, !allowsManualStorms(level));
        if (match == null) {
            return null;
        }
        return spawnManual(level, pos, typeId, match, radius, durationTicks);
    }

    /** 与公开重载相同，但由调用方指定档案；无天光维度会传入 BAND/COLUMN 档案。 */
    private static StormSnapshot spawnManual(ServerLevel level, BlockPos pos, ResourceLocation typeId,
                                             ProfileMatch match, int radius, int durationTicks) {
        if (WeatherTypes.CLEAR_ID.equals(typeId)) {
            // clear 的语义是「把覆盖该位置的风暴清掉」，而不是生成一个晴天风暴；
            // 与命令一致按列判断，避免高空清除地表风暴失败
            clearAtColumn(level, pos, 0.0D);
            return null;
        }
        int cap = WindField.intConfig(Config.MAX_MANUAL_STORMS_PER_DIMENSION, 16);
        if (manualStormCount(level) >= cap) {
            warnManualCap(cap);
            return null;
        }
        // 同一位置同类型的手动风暴先替换掉，重复 /weather rain 不会越堆越多
        double px = pos.getX() + 0.5D;
        double py = pos.getY() + 0.5D;
        double pz = pos.getZ() + 0.5D;
        int replaced = removeWhere(level, snapshot -> snapshot.manual()
                && snapshot.weatherType().equals(typeId)
                && snapshot.covers(px, py, pz), StormRemoveEvent.Reason.MANUAL, false);
        StormSpawnSpec.Builder builder = StormSpawnSpec.builder(match.id(), Vec3.atCenterOf(pos))
                .stage(match.stageIndex())
                .typeOverride(typeId)
                .velocity(0.0D, 0.0D)       // 指令/API 定位的风暴默认固定，不被风场吹走
                .manual(true);
        if (radius > 0) {
            builder.radius(radius);         // 0 = 沿用档案的 radius 区间
        }
        if (durationTicks > 0) {
            builder.lifetime(durationTicks);
        }
        StormSnapshot snapshot = spawn(level, builder.build());
        if (snapshot == null && replaced > 0) {
            broadcast(level);               // 旧风暴已删但新风暴没生成，也要让客户端立刻丢掉旧的
        }
        return snapshot;
    }

    /** 档案 + 匹配的起始阶段（例如 thunderstorm 的 thunder 在 stage 1）。 */
    private record ProfileMatch(ResourceLocation id, int stageIndex, int weight) {
    }

    /**
     * 按天气类型挑一个匹配档案（匹配任意阶段，而不是只看 stage 0）：
     * <ul>
     *   <li>有天空的维度：优先 {@code natural = true} 且 SURFACE 的档案；没有自然档案时回退到
     *       非自然 SURFACE，避免数据包把所有档案设为 natural=false 后 /weather rain 静默失效；</li>
     *   <li>无天光 / 有天花板的维度：只接受 BAND/COLUMN 档案（不受 natural 限制，
     *       cave_rain 本身就是 natural=false 的手动档案）；</li>
     *   <li>候选按 id 排序后按 weight 加权随机：手动指令也会出现不同强度的天气，而不是恒取最重档案。</li>
     * </ul>
     */
    private static ProfileMatch findProfile(ServerLevel level, ResourceLocation typeId, boolean nonSurface) {
        List<ResourceLocation> ids = new ArrayList<>(StormProfiles.ids(level));
        ids.sort(Comparator.comparing(ResourceLocation::toString));
        List<ProfileMatch> natural = new ArrayList<>();
        List<ProfileMatch> fallback = new ArrayList<>();
        for (ResourceLocation id : ids) {
            StormProfile profile = profile(level, id);
            if (profile == null) {
                continue;
            }
            int stageIndex = -1;
            for (int index = 0; index < profile.stages().size(); index++) {
                if (profile.stage(index).type().equals(typeId)) {
                    stageIndex = index;
                    break;
                }
            }
            if (stageIndex < 0) {
                continue;
            }
            ProfileMatch match = new ProfileMatch(id, stageIndex, Math.max(1, profile.weight()));
            if (nonSurface) {
                if (profile.vertical() != StormProfile.VerticalMode.SURFACE) {
                    natural.add(match);
                }
            } else if (profile.vertical() == StormProfile.VerticalMode.SURFACE) {
                if (profile.natural()) {
                    natural.add(match);
                } else {
                    fallback.add(match);
                }
            }
        }
        List<ProfileMatch> pool = natural.isEmpty() ? fallback : natural;
        if (pool.isEmpty()) {
            return null;
        }
        int total = 0;
        for (ProfileMatch match : pool) {
            total += match.weight();
        }
        int roll = level.getRandom().nextInt(total);
        for (ProfileMatch match : pool) {
            roll -= match.weight();
            if (roll < 0) {
                return match;
            }
        }
        return pool.get(pool.size() - 1);
    }

    /**
     * 按风暴雷强抽签落雷：先掷骰（便宜），命中后才搜索落点，闪电落在覆盖玩家的风暴内部。
     * 命中后的搜索分两层：外层最多重掷 3 轮，每轮 {@link #findStrikePos} 内最多取点 4 次；
     * 文档里的 {@code chance × (1 - (1 - p)^4)} 只描述内层，实际有效期望略高于它，但每片风暴每 tick 仍只生成 1 道闪电。
     */
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
            int coveredCount = 0;
            for (ServerPlayer player : level.players()) {
                if (storm.covers(player.getX(), player.getY(), player.getZ())) {
                    coveredCount++;
                }
            }
            if (coveredCount == 0) {
                continue;                       // 每片风暴每 tick 只抽一次签，闪电密度不随玩家数放大
            }
            double chance = Math.min(1.0D, baseChance * multiplier * storm.effectiveThunder());
            // 先用一次便宜的掷骰筛掉约 99.6% 的 tick，命中后才做位置搜索；
            // 搜索失败最多重试 3 轮，常态成本接近 0，期望落雷率也不会被无效落点吞掉太多。
            BlockPos pos = null;
            for (int attempt = 0; attempt < 3 && pos == null; attempt++) {
                if (random.nextDouble() >= chance) {
                    break;
                }
                // 两遍遍历取第 n 个被覆盖玩家，避免每 tick 为每片风暴分配列表。
                ServerPlayer anchor = pickCoveredPlayer(level, storm, random.nextInt(coveredCount));
                if (anchor == null) {
                    break;
                }
                pos = findStrikePos(level, storm, anchor, random);
            }
            if (pos == null) {
                continue;
            }
            // 与原版 tickChunk 相同的陷阱概率；抽到骷髅马时闪电只做视觉效果
            WeatherLightning.strike(level, pos, random);
        }
    }

    /** 取第 index 个被风暴完整覆盖（含 Y 区间）的玩家；用计数而不是中间列表，避免每 tick 分配。 */
    private static ServerPlayer pickCoveredPlayer(ServerLevel level, StormObject storm, int index) {
        int remaining = index;
        for (ServerPlayer player : level.players()) {
            if (storm.covers(player.getX(), player.getY(), player.getZ()) && remaining-- == 0) {
                return player;
            }
        }
        return null;
    }

    /**
     * 在锚点玩家附近找一个能落雷的位置：最多 4 次尝试，先做区块/高度/空气/雷暴校验，全部无效返回 null。
     * 只在掷骰命中后才调用，锚点站在风暴边缘时失败由调用方有界重试。
     */
    private static BlockPos findStrikePos(ServerLevel level, StormObject storm, ServerPlayer anchor, RandomSource random) {
        int minY = (int) Math.max(storm.minY(), level.getMinBuildHeight());
        int maxY = (int) Math.min(storm.maxY(), level.getMaxBuildHeight() - 1);
        if (maxY < minY) {
            return null;
        }
        double strikeRange = Math.min(storm.radiusAt(), 128.0D);
        for (int attempt = 0; attempt < 4; attempt++) {
            double angle = random.nextDouble() * Math.PI * 2.0;
            double distance = Math.sqrt(random.nextDouble()) * strikeRange;
            int blockX = (int) Math.floor(anchor.getX() + Math.cos(angle) * distance);
            int blockZ = (int) Math.floor(anchor.getZ() + Math.sin(angle) * distance);
            if (!level.getChunkSource().hasChunk(blockX >> 4, blockZ >> 4)) {
                continue;                   // 不为了落雷强制加载区块
            }
            if (storm.verticalMode() == StormProfile.VerticalMode.SURFACE) {
                BlockPos pos = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, new BlockPos(blockX, minY, blockZ));
                if (pos.getY() >= minY && pos.getY() <= maxY && isThunderingAt(level, pos)) {
                    return pos;
                }
            } else {
                // 洞穴/柱状风暴：在风暴区间内直接取点，落点必须有空间（不再借高度图，否则必然被顶部挡掉）
                int y = minY + random.nextInt(maxY - minY + 1);
                BlockPos candidate = new BlockPos(blockX, y, blockZ);
                if (!level.getBlockState(candidate).isAir()) {
                    candidate = candidate.above();
                }
                if (level.getBlockState(candidate).isAir()
                        && level.canSeeSky(candidate)   // 只落在正上方有开口的点，封闭洞穴不落雷
                        && storm.covers(candidate.getX() + 0.5D, candidate.getY() + 0.5D, candidate.getZ() + 0.5D)
                        && isThunderingAt(level, candidate)) {
                    return candidate;
                }
            }
        }
        return null;
    }
}
