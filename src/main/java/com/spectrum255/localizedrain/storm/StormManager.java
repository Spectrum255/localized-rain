package com.spectrum255.localizedrain.storm;

import com.spectrum255.localizedrain.Config;
import com.spectrum255.localizedrain.Localizedrain;
import com.spectrum255.localizedrain.api.IsolatedBiomeWeather;
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
import com.spectrum255.localizedrain.api.event.StormDyingEvent;
import com.spectrum255.localizedrain.api.event.StormSpawnEvent;
import com.spectrum255.localizedrain.api.event.StormStageChangeEvent;
import com.spectrum255.localizedrain.weather.WeatherLightning;
import com.spectrum255.localizedrain.weather.WeatherBiomeFilter;
import com.spectrum255.localizedrain.weather.WeatherEffects;
import com.spectrum255.localizedrain.weather.WeatherLayer;
import com.spectrum255.localizedrain.weather.WeatherManager;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
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
    /** 上一次广播的隔离群系模式；配置热重载后据此立即重推快照，而不是等一个同步周期。 */
    private static String lastIsolatedMode;
    /** 同一 tick 内按 4 格立方复用 dominantAt 结果；tick 开头清空。 */
    private static final Map<ServerLevel, Map<Long, StormObject>> DOMINANT_CACHE = new WeakHashMap<>();
    /** 同一 tick 内按 4 格立方复用天气层列表（移动模式叠加）；tick 开头清空。 */
    private static final Map<ServerLevel, Map<Long, List<WeatherLayer>>> LAYER_CACHE = new WeakHashMap<>();
    /** #标签展开结果，按数据包重载/配置变化失效。 */
    private static final Map<ResourceLocation, Set<ResourceLocation>> TAG_CACHE = new HashMap<>();
    private static String compatRulesSource = "\u0000";
    private static List<CompatRule> compatRules = List.of();
    private static String blockedRulesSource = "\u0000";
    private static List<CompatRule> blockedRules = List.of();
    private static final int DOMINANT_CACHE_LIMIT = 65536;
    /** 未显式给 band 的 BAND 档案默认带半高（以生成点为中心）。 */
    private static final int BAND_HALF_HEIGHT = 48;
    /** 手动风暴达到配置上限后只警告一次。 */
    private static boolean warnedManualCap;
    /** 存档版本比程序新（只读）时无法落盘新风暴，只警告一次。 */
    private static boolean warnedReadOnly;
    /** 类型被数据包移除的风暴：按「维度 + 类型 id」只告警一次，避免每 tick 刷日志。 */
    private static final Set<String> WARNED_MISSING_TYPE = ConcurrentHashMap.newKeySet();
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
        LAYER_CACHE.clear();
        TAG_CACHE.clear();
        compatRulesSource = "\u0000";
        compatRules = List.of();
        blockedRulesSource = "\u0000";
        blockedRules = List.of();
        warnedManualCap = false;
        warnedReadOnly = false;
        WARNED_MISSING_TYPE.clear();
        lastIsolatedMode = null;
    }

    /** 配置重载后复位「只警告一次」的标志，让新配置下的问题仍能被记录。 */
    public static void resetWarnings() {
        warnedManualCap = false;
        warnedReadOnly = false;
        WARNED_MISSING_TYPE.clear();
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

    /** 玩家退出时清掉 HUD 缓存与效果记录，重连后能重新收到完整状态。 */
    public static void forget(UUID playerId) {
        LAST_HUD.remove(playerId);
        WeatherEffects.forget(playerId);
    }

    /** 领域对象的只读投影；由安装的 {@link StormSyncSink} 编码并发送。 */
    private static StormSyncData syncData(ServerLevel level) {
        List<StormSyncData.Entry> entries = new ArrayList<>();
        for (StormObject storm : data(level).storms()) {
            StormProfile profile = storm.resolveProfile(level);
            if (profile == null) {
                continue;
            }
            WeatherType type = WeatherTypes.resolve(level, storm.weatherType(profile));
            if (type == null) {
                // 类型已被数据包热移除：不要把原始 id 发给客户端，否则客户端会继续按旧缓存渲染。
                // 风暴本身保留在存档里（类型恢复后自会复活），但要留下可定位的日志，并让生成上限忽略它。
                warnMissingType(level, storm.weatherType(profile));
                continue;
            }
            entries.add(new StormSyncData.Entry(storm.id(), storm.x(), storm.z(), storm.radiusAt(),
                    storm.velocityX, storm.velocityZ, storm.minY(), storm.maxY(),
                    storm.weatherType(profile), type.hasPrecipitation(), type.allowsThunder(),
                    storm.baseRain(), storm.baseThunder(),
                    storm.manual(), storm.dying(), storm.dyingTicksLeft));
        }
        return new StormSyncData(entries,
                Config.isolatedBiomeWeather().id(),
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

    /** 档案缺失时的降级快照：只保证几何、手动标记与类型可用于清理谓词（不对外保证完整）。 */
    private static StormSnapshot fallbackSnapshot(StormObject storm) {
        ResourceLocation type = storm.capabilityType != null ? storm.capabilityType
                : storm.typeOverride != null ? storm.typeOverride : WeatherTypes.CLEAR_ID;
        return new StormSnapshot(storm.id(), storm.x(), storm.z(), storm.radiusAt(),
                storm.minY(), storm.maxY(), storm.profileId(), type,
                storm.stageIndex(), 0,
                storm.baseRain(), storm.baseThunder(), storm.velocityX, storm.velocityZ,
                storm.temperature, storm.moisture, 0L, storm.manual(), storm.dying());
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
            if (snapshot == null) {
                // 档案被数据包删除时 snapshotOf 返回 null；这类风暴无法被清理谓词匹配，
                // 会永远留在存档里。用风暴自身字段构造降级快照，让 /weather clear 等入口仍能删掉它。
                snapshot = fallbackSnapshot(storm);
            }
            if (filter.test(snapshot)) {
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
            // 切回片模式/原版后风暴不再推进：把移动模式留下的移速/伤害效果收掉
            for (ServerPlayer player : level.players()) {
                WeatherEffects.clear(player);
            }
            return;
        }
        StormData data = data(level);
        DOMINANT_CACHE.remove(level);       // 每 tick 重新计算天气覆盖，避免跨 tick 复用
        LAYER_CACHE.remove(level);
        TAG_CACHE.clear();                  // 标签内容可能随 /reload 改变，每 tick 重新展开一次
        data.wind().tick(level);
        boolean dirtyForSync = false;
        boolean removedAny = false;
        // 事件在循环结束后统一派发：监听器可能 spawn/removeWhere，遍历中派发会让下一次 next() 抛 CME
        List<StormRemoveEvent> removeEvents = new ArrayList<>(2);
        List<StormStageChangeEvent> stageEvents = new ArrayList<>(2);
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
                removeEvents.add(new StormRemoveEvent(level, storm.id(),
                        StormRemoveEvent.Reason.MISSING_PROFILE));
                continue;
            }
            // 类型变化（阶段推进 / 手动覆盖 / 读档后）时刷新能力位，保证雷量不会在「雨天」里残留
            refreshCapabilities(level, storm, profile);
            // 地形/群系交互：按采样间隔刷新雨量、雷量与速度系数
            StormTerrain.update(level, storm);
            if (!storm.dying()) {
                if (!storm.manual()) {
                    // 自然风暴远离所有玩家后先倒计时，再进入淡出，避免玩家一走开就立刻消失
                    double despawnDistance = WindField.intConfig(Config.STORM_DESPAWN_DISTANCE, 1000);
                    if (distanceToNearestPlayer(level, storm) > despawnDistance) {
                        storm.farTicks++;
                        if (storm.farTicks >= WindField.intConfig(Config.STORM_DESPAWN_GRACE_TICKS, 600)) {
                            if (requestDying(level, storm, profile, StormDyingEvent.Cause.FAR_AWAY)) {
                                storm.farDespawn = true;
                            }
                        }
                    } else {
                        storm.farTicks = 0;
                    }
                }
                // 本 tick 刚进入淡出的风暴不再演化：否则可能在同一 tick 里再切阶段并多发一个事件
                if (!storm.dying()) {
                    StormEvolution.tick(level, storm, profile);
                    if (StormEvolution.shouldDie(storm, profile)) {
                        requestDying(level, storm, profile, StormDyingEvent.Cause.EVOLUTION);
                    } else if (StormEvolution.shouldAdvance(storm, profile)) {
                        StormEvolution.Next next = StormEvolution.nextStage(level, storm, profile);
                        if (next.stage() < 0) {
                            requestDying(level, storm, profile, StormDyingEvent.Cause.STAGE_END);
                        } else {
                            storm.enterStage(profile, next.stage(), profile.stage(storm.stageIndex()), next.type());
                            // 同一 tick 内切阶段后立刻刷新能力位：快照/事件/推送都会用到新阶段的能力位
                            refreshCapabilities(level, storm, profile);
                        }
                    }
                }
            }
            StormEvolution.Next next = StormEvolution.nextStage(level, storm, profile);
            storm.tick(profile, data.wind(), StormEvolution.speedFactor(storm, profile), next);
            if (storm.pendingDying) {
                StormDyingEvent.Cause cause = storm.pendingDyingCause == null
                        ? StormDyingEvent.Cause.LIFETIME : storm.pendingDyingCause;
                storm.pendingDyingCause = null;
                requestDying(level, storm, profile, cause);
            }
            if (storm.lastTransitionFrom >= 0) {
                // 阶段切换会改变天气类型/强度，需要立即推送而不是等周期校正
                int from = storm.lastTransitionFrom;
                StormSnapshot after = snapshotOf(level, storm);
                if (after != null) {
                    stageEvents.add(new StormStageChangeEvent(level, after, from));
                }
                storm.lastTransitionFrom = -1;
                dirtyForSync = true;
            }
            if (storm.dying() && storm.dyingTicksLeft <= 0) {
                iterator.remove();
                removedAny = true;
                removeEvents.add(new StormRemoveEvent(level, storm.id(),
                        storm.farDespawn ? StormRemoveEvent.Reason.FAR_AWAY : StormRemoveEvent.Reason.FINISHED));
            }
        }
        for (StormRemoveEvent event : removeEvents) {
            NeoForge.EVENT_BUS.post(event);
        }
        for (StormStageChangeEvent event : stageEvents) {
            NeoForge.EVENT_BUS.post(event);
        }
        // 合并/分裂在遍历结束后统一处理，避免边遍历边改列表；有变化时立即广播
        boolean mergedOrSplit = StormMerger.tick(level);
        // isolatedBiomeWeather 热重载：客户端只从快照里读 isolatedMode，改配置后立刻重推，
        // 否则客户端会按旧规则（例如 clear 每 tick 清零片雨量）再跑一个同步周期。
        String isolatedMode = Config.isolatedBiomeWeather().id();
        if (!isolatedMode.equals(lastIsolatedMode)) {
            lastIsolatedMode = isolatedMode;
            refreshAll(level.getServer());
            resetHudCache();
        }
        // 生成/移除/换阶段立即推送；没有变化时按配置间隔做一次全量校正
        int interval = WindField.intConfig(Config.STORM_SYNC_INTERVAL_TICKS, 40);
        long lastSync = LAST_SYNC.getOrDefault(level, 0L);
        if (dirtyForSync || removedAny || mergedOrSplit || level.getGameTime() - lastSync >= interval) {
            broadcast(level);
        }
        // 不再把风暴最大值写回维度级 rainLevel/thunderLevel：避免 Level#isRaining() 带动第三方全维度逻辑。
        // 客户端风暴渲染由 StormSyncPayload + ClientStormWeather 负责；第三方应改用 WeatherApi。
        spawnLightning(level);
        sendHud(level);
        applyPlayerEffects(level);
        StormSpawner.tick(level);
        data.setDirty();
    }

    /** 类型变化时刷新能力位（降水 / 雷电），快照、HUD 与客户端渲染都以能力位为准。 */
    private static void refreshCapabilities(ServerLevel level, StormObject storm, StormProfile profile) {
        ResourceLocation effectiveType = storm.weatherType(profile);
        if (effectiveType.equals(storm.capabilityType)) {
            return;
        }
        storm.capabilityType = effectiveType;
        storm.applyCapabilities(WeatherTypes.resolve(level, effectiveType));
    }

    /**
     * 在风暴真正进入淡出前发 {@link StormDyingEvent}。取消时给 20 tick 免疫期，
     * 避免同一个风暴因为「寿命/阶段/远离」条件仍然成立而每 tick 重复触发事件。
     *
     * @return 是否真的开始淡出
     */
    private static boolean requestDying(ServerLevel level, StormObject storm, StormProfile profile,
                                        StormDyingEvent.Cause cause) {
        storm.pendingDying = false;
        long now = level.getGameTime();
        if (now < storm.dyingBlockedUntil) {
            return false;
        }
        StormSnapshot snapshot = snapshotOf(level, storm);
        if (snapshot != null && NeoForge.EVENT_BUS.post(new StormDyingEvent(level, snapshot, cause)).isCanceled()) {
            storm.dyingBlockedUntil = now + 20L;
            return false;
        }
        storm.startDying();
        return true;
    }

    /**
     * 移动模式：把玩家所在「主导风暴」的类型 {@code effects} 应用到玩家，
     * 与片模式共用 {@link WeatherEffects}，离开风暴后恢复移速并清空伤害计时。
     */
    private static void applyPlayerEffects(ServerLevel level) {
        for (ServerPlayer player : level.players()) {
            List<WeatherLayer> layers = layersAt(level, player.blockPosition());
            if (layers.isEmpty()) {
                // 移动模式下 isolatedBiomeWeather=patch 时，片机器每 tick 先给隔离片玩家施加效果，
                // 这里再无条件 clear 会把它们抹掉（移速/周期伤害永久失效）；片上有活跃天气时交给片逻辑
                if (!WeatherManager.isPatchWeatherActiveAt(level, player.blockPosition())) {
                    WeatherEffects.clear(player);
                }
                continue;
            }
            WeatherEffects.applyLayers(player, level, layers);
        }
    }

    /** 风暴中心到最近玩家的水平距离；维度内没有玩家时返回最大值。 */
    private static double distanceToNearestPlayer(ServerLevel level, StormObject storm) {
        double nearest = Double.MAX_VALUE;
        for (ServerPlayer player : level.players()) {
            nearest = Math.min(nearest, Math.hypot(player.getX() - storm.x(), player.getZ() - storm.z()));
        }
        return nearest;
    }

    /** 隔离群系 + patch：该玩家的天气与 HUD 由片通道负责，移动模式不要写同一个客户端字段。 */
    private static boolean patchOwnsHud(ServerLevel level, ServerPlayer player) {
        // 先判配置：默认模式下不必每 tick 为每个玩家做一次群系查询
        return Config.isolatedBiomeWeather() == IsolatedBiomeWeather.PATCH
                && level.getBiome(player.blockPosition()).is(StormTags.STORM_ISOLATED);
    }

    /** 与客户端阈值保持一致：低于该值时客户端不认为在下雨/打雷。 */
    private static final float HUD_RAIN_THRESHOLD = 0.2F;
    private static final float HUD_THUNDER_THRESHOLD = 0.9F;

    /** 把玩家位置得到的天气层折算成 HUD 目标值：类型、布尔与每层强度都来自同一份层列表。 */
    private static StormSyncData.Hud hudFromLayers(List<WeatherLayer> layers) {
        if (layers.isEmpty()) {
            return new StormSyncData.Hud(false, false, "", "", 0.0F, List.of());
        }
        List<StormSyncData.HudLayer> hudLayers = new ArrayList<>(layers.size());
        boolean raining = false;
        boolean thundering = false;
        for (WeatherLayer layer : layers) {
            boolean builtin = WeatherTypes.isBuiltin(layer.typeId());
            hudLayers.add(new StormSyncData.HudLayer(
                    builtin ? "" : layer.type().displayKey(),
                    layer.typeId().toString(),
                    layer.intensity()));
            raining |= layer.rain() > HUD_RAIN_THRESHOLD;
            thundering |= layer.thunder() > HUD_THUNDER_THRESHOLD;
        }
        WeatherLayer dominant = layers.get(0);
        boolean dominantBuiltin = WeatherTypes.isBuiltin(dominant.typeId());
        return new StormSyncData.Hud(raining, thundering,
                dominantBuiltin ? "" : dominant.type().displayKey(),
                dominant.typeId().toString(),
                dominant.intensity(), List.copyOf(hudLayers));
    }

    /**
     * 只在 HUD 目标值真正变化时发包：布尔与类型变化立即发送，强度用差值阈值。
     *
     * <p>{@code Hud} 是精确 {@code equals}；移动模式下强度含玩家与风暴的实时距离，
     * 逐 tick 都用精确比较会形成「每 tick 一条状态包」，进而每 tick 重置客户端环境音计时。</p>
     */
    private static boolean hudChanged(StormSyncData.Hud previous, StormSyncData.Hud next) {
        if (previous == null) {
            return true;
        }
        if (previous.raining() != next.raining()
                || previous.thundering() != next.thundering()
                || !previous.typeKey().equals(next.typeKey())
                || !previous.typeId().equals(next.typeId())) {
            return true;
        }
        if (previous.layers().size() != next.layers().size()) {
            return true;
        }
        for (int index = 0; index < previous.layers().size(); index++) {
            StormSyncData.HudLayer before = previous.layers().get(index);
            StormSyncData.HudLayer after = next.layers().get(index);
            if (!before.typeId().equals(after.typeId()) || !before.typeKey().equals(after.typeKey())) {
                return true;
            }
            if (Math.abs(before.intensity() - after.intensity()) > 0.02F) {
                return true;
            }
        }
        return Math.abs(previous.intensity() - next.intensity()) > 0.02F;
    }

    /** 天气模式切换后清空 HUD 去重缓存，保证客户端能收到新模式下的一条完整 HUD。 */
    public static void resetHudCache() {
        LAST_HUD.clear();
    }

    /** 把「玩家所在位置的天气层」折算成 HUD 目标值，只在变化时发包（复用 WeatherStatePayload）。 */
    private static void sendHud(ServerLevel level) {
        for (ServerPlayer player : level.players()) {
            // 隔离片 + patch：该玩家的天气通道完全由片机器负责。必须在 layersAt 之前判断：
            // 隔离片在 patch 模式下没有任何层，走到下面会发出「晴天」HUD，
            // 覆盖 tickMobile 里片通道刚发过的 WeatherStatePayload（片天气稳定时不会再重发）。
            if (patchOwnsHud(level, player)) {
                LAST_HUD.remove(player.getUUID());
                continue;
            }
            StormSyncData.Hud hud = hudFromLayers(layersAt(level, player.blockPosition()));
            if (hudChanged(LAST_HUD.get(player.getUUID()), hud)) {
                LAST_HUD.put(player.getUUID(), hud);
                syncSink.sendHud(player, hud);
            }
        }
    }

    /** 生成一个风暴；档案不存在时返回 null（不抛异常，调用方负责提示）。 */
    public static StormSnapshot spawn(ServerLevel level, StormSpawnSpec spec) {
        return spawn(level, spec, false);
    }

    /**
     * @param capAlreadyApplied 调用方已按「替换」语义算过手动风暴名额（{@link #spawnManual}）。
     *                         自动替换时新风暴必须先落地、再删旧风暴，否则生成事件被取消会连旧风暴一起丢掉。
     */
    private static StormSnapshot spawn(ServerLevel level, StormSpawnSpec spec, boolean capAlreadyApplied) {
        if (data(level).readOnly()) {
            // 存档版本比程序新：save 会原样写回旧 NBT，新风暴永远不会落盘，生成只会造成"看起来有、重启就没"的假象
            if (!warnedReadOnly) {
                warnedReadOnly = true;
                com.spectrum255.localizedrain.Localizedrain.LOGGER.warn(
                        "[localizedrain] storm data was written by a newer version; "
                                + "spawning is disabled for this world until the version matches");
            }
            return null;
        }
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
        if (spec.manual() && !capAlreadyApplied) {
            int cap = WindField.intConfig(Config.MAX_MANUAL_STORMS_PER_DIMENSION, 16);
            if (manualStormCount(level) >= cap) {
                warnManualCap(cap);
                return null;
            }
        }
        RandomSource random = level.getRandom();
        // 半径必须在入口处夹紧：/weather storm area 用「半对角线+64」计算，超大坐标会得到上千万的半径
        double radius = Mth.clamp(spec.radius().orElseGet(() -> {
            double base = WindField.doubleConfig(Config.STORM_RADIUS_BASE, 0.0D);
            if (base > 0.0D) {
                double variance = Math.max(0.0D, Math.min(1.0D,
                        WindField.doubleConfig(Config.STORM_RADIUS_VARIANCE, 0.3D)));
                double factor = 1.0D + (random.nextDouble() * 2.0D - 1.0D) * variance;
                return Math.max(16.0D, base * factor);
            }
            StormProfile.Range range = profile.radius();
            return range.min() + random.nextDouble() * (range.max() - range.min());
        }), 16.0D, 4096.0D);
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
        // 公开 API 可能传入负数或越界阶段：与 load() 一样双向钳制，否则快照/地图会显示负阶段
        int stageIndex = Mth.clamp(spec.stage(), 0, Math.max(0, profile.stages().size() - 1));
        StormProfile.Stage stage = profile.stage(stageIndex);
        ResourceLocation effectiveType = spec.typeOverride().orElse(stage.type());
        // 未知类型会退化成晴天：与其生成一个“看不见”的风暴，不如直接拒绝并说明原因
        WeatherType resolvedType = WeatherTypes.resolve(level, effectiveType);
        if (resolvedType == null) {
            Localizedrain.LOGGER.warn("[localizedrain] storm profile {} uses unregistered weather type {}; refusing to spawn",
                    spec.profileId(), effectiveType);
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
        // 立刻用阶段强度初始化，否则生成后第一次 broadcast 的强度仍是 0
        // 初始强度取阶段区间下限：新风暴从「平静」开始，再由湿度演化逐渐增强/减弱。
        // 这里有意用 lerp(0.0F)（而不是随机起始值），避免风暴生成瞬间就满强度。
        storm.rainIntensity = stage.rain().lerp(0.0F);
        storm.thunderIntensity = stage.thunder().lerp(0.0F);
        // 生成参数里显式指定的强度用 override 承载：StormObject.tick 每 tick 都会用阶段插值
        // 覆写 rainIntensity/thunderIntensity，直接写字段只能影响首帧（H3）。
        spec.rainIntensity().ifPresent(value -> storm.rainOverride = Mth.clamp(value, 0.0F, 1.0F));
        spec.thunderIntensity().ifPresent(value -> storm.thunderOverride = Mth.clamp(value, 0.0F, 1.0F));
        // typeOverride 只改类型：能力位存进风暴，阶段推进与强度覆盖时都会再次钳制
        storm.capabilityType = effectiveType;
        storm.applyCapabilities(resolvedType);
        spec.velocity().ifPresent(velocity -> {
            // 固定速度是 R18 的旁路：这里必须与 setVelocity 同样钳制，否则 1e9 的固定速度
            // 会让手动风暴永远回收不掉，NaN/Inf 还会污染存档坐标
            if (!Double.isFinite(velocity[0]) || !Double.isFinite(velocity[1])) {
                Localizedrain.LOGGER.warn("[localizedrain] storm spec has non-finite velocity ({}, {}); ignoring it",
                        velocity[0], velocity[1]);
                return;
            }
            storm.velocityX = StormObject.clampVelocity(velocity[0]);
            storm.velocityZ = StormObject.clampVelocity(velocity[1]);
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
        if (target == storm.stageIndex()) {
            // 同一阶段重复设置不应重置阶段计时/半径，也不应发布 from==to 的假切换事件
            return false;
        }
        storm.enterStage(profile, target, profile.stage(storm.stageIndex()));
        afterStageWrite(level, storm);
        return true;
    }

    /** 阶段被 API 直接改写后：立即发事件并复位标志，避免下一 tick 重复发布。 */
    private static void afterStageWrite(ServerLevel level, StormObject storm) {
        // 阶段切换会换掉降水/雷能力位与强度基准：这里同 tick 刷一次，否则广播出去的是「新类型 + 旧能力位」
        StormProfile profile = storm.resolveProfile(level);
        if (profile != null) {
            storm.capabilityType = storm.weatherType(profile);
            storm.applyCapabilities(WeatherTypes.resolve(level, storm.capabilityType));
        }
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
        if (!Double.isFinite(x) || !Double.isFinite(z)) {
            return false;       // NaN/Inf 会污染坐标与存档，并让「距离回收」永远失败
        }
        StormObject storm = find(level, id);
        if (storm == null) {
            return false;
        }
        storm.velocityX = StormObject.clampVelocity(x);
        storm.velocityZ = StormObject.clampVelocity(z);
        storm.pinnedVelocity = true;
        data(level).setDirty();
        broadcast(level);
        return true;
    }

    /** 覆盖风暴雨/雷强度；下次阶段切换时恢复档案值。 */
    public static boolean setIntensity(ServerLevel level, UUID id, float rain, float thunder) {
        // NaN 与区间比较恒为 false，Mth.clamp 不会拦住它；显式拒绝，避免污染存档与客户端雨量
        if (!Float.isFinite(rain) || !Float.isFinite(thunder)) {
            return false;
        }
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
        if (!Double.isFinite(velocityX) || !Double.isFinite(velocityZ)) {
            return;             // 风场 NaN 会经 velocity 传播到每个自然风暴的坐标
        }
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
            String isolatedMode = Config.isolatedBiomeWeather().id();
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

    /** 一条 {@code compatibleWeather} 规则：source 类型允许与 targets 中的类型同点叠加。 */
    private record CompatRule(ResourceLocation source, List<CompatTarget> targets) {}

    /** 规则右侧的一项：普通 id 或 #标签。 */
    private record CompatTarget(ResourceLocation id, boolean tag) {}

    /** 参与叠加判定的候选层：附带风暴出现时间，用于判定「谁先出现」。 */
    private record SampleLayer(WeatherLayer layer, long spawnGameTime) {}

    /**
     * 解析 {@code compatibleWeather}。只有配置原文变化时才重新解析，避免把字符串解析放进
     * 每 tick 的查询热路径。
     */
    private static List<CompatRule> compatRules() {
        String signature = String.join("\u0000", Config.compatibleWeather());
        if (signature.equals(compatRulesSource)) {
            return compatRules;
        }
        compatRules = parseRules(Config.compatibleWeather());
        compatRulesSource = signature;
        TAG_CACHE.clear();
        return compatRules;
    }

    /** 解析 {@code blockedWeather}；语义与 {@code compatibleWeather} 相同，但只在书写方向生效。 */
    private static List<CompatRule> blockedRules() {
        String signature = String.join("\u0000", Config.blockedWeather());
        if (signature.equals(blockedRulesSource)) {
            return blockedRules;
        }
        blockedRules = parseRules(Config.blockedWeather());
        blockedRulesSource = signature;
        TAG_CACHE.clear();
        return blockedRules;
    }

    private static List<CompatRule> parseRules(List<? extends String> raw) {
        List<CompatRule> parsed = new ArrayList<>();
        for (String entry : raw) {
            int split = entry.indexOf('=');
            if (split <= 0 || split == entry.length() - 1) {
                continue;
            }
            ResourceLocation source = ResourceLocation.tryParse(entry.substring(0, split).trim());
            if (source == null) {
                continue;
            }
            List<CompatTarget> targets = new ArrayList<>();
            for (String token : entry.substring(split + 1).split(",")) {
                String trimmed = token.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                boolean tag = trimmed.startsWith("#");
                ResourceLocation id = ResourceLocation.tryParse(tag ? trimmed.substring(1) : trimmed);
                if (id != null) {
                    targets.add(new CompatTarget(id, tag));
                }
            }
            if (!targets.isEmpty()) {
                parsed.add(new CompatRule(source, List.copyOf(targets)));
            }
        }
        return List.copyOf(parsed);
    }

    /**
     * 两种天气是否允许在同一位置同时生效；关系是成对的（A=B 或 B=A 任一即可）。
     */
    static boolean typesCompatible(ServerLevel level, ResourceLocation first, ResourceLocation second) {
        if (first == null || second == null) {
            return false;
        }
        if (first.equals(second)) {
            return true;
        }
        for (CompatRule rule : compatRules()) {
            if (rule.source().equals(first) && ruleMatches(level, rule, second)) {
                return true;
            }
            if (rule.source().equals(second) && ruleMatches(level, rule, first)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 已选层与候选层之间是否存在单向互斥：按出现时间判定先后，只检查「先出现 → 后出现」方向。
     *
     * <p>这样 {@code fog=rain} 只拒绝「雾之后起来的雨」，雨先出现时仍允许随后起雾。</p>
     */
    private static boolean blockedPair(ServerLevel level, SampleLayer first, SampleLayer second) {
        SampleLayer earlier = first.spawnGameTime() <= second.spawnGameTime() ? first : second;
        SampleLayer later = earlier == first ? second : first;
        if (earlier.layer().typeId().equals(later.layer().typeId())) {
            return false;
        }
        for (CompatRule rule : blockedRules()) {
            if (rule.source().equals(earlier.layer().typeId())
                    && ruleMatches(level, rule, later.layer().typeId())) {
                return true;
            }
        }
        return false;
    }

    private static boolean ruleMatches(ServerLevel level, CompatRule rule, ResourceLocation id) {
        for (CompatTarget target : rule.targets()) {
            if (!target.tag()) {
                if (target.id().equals(id)) {
                    return true;
                }
            } else if (TAG_CACHE.computeIfAbsent(target.id(),
                    tag -> Set.copyOf(WeatherTypes.tagIds(level, tag))).contains(id)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 覆盖该位置的天气层，按展示强度降序，最多 {@link Config#maxStackedWeatherTypes()} 层。
     *
     * <p>没有配置兼容关系时视为「全部天气互相兼容」，只受层数上限约束；填写后只叠加列出的组合。
     * 结果按 4 格立方在本 tick 内复用。</p>
     */
    public static List<WeatherLayer> layersAt(ServerLevel level, BlockPos pos) {
        Map<Long, List<WeatherLayer>> cache = LAYER_CACHE.computeIfAbsent(level, ignored -> new HashMap<>());
        long key = quartKey(pos);
        List<WeatherLayer> cached = cache.get(key);
        if (cached != null) {
            return cached;
        }
        List<WeatherLayer> result = computeLayers(level, pos);
        if (cache.size() >= DOMINANT_CACHE_LIMIT) {
            cache.clear();
        }
        cache.put(key, result);
        return result;
    }

    /**
     * 移动模式的阶段边界预报：覆盖该位置的风暴在 {@code horizonTicks} 内进入下一阶段时，
     * 给出剩余 tick、下一阶段类型与当前雨量。末阶段则预测为晴天。
     */
    public static List<com.spectrum255.localizedrain.api.WeatherApi.ForecastStep> forecastAt(
            ServerLevel level, BlockPos pos, int horizonTicks) {
        if (level == null || pos == null || horizonTicks <= 0) {
            return List.of();
        }
        double px = pos.getX() + 0.5D;
        double py = pos.getY() + 0.5D;
        double pz = pos.getZ() + 0.5D;
        List<com.spectrum255.localizedrain.api.WeatherApi.ForecastStep> steps = new ArrayList<>();
        for (StormObject storm : data(level).storms()) {
            if (!storm.covers(px, py, pz)) {
                continue;
            }
            StormProfile profile = storm.resolveProfile(level);
            if (profile == null) {
                continue;
            }
            int remaining = (int) Math.max(0, Math.min(Integer.MAX_VALUE, storm.stageTicksLeft()));
            if (remaining > horizonTicks) {
                continue;
            }
            int nextIndex = storm.stageIndex() + 1;
            if (nextIndex < profile.stages().size()) {
                StormProfile.Stage next = profile.stages().get(nextIndex);
                steps.add(new com.spectrum255.localizedrain.api.WeatherApi.ForecastStep(remaining,
                        next.type(), storm.baseRain()));
            } else {
                steps.add(new com.spectrum255.localizedrain.api.WeatherApi.ForecastStep(remaining,
                        WeatherTypes.CLEAR_ID, 0.0F));
            }
        }
        steps.sort(java.util.Comparator.comparingInt(
                com.spectrum255.localizedrain.api.WeatherApi.ForecastStep::inTicks));
        return List.copyOf(steps.subList(0, Math.min(8, steps.size())));
    }

    private static List<WeatherLayer> computeLayers(ServerLevel level, BlockPos pos) {
        boolean manualOnly = false;
        if (isIsolatedAt(level, pos)) {
            // clear：绝对无天气；patch：由片天气机器接管；manual：只认手动风暴
            if (!"manual".equals(Config.isolatedBiomeWeather().id())) {
                return List.of();
            }
            manualOnly = true;
        }
        double px = pos.getX() + 0.5D;
        double py = pos.getY() + 0.5D;
        double pz = pos.getZ() + 0.5D;
        List<SampleLayer> candidates = new ArrayList<>(2);
        for (StormObject storm : data(level).storms()) {
            if (manualOnly && !storm.manual()) {
                continue;
            }
            if (!storm.covers(px, py, pz)) {
                continue;
            }
            StormProfile profile = storm.resolveProfile(level);
            if (profile == null) {
                continue;
            }
            ResourceLocation typeId = storm.weatherType(profile);
            WeatherType type = WeatherTypes.resolve(level, typeId);
            if (type == null) {
                continue;
            }
            WeatherLayer layer = sampleLayer(level, pos, storm, typeId, type);
            if (layer != null) {
                candidates.add(new SampleLayer(layer, storm.spawnGameTime));
            }
        }
        if (candidates.isEmpty()) {
            return List.of();
        }
        candidates.sort(Comparator.comparingDouble((SampleLayer sample) -> sample.layer().intensity()).reversed());
        int maxLayers = Math.max(1, Math.min(8, Config.maxStackedWeatherTypes()));
        if (maxLayers <= 1) {
            // 叠加被配置成关闭：不要在热路径上再碰兼容规则的签名比较
            return List.of(candidates.get(0).layer());
        }
        List<CompatRule> compat = compatRules();
        List<CompatRule> blocked = blockedRules();
        if (compat.isEmpty() && blocked.isEmpty()) {
            // 未配置兼容关系即为「全部天气互相叠加」（测试期默认），只受层数上限约束
            List<WeatherLayer> layers = new ArrayList<>(Math.min(maxLayers, candidates.size()));
            for (int i = 0; i < Math.min(maxLayers, candidates.size()); i++) {
                layers.add(candidates.get(i).layer());
            }
            return List.copyOf(layers);
        }
        List<SampleLayer> chosen = new ArrayList<>(Math.min(maxLayers, candidates.size()));
        for (SampleLayer candidate : candidates) {
            boolean allowed = true;
            for (int i = 0; i < chosen.size(); i++) {
                SampleLayer existing = chosen.get(i);
                if (blockedPair(level, existing, candidate)) {
                    // 单向互斥：保留先出现的那个。候选严格更早就替换已选层，否则跳过候选。
                    if (candidate.spawnGameTime() < existing.spawnGameTime()) {
                        chosen.remove(i--);
                    } else {
                        allowed = false;
                        break;
                    }
                } else if (!compat.isEmpty()
                        && !typesCompatible(level, existing.layer().typeId(), candidate.layer().typeId())) {
                    allowed = false;
                    break;
                }
            }
            if (allowed && chosen.size() < maxLayers) {
                chosen.add(candidate);
            }
        }
        List<WeatherLayer> layers = new ArrayList<>(chosen.size());
        for (SampleLayer sample : chosen) {
            layers.add(sample.layer());
        }
        return List.copyOf(layers);
    }

    private static WeatherLayer sampleLayer(ServerLevel level, BlockPos pos, StormObject storm,
                                            ResourceLocation typeId, WeatherType type) {
        double px = pos.getX() + 0.5D;
        double pz = pos.getZ() + 0.5D;
        double ratio = WindField.doubleConfig(Config.STORM_FALLOFF_RATIO, 0.3D);
        double distance = Math.hypot(px - storm.x(), pz - storm.z());
        double falloff = Math.max(1.0D, storm.radiusAt() * ratio);
        double horizontal = Mth.clamp((storm.radiusAt() + falloff - distance) / falloff, 0.0D, 1.0D);
        double fade = storm.fade();
        float rain = type.hasPrecipitation() ? (float) (storm.baseRain() * horizontal * fade) : 0.0F;
        float thunder = type.allowsThunder() ? (float) (storm.baseThunder() * horizontal * fade) : 0.0F;
        float intensity = type.hasPrecipitation() ? rain : (float) (horizontal * fade);
        if (intensity <= 0.0F) {
            return null;
        }
        return new WeatherLayer(typeId, type, rain, thunder, intensity,
                canPrecipitate(level, pos, storm), !storm.dying());
    }

    public static boolean isRainingAt(ServerLevel level, BlockPos pos) {
        for (WeatherLayer layer : layersAt(level, pos)) {
            if (layer.rain() > 0.05F
                    && layer.canPrecipitateHere()
                    && layer.type().hasPrecipitation()
                    && layer.type().precipitation() == Biome.Precipitation.RAIN) {
                return true;
            }
        }
        return false;
    }

    public static boolean isPrecipitatingAt(ServerLevel level, BlockPos pos) {
        for (WeatherLayer layer : layersAt(level, pos)) {
            if (layer.rain() > 0.05F && layer.type().hasPrecipitation()) {
                return true;
            }
        }
        return false;
    }

    public static boolean isThunderingAt(ServerLevel level, BlockPos pos) {
        for (WeatherLayer layer : layersAt(level, pos)) {
            if (layer.thunder() > 0.05F && layer.canPrecipitateHere() && layer.type().allowsThunder()) {
                return true;
            }
        }
        return false;
    }

    public static boolean isThunderingRegionAt(ServerLevel level, BlockPos pos) {
        for (WeatherLayer layer : layersAt(level, pos)) {
            if (layer.thunder() > 0.05F && layer.type().allowsThunder()) {
                return true;
            }
        }
        return false;
    }

    public static float thunderLevelAt(ServerLevel level, BlockPos pos) {
        float max = 0.0F;
        for (WeatherLayer layer : layersAt(level, pos)) {
            max = Math.max(max, layer.thunder());
        }
        return max;
    }

    public static int skyDarkenAt(ServerLevel level, BlockPos pos) {
        List<WeatherLayer> layers = layersAt(level, pos);
        float rain = 0.0F;
        float thunder = 0.0F;
        for (WeatherLayer layer : layers) {
            rain = Math.max(rain, layer.rain());
            thunder = Math.max(thunder, layer.thunder());
        }
        int base = WeatherManager.skyDarkenFor(level, rain, thunder);
        for (WeatherLayer layer : layers) {
            // applyTypeSkyDarken 返回 max(base, 类型自身)，逐层叠加即取全部层的最大值
            base = WeatherManager.applyTypeSkyDarken(level, layer.typeId(), base);
        }
        return base;
    }

    public static WeatherType weatherTypeAt(ServerLevel level, BlockPos pos) {
        List<WeatherLayer> layers = layersAt(level, pos);
        return layers.isEmpty() ? WeatherTypes.byId(level, WeatherTypes.CLEAR_ID) : layers.get(0).type();
    }

    /** 只约束 SURFACE 手动风暴：无天光/带天花板的维度不允许 SURFACE 雨雪，但 BAND/COLUMN 洞穴风暴仍可。 */
    public static boolean allowsManualStorms(ServerLevel level) {
        return level.dimensionType().hasSkyLight() && !level.dimensionType().hasCeiling();
    }

    /**
     * 风暴当前类型的解析结果；档案缺失或类型已被数据包热移除时返回 {@code null}。
     *
     * <p>这类风暴仍留在 {@code StormData} 里（不能为了让客户端消失就删存档），但维度级与亮度查询
     * 必须按「无天气」处理：{@code syncData} 已经跳过它们，客户端看不到任何视觉，服务端继续报雨
     * 会让兼容代理、睡觉清天气与刷怪光照与客户端不一致。
     */
    private static WeatherType registeredType(ServerLevel level, StormObject storm) {
        StormProfile profile = storm.resolveProfile(level);
        return profile == null ? null : WeatherTypes.resolve(level, storm.weatherType(profile));
    }

    /**
     * 该风暴当前是否有一个已注册的类型。生成上限只应统计「客户端看得见、真正在跑」的风暴：
     * 类型被数据包热移除的风暴会被 {@code syncData} 跳过，若仍占名额，玩家会看到「上限已满却没有风暴」。
     */
    public static boolean hasRegisteredType(ServerLevel level, StormObject storm) {
        return registeredType(level, storm) != null;
    }

    /** 类型被数据包热移除的风暴只告警一次，按「维度 + 类型 id」去重。 */
    private static void warnMissingType(ServerLevel level, ResourceLocation typeId) {
        String key = level.dimension().location() + "|" + typeId;
        if (WARNED_MISSING_TYPE.add(key)) {
            Localizedrain.LOGGER.warn(
                    "[localizedrain] storm weather type {} is not registered in {}; the storm stays in the save "
                            + "but is skipped on the client and does not occupy spawn limits until the type returns",
                    typeId, level.dimension().location());
        }
    }

    /**
     * 是否有风暴正在降水（供天气模式分派与睡觉清天气使用）。
     *
     * <p>这是维度级查询，不看具体位置的可降水条件（见天/垂直模式）；按位置判定请用
     * {@code WeatherManager.isPrecipitatingAt} / {@code rainLevelAt}。</p>
     */
    public static boolean anyPrecipitation(ServerLevel level) {
        for (StormObject storm : data(level).storms()) {
            if (storm.effectiveRain() > 0.2F && registeredType(level, storm) != null) {
                return true;
            }
        }
        return false;
    }

    /**
     * 该维度所有风暴的最大雨量（0–1），供原版 {@code getRainLevel} 兼容代理读取。
     *
     * <p>刻意不检查 {@code canPrecipitateHere}：代理要给「没有坐标」的原版查询一个维度级近似值，
     * 而逐个方块做见天/垂直判定开销过大。按位置的精确值请用 {@code WeatherManager.rainLevelAt}。</p>
     */
    public static float maxRainLevel(ServerLevel level) {
        float max = 0.0F;
        for (StormObject storm : data(level).storms()) {
            if (registeredType(level, storm) == null) {
                continue;
            }
            max = Math.max(max, storm.effectiveRain());
        }
        return Float.isFinite(max) ? Mth.clamp(max, 0.0F, 1.0F) : 0.0F;
    }

    /**
     * 该维度所有风暴的最大雷量（0–1），供原版 {@code getThunderLevel} 兼容代理读取。
     *
     * <p>与 {@link #maxRainLevel(ServerLevel)} 一样是维度级近似，不做位置判定。</p>
     */
    public static float maxThunderLevel(ServerLevel level) {
        float max = 0.0F;
        for (StormObject storm : data(level).storms()) {
            if (registeredType(level, storm) == null) {
                continue;
            }
            max = Math.max(max, storm.effectiveThunder());
        }
        return Float.isFinite(max) ? Mth.clamp(max, 0.0F, 1.0F) : 0.0F;
    }

    /** 该位置的最大局部雨量（0–1）；非降水类型或没有风暴时返回 0。 */
    public static float rainLevelAt(ServerLevel level, BlockPos pos) {
        float max = 0.0F;
        for (WeatherLayer layer : layersAt(level, pos)) {
            max = Math.max(max, layer.rain());
        }
        return max;
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
        // 同一位置同类型的手动风暴先找出（不删）：新风暴被 StormSpawnEvent 取消时旧风暴要保留
        double px = pos.getX() + 0.5D;
        double py = pos.getY() + 0.5D;
        double pz = pos.getZ() + 0.5D;
        java.util.List<UUID> replacing = new java.util.ArrayList<>();
        for (StormObject storm : data(level).storms()) {
            StormSnapshot candidate = snapshotOf(level, storm);
            if (candidate != null && candidate.manual()
                    && matchesRequested(level, candidate.weatherType(), typeId)
                    && candidate.covers(px, py, pz)) {
                replacing.add(storm.id());
            }
        }
        // 上限按「替换后」的名额算：即将被替换的旧风暴不占新名额
        int cap = WindField.intConfig(Config.MAX_MANUAL_STORMS_PER_DIMENSION, 16);
        if (manualStormCount(level) - replacing.size() >= cap) {
            warnManualCap(cap);
            return null;
        }
        StormSpawnSpec.Builder builder = StormSpawnSpec.builder(match.id(), Vec3.atCenterOf(pos))
                .stage(match.stageIndex())
                // 精确匹配才覆盖类型；能力位匹配（下界 /weather thunder → voidstorm）保留档案自己的类型外观
                .typeOverride(match.direct() ? typeId : null)
                .velocity(0.0D, 0.0D)       // 指令/API 定位的风暴默认固定，不被风场吹走
                .manual(true);
        if (!allowsManualStorms(level)) {
            // 无天光/有天花板维度：SURFACE 档案降级为以玩家为中心的 BAND，否则风暴会卡在维度顶部
            double[] band = manualBand(level, pos);
            builder.verticalBand(band[0], band[1]);
        }
        if (radius > 0) {
            builder.radius(radius);         // 0 = 沿用档案的 radius 区间
        }
        if (durationTicks > 0) {
            builder.lifetime(durationTicks);
        }
        StormSnapshot snapshot = spawn(level, builder.build(), true);
        if (snapshot == null) {
            return null;                    // 新风暴没生成：旧风暴原样保留，客户端不需要改动
        }
        if (!replacing.isEmpty()) {
            UUID newId = snapshot.id();
            int removed = removeWhere(level, s -> replacing.contains(s.id()) && !newId.equals(s.id()),
                    StormRemoveEvent.Reason.MANUAL, false);
            if (removed > 0) {
                broadcast(level);           // 一条推送里同时包含「新增」和「旧风暴消失」
            }
        }
        return snapshot;
    }

    /** 无天光维度里手动风暴的默认带：以玩家位置为中心，太窄时退化为整列。 */
    private static double[] manualBand(ServerLevel level, BlockPos pos) {
        double min = Math.max(level.getMinBuildHeight(), pos.getY() - 24.0D);
        double max = Math.min(level.getMaxBuildHeight(), pos.getY() + 40.0D);
        if (max - min < 32.0D) {
            return new double[] {level.getMinBuildHeight(), level.getMaxBuildHeight()};
        }
        return new double[] {min, max};
    }

    /** 档案 + 匹配的起始阶段（例如 thunderstorm 的 thunder 在 stage 1）。 */
    private record ProfileMatch(ResourceLocation id, int stageIndex, int weight, boolean direct) {
    }

    /**
     * 命令里的内置天气 id 与自定义类型的能力位匹配：
     * {@code /weather thunder} 也接受「自身不是 thunder、但 allows_thunder=true」的阶段
     * （例如下界的 voidstorm）；{@code /weather rain} 同理接受降水类型为雨的阶段。
     * 这种匹配不覆盖阶段自己的类型，风暴仍显示 voidstorm/blizzard 的外观。
     */
    private static boolean matchesCapability(ServerLevel level, ResourceLocation stageType, ResourceLocation requested) {
        WeatherType type = WeatherTypes.resolve(level, stageType);
        if (type == null) {
            return false;
        }
        if (requested.equals(WeatherTypes.THUNDER_ID)) {
            return type.allowsThunder();
        }
        if (requested.equals(WeatherTypes.RAIN_ID)) {
            return type.hasPrecipitation() && type.precipitation() == Biome.Precipitation.RAIN;
        }
        return false;
    }

    private static boolean matchesRequested(ServerLevel level, ResourceLocation actual, ResourceLocation requested) {
        return actual.equals(requested) || matchesCapability(level, actual, requested);
    }

    /**
     * 按天气类型挑一个匹配档案（匹配任意阶段，而不是只看 stage 0）：
     * <ul>
     *   <li>有天空的维度：优先 {@code natural = true} 且 SURFACE 的档案；没有自然档案时回退到
     *       非自然 SURFACE，避免数据包把所有档案设为 natural=false 后 /weather rain 静默失效；</li>
     *   <li>无天光 / 有天花板的维度：接受所有通过 {@link StormProfile#allowsSkylight} 的档案
     *       （BAND/COLUMN 直接使用；SURFACE 由 spawn 用 verticalBand 覆盖成本地 BAND），
     *       不区分 natural 标志，避免 cave_rain 与 voidstorm 被分流；</li>
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
            // 档案层的天光要求：REQUIRED 只允许有天光维度，FORBIDDEN 只允许无天光维度。
            // 之前漏掉这个检查，导致 FORBIDDEN 档案能被 /weather 生成到主世界。
            if (!profile.allowsSkylight(level)) {
                continue;
            }
            // 档案层的维度白名单：手动生成也必须遵守，否则「下界专属 / 末地专属」只对自然生成生效。
            if (!profile.allowsDimension(level)) {
                continue;
            }
            int stageIndex = -1;
            boolean direct = false;
            for (int index = 0; index < profile.stages().size(); index++) {
                ResourceLocation stageType = profile.stage(index).type();
                if (stageType.equals(typeId)) {
                    stageIndex = index;
                    direct = true;
                    break;
                }
                if (stageIndex < 0 && matchesCapability(level, stageType, typeId)) {
                    stageIndex = index;      // 精确匹配优先，没找到才用能力位匹配
                }
            }
            if (stageIndex < 0) {
                continue;
            }
            ProfileMatch match = new ProfileMatch(id, stageIndex, Math.max(1, profile.weight()), direct);
            if (nonSurface) {
                // 无天光/有天花板维度：skylight 允许的档案都可用；SURFACE 档案由 spawn 用 verticalBand 覆盖成 BAND
                natural.add(match);
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
        // 权重来自数据包，两个 Integer.MAX_VALUE 相加会溢出为负，nextInt(total) 直接抛异常。
        // 用 long 累加 + 浮点抽样：权重上限保留，总量不设有效上限。
        long total = 0L;
        for (ProfileMatch match : pool) {
            total += match.weight();
        }
        if (total <= 0L) {
            return pool.get(0);
        }
        double roll = level.getRandom().nextDouble() * (double) total;
        double acc = 0.0D;
        for (ProfileMatch match : pool) {
            acc += match.weight();
            if (roll < acc) {
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
        boolean skylit = level.dimensionType().hasSkyLight();
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
                        && (!skylit || level.canSeeSky(candidate))   // 有天光的维度只落在开口下方；无天光维度没有天空概念
                        && storm.covers(candidate.getX() + 0.5D, candidate.getY() + 0.5D, candidate.getZ() + 0.5D)
                        && isThunderingAt(level, candidate)) {
                    return candidate;
                }
            }
        }
        return null;
    }
}
