package com.spectrum255.localizedrain.weather;

import com.spectrum255.localizedrain.Config;
import com.spectrum255.localizedrain.ConfigFiles;
import com.spectrum255.localizedrain.Localizedrain;
import com.spectrum255.localizedrain.api.StormTags;
import com.spectrum255.localizedrain.api.WeatherMode;
import com.spectrum255.localizedrain.api.WeatherType;
import com.spectrum255.localizedrain.api.WeatherTypes;
import com.spectrum255.localizedrain.network.WeatherStatePayload;
import com.spectrum255.localizedrain.storm.StormManager;
import com.spectrum255.localizedrain.storm.WindField;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.protocol.game.ClientboundGameEventPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

public final class WeatherManager {
    /** debugLogging 开启时记录每个片上次的天气签名（type|raining|thundering），只输出变化。 */
    private static final Map<String, String> WEATHER_LOG_SIGNATURES = new HashMap<>();
    private static final Set<String> WEATHER_LOG_PATCHES = new HashSet<>();

    /** 原版 tickChunk 里雷击抽签的上限值，1/100000 每区块每 tick。 */
    public static final int LIGHTNING_ROLL_BASE = 100_000;
    /** 每个 tick 让客户端显示的雨量/雷量向目标靠近的步长，和原版的 0.01/tick 一致。 */
    private static final float LEVEL_STEP = 0.01F;

    /** 每个维度一份 SavedData，避免在降水/查询这类热点路径上反复走 DimensionDataStorage。 */
    private static final Map<ServerLevel, WeatherData> DATA_CACHE = new WeakHashMap<>();
    /** 每个玩家的客户端显示状态（含平滑中的雨量/雷量）。 */
    private static final Map<UUID, SentState> LAST_SENT = new HashMap<>();

    /**
     * 每个维度的 cell 级查询缓存：把「位置 → 群系片 + 名单判定 + WeatherData」合并成一次查找。
     * isRainingAt / skyDarkenAt / 刷怪判定都会走这里，原本要走 4～6 次 map 查找。
     */
    private static final Map<ServerLevel, CellLookup> LOOKUPS = new WeakHashMap<>();
    private static final int MAX_LOOKUP_CELLS = 400_000;

    /** 天空亮度公式里跟时间相关的部分，每 tick 刷新一次，避免光照查询里反复算 cos。 */
    private static ServerLevel dayFactorLevel;
    private static double dayFactorValue = 1.0;
    /** 每个维度距离上一次「已加载区块扫描」过了多少 tick。 */
    private static final Map<ServerLevel, Integer> SWEEP_COUNTERS = new WeakHashMap<>();
    /** 每个维度距离上一次「存档清理」过了多少 tick。 */
    private static final Map<ServerLevel, Integer> PRUNE_COUNTERS = new WeakHashMap<>();
    /** 存档清理间隔：1200 tick（1 分钟）。 */
    private static final int PRUNE_INTERVAL_TICKS = 1200;

    /**
     * 兼容代理的维度级缓存：每维度一份 {@link LocalQuery}。
     *
     * <p>只在真正被 {@code CompatWeatherQueries} 查询时按 tick 懒计算——没有任何查询就不扫描片/风暴，
     * 开关关闭时不会产生额外开销（见 SERVER 配置 {@code compatVanillaWeatherQueries}）。</p>
     */
    private static final Map<ServerLevel, LocalQuery> LOCAL_QUERY = new WeakHashMap<>();

    private static final class LocalQuery {
        float rain;
        float thunder;
        long stamp = Long.MIN_VALUE;
    }

    // 扫描期间复用的临时集合：扫描在主线程串行执行，扫完即清空，避免每轮都分配新对象
    private static final Set<String> SWEEP_CYCLING = new HashSet<>();
    private static final Map<String, WeatherBiomeFilter.Forced> SWEEP_FORCED = new LinkedHashMap<>();
    private static final Set<String> SWEEP_PINNED_CLEAR = new HashSet<>();
    private static final Map<String, List<WeatherBiomeFilter.Candidate>> SWEEP_CANDIDATES = new HashMap<>();
    private static final Set<String> SWEEP_HELD = new HashSet<>();
    /** 本 sweep 中被指令/API 手动覆盖的片：只走手动计时器，不参与自然分类。 */
    private static final Set<String> SWEEP_MANUAL = new HashSet<>();

    /** 上一 tick 的天气模式；用于检测运行期切换并通知客户端。 */
    private static WeatherMode lastMode;

    private WeatherManager() {}

    public static void tick(ServerLevel level) {
        // 兜底清掉上一个 tick 异常路径遗留的位置帧（正常路径每个帧自己 pop）
        CompatWeatherQueries.clear();
        // 热重载：每 20 tick 检查一次配置界面与存档 weather.toml，改完约 1 秒生效
        if (level.getGameTime() % 20L == 0L) {
            CompatWeatherQueries.refresh();
            WeatherMode.poll();
            WeatherMode fromFile = ConfigFiles.pollWeatherMode(level);
            if (fromFile != null && fromFile != WeatherMode.current()) {
                WeatherMode.apply(fromFile);
                Localizedrain.LOGGER.info("[localizedrain] weather mode hot-reloaded from weather.toml: {}",
                        fromFile.getSerializedName());
            }
        }
        WeatherMode mode = WeatherMode.current();
        if (mode != WeatherMode.VANILLA) {
            // 原版模式下 advanceWeatherCycle 会推进 Level 的四个雨雷字段；切到 MOBILE/PATCHES 后该方法被取消，
            // 字段会冻结在切走前的值（通常 1.0），使 getRainLevel 的基值恒为下雨、代理的 max 无法压低。
            if (level.rainLevel != 0.0F || level.oRainLevel != 0.0F) {
                level.oRainLevel = 0.0F;
                level.rainLevel = 0.0F;
            }
            if (level.thunderLevel != 0.0F || level.oThunderLevel != 0.0F) {
                level.oThunderLevel = 0.0F;
                level.thunderLevel = 0.0F;
            }
        }
        // 模式在运行期变化（配置重载/命令/文件热重载）时立刻重推风暴快照：客户端据此停止/开始接管雨量
        if (mode != lastMode) {
            lastMode = mode;
            // 移动模式期间 send() 不运行，LAST_SENT 停在进入移动模式前的旧值；不清掉的话，
            // 切回 patches 后目标值只要与旧缓存相同就不会发包，客户端会一直保留风暴雨量。
            LAST_SENT.clear();
            StormManager.resetHudCache();   // 切回移动模式时旧 HUD 去重缓存会让客户端停在片天气上
            StormManager.refreshAll(level.getServer());
            Localizedrain.LOGGER.info("[localizedrain] weather mode is now {}", mode.getSerializedName());
            if (mode == WeatherMode.VANILLA) {
                // 切到原版模式后本模组不再发包，主动清掉客户端残留的自定义天气（否则视觉层会停在旧类型）。
                // 必须遍历所有维度：lastMode 是全局单值，只有第一个执行 tick 的维度会进入这个分支，
                // 其它维度若不清理，玩家跨维度后会保留旧类型/强度/粒子/雾。
                for (ServerLevel dimension : level.getServer().getAllLevels()) {
                    for (ServerPlayer player : dimension.players()) {
                        LAST_SENT.remove(player.getUUID());
                        send(player, WeatherData.WeatherState.clearDefault());
                    }
                }
            }
        }
        switch (mode) {
            case PATCHES -> tickPatches(level);
            case MOBILE -> tickMobile(level);
            case VANILLA -> {
                // 原版模式不接管天气：清掉片模式/移动模式可能残留的移速与伤害效果
                for (ServerPlayer player : level.players()) {
                    WeatherEffects.clear(player);
                }
            }
        }
    }

    /**
     * 兼容代理的维度级近似：该维度所有片/风暴里的最大雨量、最大雷量。
     *
     * <p>第三方调用的 {@code Level#isRaining()/getRainLevel()} 没有坐标参数，只能看到这个值；
     * 位置精确的查询由 {@code CompatWeatherQueries} 的位置栈处理。计算按 tick 懒执行，
     * 没人查询就不扫描。</p>
     */
    private static LocalQuery localQuery(ServerLevel level) {
        LocalQuery query = LOCAL_QUERY.get(level);
        if (query == null) {
            query = new LocalQuery();
            LOCAL_QUERY.put(level, query);
        }
        long now = level.getGameTime();
        if (query.stamp == now) {
            return query;
        }
        query.stamp = now;
        float rain = 0.0F;
        float thunder = 0.0F;
        switch (WeatherMode.current()) {
            case PATCHES -> {
                WeatherData data = data(level);
                for (String patch : data.patches()) {
                    WeatherData.WeatherState state = data.get(patch);
                    if (state == null || !state.isRaining()) {
                        continue;
                    }
                    rain = Math.max(rain, state.rainLevel());
                    thunder = Math.max(thunder, state.thunderLevel());
                }
            }
            case MOBILE -> {
                rain = StormManager.maxRainLevel(level);
                thunder = StormManager.maxThunderLevel(level);
            }
            case VANILLA -> {
                // 原版模式不代理：CompatWeatherQueries 也会直接放行
            }
        }
        query.rain = Float.isFinite(rain) ? Mth.clamp(rain, 0.0F, 1.0F) : 0.0F;
        query.thunder = Float.isFinite(thunder) ? Mth.clamp(thunder, 0.0F, 1.0F) : 0.0F;
        return query;
    }

    /**
     * 公开查询可能被第三方模组从工作线程调用（异步区块生成、并行任务等）。
     * 本类的缓存大量使用非同步 {@link java.util.WeakHashMap} 并会访问 {@code SavedData}，
     * 跨线程读写会损坏结构，因此非主线程直接返回安全默认值。
     */
    private static boolean offThread(ServerLevel level) {
        return level != null && !level.getServer().isSameThread();
    }

    /** 该维度当前最大局部雨量（0–1），供原版 {@code getRainLevel} 兼容代理读取。 */
    public static float localRainLevel(ServerLevel level) {
        if (offThread(level)) {
            return 0.0F;
        }
        return localQuery(level).rain;
    }

    /** 该维度当前最大局部雷量（0–1），供原版 {@code getThunderLevel} 兼容代理读取。 */
    public static float localThunderLevel(ServerLevel level) {
        if (offThread(level)) {
            return 0.0F;
        }
        return localQuery(level).thunder;
    }

    /**
     * 该位置当前的雨量（0–1）。
     *
     * <p>与 {@link #isRainingAt} 不同，这里不做见天/遮挡判断，直接返回该位置所属片/风暴的雨量；
     * 供第三方模组按位置读取降水强度（例如按雨量决定容器集水速率）。</p>
     */
    public static float rainLevelAt(ServerLevel level, BlockPos pos) {
        if (level == null || pos == null || offThread(level)) {
            return 0.0F;
        }
        return switch (WeatherMode.current()) {
            case MOBILE -> StormManager.rainLevelAt(level, pos);
            case VANILLA -> level.getRainLevel(1.0F);
            case PATCHES -> patchesRainLevelAt(level, pos);
        };
    }

    /**
     * 未来天气变化（近似预报）：接下来 {@code horizonTicks} 内可预期的状态切换，按时间升序。
     *
     * <p>片模式按当前类型的 {@code next} 里权重最高的已注册条目预测（不做温湿度过滤，因此只是近似）；
     * 移动模式按风暴阶段边界与下一阶段类型预测；原版模式与无记录的位置返回空列表。</p>
     */
    public static List<com.spectrum255.localizedrain.api.WeatherApi.ForecastStep> forecastAt(
            ServerLevel level, BlockPos pos, int horizonTicks) {
        if (level == null || pos == null || offThread(level) || horizonTicks <= 0) {
            return List.of();
        }
        return switch (WeatherMode.current()) {
            case MOBILE -> StormManager.forecastAt(level, pos, horizonTicks);
            case VANILLA -> List.of();
            case PATCHES -> patchForecast(level, pos, horizonTicks);
        };
    }

    private static List<com.spectrum255.localizedrain.api.WeatherApi.ForecastStep> patchForecast(
            ServerLevel level, BlockPos pos, int horizonTicks) {
        WeatherData.WeatherState state = stateOrNull(level, pos);
        if (state == null) {
            return List.of();
        }
        int remaining = state.rainTimeRemaining();
        if (remaining > horizonTicks) {
            return List.of();
        }
        ResourceLocation next = state.typeId();
        float intensity = state.rainLevel();
        WeatherType current = WeatherTypes.byId(level, state.typeId());
        if (current != null && !current.next().isEmpty()) {
            WeatherType.Evolution best = null;
            for (WeatherType.Evolution evolution : current.next()) {
                if (evolution.weight() > 0 && (best == null || evolution.weight() > best.weight())) {
                    best = evolution;
                }
            }
            if (best != null && WeatherTypes.resolve(level, best.type()) != null) {
                next = best.type();
                intensity = WeatherTypes.byId(level, next).hasPrecipitation() ? Math.max(0.3F, intensity) : 0.0F;
            }
        }
        return List.of(new com.spectrum255.localizedrain.api.WeatherApi.ForecastStep(remaining, next, intensity));
    }

    private static float patchesRainLevelAt(ServerLevel level, BlockPos pos) {        WeatherData.WeatherState state = stateOrNull(level, pos);
        if (state == null || !state.isRaining()) {
            return 0.0F;
        }
        if (!WeatherTypes.byId(level, state.typeId()).hasPrecipitation()) {
            return 0.0F;
        }
        float value = state.rainLevel();
        return Float.isFinite(value) ? Mth.clamp(value, 0.0F, 1.0F) : 0.0F;
    }

    /** 移动天气：风暴为主；isolatedBiomeWeather=patch 时隔离群系的片仍由片机器推进。 */
    private static void tickMobile(ServerLevel level) {
        String isolatedMode = Config.isolatedBiomeWeather().id();
        if ("patch".equals(isolatedMode)) {
            tickIsolatedPatches(level);
        }
        StormManager.tick(level);       // 放在后面：维度级雨雷量以风暴为准
        // 移动模式也要做存档清理：玩家查询会持续注册父表/片，不清理会让 segment_parents 随探索膨胀
        pruneIfDue(level, data(level));
    }

    /** 非 null 时，只有通过该谓词的片会被分类与推进（移动模式下隔离群系的 patch 模式用）。 */
    private static java.util.function.Predicate<String> patchFilter;

    /** 只推进「包含隔离群系的片」，不做全维度扫描。 */
    private static void tickIsolatedPatches(ServerLevel level) {
        WeatherData data = data(level);
        BiomePatches.beginTick(level);

        // 与 tickPatches 一样按 simulationInterval 限频，否则片天气会以 interval 倍速推进
        int interval = simulationInterval();
        Integer counter = SWEEP_COUNTERS.get(level);
        int age = (counter == null ? 0 : counter) + 1;
        if (age >= interval) {
            age = 0;
            boolean cycleWeather = level.getGameRules().getBoolean(GameRules.RULE_WEATHER_CYCLE);
            Set<String> playerPatches = new HashSet<>();
            for (ServerPlayer player : level.players()) {
                String patch = BiomePatches.patchAt(level, player.blockPosition());
                if (patch != null && isIsolatedPatch(level, patch)) {
                    playerPatches.add(patch);
                }
            }
            patchFilter = patch -> isIsolatedPatch(level, patch);
            try {
                sweep(level, data, cycleWeather, interval, playerPatches);
            } finally {
                patchFilter = null;
            }
        }
        SWEEP_COUNTERS.put(level, age);

        // 隔离片的状态由服务端逐玩家包驱动客户端雨/雷通道（patch 模式下客户端不自行覆盖）。
        // send 内部按 0.01/tick 平滑并去重，所以这里每 tick 调用不会产生额外流量。
        try {
            for (ServerPlayer player : level.players()) {
                String patch = BiomePatches.patchAt(level, player.blockPosition());
                if (patch == null || !isIsolatedPatch(level, patch)) {
                    continue;
                }
                WeatherData.WeatherState state = data.get(patch);
                // 隔离片不一定有状态条目：干燥群系 / 自然池为空 / 硬锁晴天只会进入 pinnedClear 且不创建条目，
                // 刚进入尚未被 sweep 扫到的片也一样。移动风暴通道已按 patchOwnsHud 跳过这些玩家，
                // 这里若什么都不发，ClientWeatherTargets 会一直保留进入隔离群系之前的旧天气。
                send(player, state != null ? state : WeatherData.WeatherState.clearDefault());
            }
        } finally {
            // 这个缓存只服务本轮查询；key 已带维度，扫完无论是否抛异常都清空
            ISOLATED_PATCH_CACHE.clear();
        }
    }

    /** 片所属群系是否被标记为隔离；sweep 期间同一片只解析一次。 */
    private static final Map<String, Boolean> ISOLATED_PATCH_CACHE = new HashMap<>();

    private static boolean isIsolatedPatch(ServerLevel level, String patch) {
        // key 带维度：缓存是静态的，不同维度的同名 p3 片群系可能不同
        String key = level.dimension().location() + "|" + patch;
        return ISOLATED_PATCH_CACHE.computeIfAbsent(key, ignored -> {
            ResourceLocation id = BiomePatches.patchBiome(level, patch);
            if (id == null) {
                return false;
            }
            return level.registryAccess().registryOrThrow(Registries.BIOME)
                    .getHolder(id)
                    .map(holder -> holder.is(StormTags.STORM_ISOLATED))
                    .orElse(false);
        });
    }

    /** VANILLA 模式下 Mixin 直接放行原版逻辑。 */
    public static boolean vanillaMode() {
        return WeatherMode.current() == WeatherMode.VANILLA;
    }

    public static boolean mobileMode() {
        return WeatherMode.current() == WeatherMode.MOBILE;
    }

    /** 服务器停止/换存档时清空静态缓存，避免旧 ServerLevel 被强引用（dayFactorLevel 是强引用字段）。 */
    public static void resetCaches() {
        dayFactorLevel = null;
        dayFactorValue = 1.0;
        LOOKUPS.clear();
        DATA_CACHE.clear();
        SWEEP_COUNTERS.clear();
        PRUNE_COUNTERS.clear();
        LOCAL_QUERY.clear();
        LAST_SENT.clear();
        WEATHER_LOG_SIGNATURES.clear();
        ISOLATED_PATCH_CACHE.clear();
        CompatWeatherQueries.clear();
        CompatWeatherQueries.resetWarnings();
        CompatWeatherQueries.refresh();
        lastMode = null;
    }

    private static void tickPatches(ServerLevel level) {
        // 天气按维度独立推进；无天光维度（下界/末地等）同样参与，是否真的出现天气由群系降水类型、
        // 名单与命令/API 决定（原版下界/末地群系本身没有降水，默认仍然是晴天）
        // 本 tick 的群系轮廓填充预算：跨入新群系的填充最多做两片，多出来的推迟到后续 tick
        BiomePatches.beginTick(level);

        WeatherData data = data(level);
        boolean cycleWeather = level.getGameRules().getBoolean(GameRules.RULE_WEATHER_CYCLE);

        // 玩家所在片每 tick 都要解析一遍，既用于发包，也保证玩家脚下的片一定在本次扫描里。
        // 玩家列必须立即扫描（不受每 tick 列扫描预算限制），否则进入新区域会出现最多 1 秒的天气空档。
        Map<UUID, String> patchByPlayer = new HashMap<>();
        Set<String> playerPatches = new HashSet<>();
        for (ServerPlayer player : level.players()) {
            String patch = BiomePatches.patchAt(level, player.blockPosition());
            patchByPlayer.put(player.getUUID(), patch);
            if (patch != null) {
                playerPatches.add(patch);
            }
            if (BiomePatches.debugEnabled() && level.getGameTime() % 20L == 0L) {
                com.spectrum255.localizedrain.Localizedrain.LOGGER.info(
                        "[LRDBG] player={} dim={} pos={} patch={} biome={}",
                        player.getGameProfile().getName(), level.dimension().location(),
                        player.blockPosition(), patch, BiomePatches.patchBiome(level, patch));
            }
        }

        // 已加载区块的片按固定间隔推进；每次把每片推进 interval 个 tick，总速率与每 tick 推进一致
        int interval = simulationInterval();
        Integer counter = SWEEP_COUNTERS.get(level);
        int age = (counter == null ? 0 : counter) + 1;
        if (age >= interval) {
            age = 0;
            sweep(level, data, cycleWeather, interval, playerPatches);
        }
        SWEEP_COUNTERS.put(level, age);

        // 定期清理存档：删除长期未被推进的片，并在超过条数上限时按 lastTick 淘汰最旧的
        pruneIfDue(level, data);

        refreshDayFactor(level);
        spawnLightning(level, patchByPlayer, data);

        for (ServerPlayer player : level.players()) {
            String patch = patchByPlayer.get(player.getUUID());
            if (patch == null) {
                continue;
            }
            WeatherData.WeatherState state = data.get(patch);
            WeatherBiomeFilter.Decision decision = WeatherBiomeFilter.decision(level, patch);
            if (state == null || decision.hardLock()
                    || (decision.locked() && !state.isManual())) {
                send(player, WeatherData.WeatherState.clearDefault());
            } else {
                send(player, state);
            }
        }
    }

    private static int simulationInterval() {
        try {
            return Math.max(1, Config.WEATHER_SIMULATION_INTERVAL_TICKS.get());
        } catch (IllegalStateException ignored) {
            // 配置尚未加载时按默认值推进，避免服务器启动早期抛异常
            return 20;
        }
    }

    /** 定期清理（片模式与移动模式共用）：清过期片、按条数淘汰、收缩父表。 */
    private static void pruneIfDue(ServerLevel level, WeatherData data) {
        int pruneAge = PRUNE_COUNTERS.getOrDefault(level, 0) + 1;
        if (pruneAge >= PRUNE_INTERVAL_TICKS) {
            pruneAge = 0;
            data.prune(level.getGameTime(), maxStoredStates(), retentionTicks(), maxParentEntries(),
                    patch -> BiomePatches.isColumnCached(level, patch),
                    patch -> BiomePatches.evictColumn(level, patch));
        }
        PRUNE_COUNTERS.put(level, pruneAge);
    }

    /** 移动模式：该位置所在的片是否有由隔离片机制维持的天气现象（清空风暴效果前需跳过）。 */
    public static boolean isPatchWeatherActiveAt(ServerLevel level, BlockPos pos) {
        WeatherData.WeatherState state = stateOrNull(level, pos);
        return state != null && state.isWeatherActive();
    }

    /** 是否同时扫描洞穴/浮岛等 3D 群系子状态。 */
    private static boolean scan3dSubstates() {
        try {
            return Config.SCAN_3D_SUBSTATES.get();
        } catch (IllegalStateException ignored) {
            return true;
        }
    }

    /** 每个维度最多保留的天气状态条数。 */
    private static int maxStoredStates() {
        try {
            return Math.max(256, Config.MAX_STORED_STATES.get());
        } catch (IllegalStateException ignored) {
            return 4096;
        }
    }

    /** 每个维度段父表的硬上限。 */
    private static int maxParentEntries() {
        try {
            return Math.max(4096, Config.MAX_PARENT_ENTRIES.get());
        } catch (IllegalStateException ignored) {
            return 32768;
        }
    }

    /** 天气状态保留时长（tick）；0 = 不按时间清理。 */
    private static long retentionTicks() {
        try {
            int days = Config.STATE_RETENTION_DAYS.get();
            return days <= 0 ? 0L : (long) days * 24_000L;
        } catch (IllegalStateException ignored) {
            return 7L * 24_000L;
        }
    }

    /**
     * 推进所有已加载区块对应的群系片。
     *
     * <p>每个区块取中心列的地表位置判定所属片（16×16 方块一格的粒度），同一片只推进一次。
     * 每片一次推进 {@code ticks} 个 tick，等价于原来每 tick 推进一次，只是把粒度放粗到扫描间隔。</p>
     */
    private static void sweep(ServerLevel level, WeatherData data, boolean cycleWeather, int ticks,
                              Set<String> playerPatches) {
        // 结算间隔是 ticks 个 tick：预算按间隔放大，冷启动补列不会被 4 列/tick 的默认值拖慢
        BiomePatches.beginSweep(level, ticks);
        RandomSource random = level.getRandom();
        long gameTime = level.getGameTime();
        Set<String> cycling = SWEEP_CYCLING;
        Map<String, WeatherBiomeFilter.Forced> forcedByPatch = SWEEP_FORCED;
        Set<String> pinnedClear = SWEEP_PINNED_CLEAR;
        Map<String, List<WeatherBiomeFilter.Candidate>> candidateByPatch = SWEEP_CANDIDATES;
        Set<String> manual = SWEEP_MANUAL;
        cycling.clear();
        forcedByPatch.clear();
        pinnedClear.clear();
        candidateByPatch.clear();
        manual.clear();

        boolean scan3d = scan3dSubstates();
        for (long chunkPos : LoadedChunks.snapshot(level)) {
            int chunkX = ChunkPos.getX(chunkPos);
            int chunkZ = ChunkPos.getZ(chunkPos);
            if (!level.getChunkSource().hasChunk(chunkX, chunkZ)) {
                // 集合里的条目可能比区块卸载事件早一帧，扫描时再确认一次
                continue;
            }
            if (scan3d) {
                // 整列扫描一次就能拿到该列所有段（含洞穴/浮岛）：直接分类全部段，
                // 不再按 section 逐点采样（那段逻辑与列段完全重复）
                if (BiomePatches.ensureColumn(level, chunkX, chunkZ)) {
                    BiomePatches.forEachColumnPatch(level, chunkX, chunkZ, patch ->
                            classify(level, data, patch, cycling, forcedByPatch, pinnedClear, candidateByPatch, manual));
                }
                continue;
            }
            // 不扫描 3D 子状态时只推进地表所在的那一段
            BlockPos surface = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING,
                    new BlockPos((chunkX << 4) + 8, level.getMinBuildHeight(), (chunkZ << 4) + 8));
            if (level.isOutsideBuildHeight(surface)) {
                // 下界这类带天花板维度的地表高度图落在建筑高度之上，钳回范围内再采样群系
                surface = new BlockPos(surface.getX(), level.getMaxBuildHeight() - 1, surface.getZ());
            }
            String patch = BiomePatches.patchAtBudgeted(level, surface);
            if (patch == null) {
                continue;
            }
            // 预算用尽时 patchAtBudgeted 返回临时回退键（不写缓存）：等下一次扫描再算，避免把错误归属推进到状态里
            String cached = BiomePatches.cachedPatchAt(level, surface);
            if (cached == null || !cached.equals(patch)) {
                continue;
            }
            classify(level, data, patch, cycling, forcedByPatch, pinnedClear, candidateByPatch, manual);
        }
        // 玩家所在片即使对应区块还没被扫描到也要推进（例如刚传送进未加载区域）
        for (String patch : playerPatches) {
            classify(level, data, patch, cycling, forcedByPatch, pinnedClear, candidateByPatch, manual);
        }

        // 强制天气先压旗标，再按 cycle=false 推进：计时器不被随机循环覆盖，雨量/雷量仍渐变
        for (Map.Entry<String, WeatherBiomeFilter.Forced> entry : forcedByPatch.entrySet()) {
            WeatherBiomeFilter.Forced forced = entry.getValue();
            data.forceWeather(entry.getKey(), forced.id(), forced.type(), random);
        }
        Set<String> held = SWEEP_HELD;
        held.clear();
        for (String patch : pinnedClear) {
            if (data.get(patch) != null) {
                // 只让已经存在的状态淡出；没有记录的干燥群系不必创建存档条目
                data.forceClear(patch, random);
                held.add(patch);
            }
        }
        held.addAll(forcedByPatch.keySet());

        if (!cycling.isEmpty()) {
            data.tick(level, random, cycleWeather, cycling, gameTime, candidateByPatch, ticks);
        }
        if (!held.isEmpty()) {
            data.tick(level, random, false, held, gameTime, Map.of(), ticks);
        }
        if (!manual.isEmpty()) {
            // 手动覆盖单独计时：不参与自然循环，到期后回到晴天再由名单重新接管
            data.tickManual(level, random, manual, gameTime, ticks, cycleWeather);
        }

        if (logWeatherChanges()) {
            WEATHER_LOG_PATCHES.clear();
            WEATHER_LOG_PATCHES.addAll(cycling);
            WEATHER_LOG_PATCHES.addAll(held);
            WEATHER_LOG_PATCHES.addAll(manual);
            logWeatherChanges(level, data, WEATHER_LOG_PATCHES);
        }

        // 不再把局部最大值写回维度级 rainLevel/thunderLevel：否则 Level#isRaining() 会让第三方逻辑全维度生效。
        // 客户端渲染仍由逐玩家发包驱动；第三方模组应改用 WeatherApi 的按位置查询。
    }

    private static boolean debugLogging() {
        try {
            return Config.DEBUG_LOGGING.get();
        } catch (IllegalStateException ignored) {
            return false;
        }
    }

    private static boolean logWeatherChanges() {
        try {
            return Config.LOG_WEATHER_CHANGES.get();
        } catch (IllegalStateException ignored) {
            return false;
        }
    }

    /** 片 key {@code p3:<colX>,<colZ>,<segStartY>} → 片中心方块坐标；格式不符返回 null。 */
    private static BlockPos patchCenter(String patch) {
        if (patch == null || !patch.startsWith("p3:")) {
            return null;
        }
        String[] parts = patch.substring(3).split(",");
        if (parts.length != 3) {
            return null;
        }
        try {
            return new BlockPos(Integer.parseInt(parts[0]) * 16 + 8,
                    Integer.parseInt(parts[2]), Integer.parseInt(parts[1]) * 16 + 8);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    /** debugLogging：任何片天气（类型 / 是否下雨 / 是否打雷）变化时输出位置、群系与类型。 */
    private static void logWeatherChanges(ServerLevel level, WeatherData data, Set<String> patches) {
        for (String patch : patches) {
            WeatherData.WeatherState state = data.get(patch);
            if (state == null) {
                continue;
            }
            String signature = state.typeId() + "|" + state.isRaining() + "|" + state.isThundering();
            // key 必须带维度：不同维度的同名 p3 片会互相覆盖签名，导致日志漏报
            String logKey = level.dimension().location() + "|" + patch;
            String previous = WEATHER_LOG_SIGNATURES.put(logKey, signature);
            if (signature.equals(previous)) {
                continue;
            }
            BlockPos pos = patchCenter(patch);
            com.spectrum255.localizedrain.Localizedrain.LOGGER.info(
                    "[LRDBG] weather change dim={} pos={} patch={} biome={} type={} raining={} thundering={} rain={} thunder={} previous={}",
                    level.dimension().location(),
                    pos == null ? "?" : pos.getX() + "," + pos.getY() + "," + pos.getZ(),
                    patch,
                    BiomePatches.patchBiome(level, patch),
                    state.typeId(),
                    state.isRaining(),
                    state.isThundering(),
                    state.rainLevel(),
                    state.thunderLevel(),
                    previous == null ? "none" : previous);
        }
        if (WEATHER_LOG_SIGNATURES.size() > 65536) {
            WEATHER_LOG_SIGNATURES.clear();     // 极端情况下防止无界增长；下一轮会重新输出一次全量
        }
    }

    private static void classify(ServerLevel level, WeatherData data, String patch, Set<String> cycling,
                                 Map<String, WeatherBiomeFilter.Forced> forcedByPatch, Set<String> pinnedClear,
                                 Map<String, List<WeatherBiomeFilter.Candidate>> candidateByPatch,
                                 Set<String> manual) {
        if (patchFilter != null && !patchFilter.test(patch)) {
            return;                     // 移动模式：非隔离片一律不接管
        }
        if (patch == null) {
            return;
        }
        // 相邻两列可能在两次 classify 之间被并查集合并：用规范 key 去重，
        // 否则同一个 WeatherState 会在一轮 sweep 里被 tick 两次（计时器双倍推进）。
        String key = data.canonicalKey(patch);
        if (key == null) {
            key = patch;
        }
        if (cycling.contains(key) || manual.contains(key)
                || forcedByPatch.containsKey(key) || pinnedClear.contains(key)) {
            return;
        }
        WeatherBiomeFilter.Decision decision = WeatherBiomeFilter.decision(level, patch);
        if (decision.forced() != null) {
            // 强制天气：类型可能出现在干燥群系（例如沙漠里的沙尘暴），不检查 hasPrecipitation
            forcedByPatch.put(key, decision.forced());
            return;
        }
        if (decision.hardLock()) {
            // 强制晴天：真正的锁定，指令/API/自然循环都无法覆盖
            pinnedClear.add(key);
            return;
        }
        if (data.isManual(patch)) {
            // 指令/API 手动覆盖：只走手动计时器，不参与自然循环
            manual.add(key);
            return;
        }
        if (decision.locked()) {
            // 软锁定：自然池里只剩晴天（名单剔除、干燥群系默认），手动天气仍然有效
            pinnedClear.add(key);
            return;
        }
        candidateByPatch.put(key, decision.pool());
        cycling.add(key);
    }

    /** 取（必要时计算）该位置所在群系范围的标识。 */
    public static String patchAt(ServerLevel level, BlockPos pos) {
        return BiomePatches.patchAt(level, pos);
    }

    /** 原版语义的 isRainingAt（不经过本模组的 Mixin，避免递归）。 */
    private static boolean vanillaRainingAt(ServerLevel level, BlockPos pos) {
        return level.isRaining()
                && level.getBiome(pos).value().getPrecipitationAt(pos) == Biome.Precipitation.RAIN
                && level.canSeeSky(pos)
                && level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, pos).getY() <= pos.getY();
    }

    /**
     * 数据包重载（/reload）后把片模式缓存、已有天气状态与风暴能力位刷新到新的定义。
     * 不改变任何状态的类型 ID、计时器、手动标记或空间归属。
     */
    /**
     * 数据包重载后刷新片状态与风暴能力位。
     *
     * <p>调用方负责先让过滤器缓存失效（{@code WeatherBiomeFilter.invalidate()}），
     * 本方法只处理存档状态与风暴派生位。</p>
     */
    public static void refreshAfterDatapackReload(MinecraftServer server) {
        if (server == null) {
            return;
        }
        int states = 0;
        int storms = 0;
        for (ServerLevel level : server.getAllLevels()) {
            WeatherData data = data(level);
            int changedStates = data.refreshTypeDefinitions(level);
            if (changedStates > 0) {
                data.setDirty();
            }
            states += changedStates;
            storms += StormManager.refreshCapabilities(level);
        }
        Localizedrain.LOGGER.info(
                "[localizedrain] reloaded datapack weather definitions: {} states and {} storms refreshed",
                states, storms);
        // 快照里可能还带着已删除类型的原始 id：立即重推一次，别等周期校正
        StormManager.refreshAll(server);
        com.spectrum255.localizedrain.api.StormProfiles.resetWarnings();
    }

    /** 原版 isRainingAt 的完整语义：局部天气 + 头顶无遮挡 + 未被方块挡住 + 该群系确实降的是雨。 */
    public static boolean isRainingAt(ServerLevel level, BlockPos pos) {
        if (offThread(level)) {
            return false;
        }
        return switch (WeatherMode.current()) {
            case MOBILE -> StormManager.isRainingAt(level, pos);
            case VANILLA -> vanillaRainingAt(level, pos);
            case PATCHES -> patchesIsRainingAt(level, pos);
        };
    }

    /** 维度内是否有任意片/风暴正在降水：仅用于把原版维度级判定（例如睡觉清天气）局部化。 */
    public static boolean hasLocalPrecipitation(ServerLevel level) {
        if (offThread(level)) {
            return false;
        }
        if (mobileMode()) {
            return StormManager.anyPrecipitation(level);
        }
        WeatherData data = data(level);
        for (String patch : data.patches()) {
            WeatherData.WeatherState state = data.get(patch);
            if (state != null && state.isRaining()) {
                return true;
            }
        }
        return false;
    }

    private static boolean patchesIsRainingAt(ServerLevel level, BlockPos pos) {
        WeatherData.WeatherState state = stateAt(level, pos);
        WeatherType type = WeatherTypes.byId(level, state.typeId());
        return type.hasPrecipitation()
                && type.precipitation() == Biome.Precipitation.RAIN
                && state.isRaining()
                && canPrecipitateAt(level, pos);
    }

    /** 降水（积雪、炼药锅、水结冰）只看局部天气，降水类型由原版按群系自行判断。 */
    public static boolean isPrecipitatingAt(ServerLevel level, BlockPos pos) {
        if (offThread(level)) {
            return false;
        }
        return switch (WeatherMode.current()) {
            case MOBILE -> StormManager.isPrecipitatingAt(level, pos);
            case VANILLA -> level.isRaining();
            case PATCHES -> patchesIsPrecipitatingAt(level, pos);
        };
    }

    private static boolean patchesIsPrecipitatingAt(ServerLevel level, BlockPos pos) {
        WeatherData.WeatherState state = stateAt(level, pos);
        return WeatherTypes.byId(level, state.typeId()).hasPrecipitation()
                && state.isRaining();
    }

    public static boolean isThunderingAt(ServerLevel level, BlockPos pos) {
        if (offThread(level)) {
            return false;
        }
        return switch (WeatherMode.current()) {
            case MOBILE -> StormManager.isThunderingAt(level, pos);
            case VANILLA -> level.isThundering() && vanillaRainingAt(level, pos);
            case PATCHES -> patchesIsThunderingAt(level, pos);
        };
    }

    private static boolean patchesIsThunderingAt(ServerLevel level, BlockPos pos) {
        WeatherData.WeatherState state = stateAt(level, pos);
        return dimensionAllowsThunder(level)
                && WeatherTypes.byId(level, state.typeId()).allowsThunder()
                && state.isThundering()
                && canPrecipitateAt(level, pos);
    }

    /** 有天光的维度要求没有天花板；无天光维度（下界/末地等）允许天气，是否启用由名单/命令/API 决定。 */
    private static boolean dimensionAllowsThunder(ServerLevel level) {
        return !level.dimensionType().hasSkyLight() || !level.dimensionType().hasCeiling();
    }

    /** 该位置是否真的能淋到雨：无天光维度没有「见天」概念，天气激活即可；有天光维度按见天 + 高度图 + 群系降水判定。 */
    private static boolean canPrecipitateAt(ServerLevel level, BlockPos pos) {
        if (!level.dimensionType().hasSkyLight()) {
            return true;
        }
        return level.canSeeSky(pos)
                && level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, pos).getY() <= pos.getY()
                && level.getBiome(pos).value().getPrecipitationAt(pos) == Biome.Precipitation.RAIN;
    }

    /**
     * 该位置所属范围是否处于雷暴（对应原版维度级的 isThundering()，但不做见天判断）。
     * 原版刷怪判定用它决定要不要额外减 10 点光照，必须按位置而不是整个维度来算。
     */
    public static boolean isThunderingRegionAt(ServerLevel level, BlockPos pos) {
        if (offThread(level)) {
            return false;
        }
        return switch (WeatherMode.current()) {
            case MOBILE -> StormManager.isThunderingRegionAt(level, pos);
            case VANILLA -> level.isThundering();
            case PATCHES -> patchesIsThunderingRegionAt(level, pos);
        };
    }

    private static boolean patchesIsThunderingRegionAt(ServerLevel level, BlockPos pos) {
        WeatherData.WeatherState state = stateAt(level, pos);
        return dimensionAllowsThunder(level)
                && WeatherTypes.byId(level, state.typeId()).allowsThunder()
                && state.isThundering();
    }

    /**
     * 返回该位置所在群系片当前的雷量（0.0F–1.0F），供外部模组读取（如气象协议）。
     *
     * <p>只读：不会创建天气状态、不会触发群系轮廓计算、不会加载区块；没有记录的区域，
     * 以及数值异常（NaN/Infinity）都返回 {@code 0.0F}。远处没有被 tick 的群系片会停在上次 tick 时的值。</p>
     *
     * <p>不做见天判断、不做降水门控：即使该处没有下雨，也返回存储的雷量。与
     * {@link #isThunderingRegionAt(ServerLevel, BlockPos)} 保持一致——后者为 true 时本方法必然大于 0.9。</p>
     */
    public static float thunderLevelAt(ServerLevel level, BlockPos pos) {
        if (level == null || pos == null || offThread(level)) {
            return 0.0F;
        }
        return switch (WeatherMode.current()) {
            case MOBILE -> StormManager.thunderLevelAt(level, pos);
            case VANILLA -> level.getThunderLevel(1.0F);
            case PATCHES -> patchesThunderLevelAt(level, pos);
        };
    }

    private static float patchesThunderLevelAt(ServerLevel level, BlockPos pos) {
        WeatherData.WeatherState state = stateOrNull(level, pos);
        if (state == null) {
            return 0.0F;
        }
        // 该类型不允许雷时，雷量通道对它没有意义（例如沙尘暴、晴天）
        if (!WeatherTypes.byId(level, state.typeId()).allowsThunder()) {
            return 0.0F;
        }
        float value = state.thunderLevel();
        return Float.isFinite(value) ? Mth.clamp(value, 0.0F, 1.0F) : 0.0F;
    }

    /**
     * 按位置所在范围计算天空变暗值，公式与原版 Level#updateSkyBrightness 一致。
     * 用它替换维度级的 getSkyDarken() 后，局部下雨/雷暴才不会把整张地图的亮度一起压低。
     */
    public static int skyDarkenAt(ServerLevel level, BlockPos pos) {
        if (offThread(level)) {
            return 0;
        }
        return switch (WeatherMode.current()) {
            case MOBILE -> StormManager.skyDarkenAt(level, pos);
            case VANILLA -> skyDarkenFor(level, level.getRainLevel(1.0F), level.getThunderLevel(1.0F));
            case PATCHES -> patchesSkyDarkenAt(level, pos);
        };
    }

    private static int patchesSkyDarkenAt(ServerLevel level, BlockPos pos) {
        WeatherData.WeatherState state = stateOrNull(level, pos);
        float rain = state == null ? 0.0F : state.rainLevel();
        float thunder = state == null ? 0.0F : state.thunderLevel();
        int base = skyDarkenFor(level, rain, thunder);
        return state == null ? base : applyTypeSkyDarken(level, state.typeId(), base);
    }

    /**
     * 天气类型自定义的天空变暗：{@code visuals.sky_darken} 是 0–1 的强度，映射到原版 0–11 的天空变暗值。
     * 这样即使天气本身不降水（雾、沙尘暴等），也能像雷暴一样压低光照、允许刷怪。
     */
    public static int applyTypeSkyDarken(ServerLevel level, ResourceLocation typeId, int base) {
        WeatherType type = WeatherTypes.resolve(level, typeId);
        if (type == null) {
            return base;
        }
        int typeDarken = Mth.ceil(Mth.clamp(type.visuals().skyDarken(), 0.0F, 1.0F) * 11.0F);
        return Math.max(base, typeDarken);
    }

    /** 天空变暗公式（与原版 Level#updateSkyBrightness 一致），供片模式与风暴模式共用。 */
    public static int skyDarkenFor(ServerLevel level, float rain, float thunder) {
        double rainFactor = 1.0 - (double) (rain * 5.0F) / 16.0;
        double thunderFactor = 1.0 - (double) (thunder * 5.0F) / 16.0;
        return (int) ((1.0 - dayFactor(level) * rainFactor * thunderFactor) * 11.0);
    }

    /** 该位置当前的天气类型（供 API/调试）；缓存未命中时现场解析一次，仍然没有记录才返回晴天。 */
    /** 该位置的天气类型 id（无记录返回晴天 id），供 API 的层视图使用。 */
    public static ResourceLocation weatherTypeIdAt(ServerLevel level, BlockPos pos) {
        if (level == null || pos == null || offThread(level)) {
            return WeatherTypes.CLEAR_ID;
        }
        WeatherData.WeatherState state = stateOrNull(level, pos);
        return state == null ? WeatherTypes.CLEAR_ID : state.typeId();
    }

    public static WeatherType weatherTypeAt(ServerLevel level, BlockPos pos) {
        return switch (WeatherMode.current()) {
            case MOBILE -> StormManager.weatherTypeAt(level, pos);
            case VANILLA -> vanillaWeatherTypeAt(level, pos);
            case PATCHES -> patchesWeatherTypeAt(level, pos);
        };
    }

    /** 原版语义只有「下雨 / 雷暴 / 晴天」三态。 */
    private static WeatherType vanillaWeatherTypeAt(ServerLevel level, BlockPos pos) {
        if (level.isThundering() && vanillaRainingAt(level, pos)) {
            return WeatherTypes.byId(level, WeatherTypes.THUNDER_ID);
        }
        return level.isRaining() ? WeatherTypes.byId(level, WeatherTypes.RAIN_ID)
                : WeatherTypes.byId(level, WeatherTypes.CLEAR_ID);
    }

    private static WeatherType patchesWeatherTypeAt(ServerLevel level, BlockPos pos) {
        WeatherData.WeatherState state = stateOrNull(level, pos);
        if (state == null) {
            // 与 HUD 发包使用同一条可计算的解析路径，避免附属模组在缓存冷启动时误判为晴天
            state = resolveState(level, pos);
        }
        return state == null ? WeatherTypes.byId(level, WeatherTypes.CLEAR_ID) : WeatherTypes.byId(level, state.typeId());
    }

    /**
     * 现场解析该位置的天气状态（单列按需扫描，不受每 tick 预算限制），只给低频 API 读取用。
     *
     * <p>不能走 {@code patchAtBudgeted}：预算耗尽时它会返回 {@code null}，附属模组会在那一 tick
     * 短暂读到晴天，而玩家 tick 路径用的本来也是无预算的 {@link BiomePatches#patchAt}。</p>
     */
    private static WeatherData.WeatherState resolveState(ServerLevel level, BlockPos pos) {
        if (level.isOutsideBuildHeight(pos)) {
            return null;
        }
        String patch = BiomePatches.patchAt(level, pos);
        if (patch == null) {
            return null;
        }
        WeatherData.WeatherState state = data(level).get(patch);
        if (WeatherBiomeFilter.decision(level, patch).hardLock()) {
            return null;
        }
        return state;
    }

    /** 已经记录过的所有范围（探索过的），用于 global 与全局天气兼容层。 */
    public static Set<String> allPatches(ServerLevel level) {
        return Set.copyOf(data(level).patches());
    }

    /**
     * 把一批群系片手动设为注册表里的某个天气类型（指令/API 用）。
     *
     * <p>白名单/黑名单只约束自然循环，手动设置可以覆盖它们；被配置强制天气锁定的片仍会被跳过。
     * 设置的状态会记录为手动覆盖，计时结束后回到晴天，之后由名单决定是否重新参与自然循环。</p>
     */
    public static boolean setWeather(ServerLevel level, Collection<String> patches,
                                     ResourceLocation typeId, int duration) {
        if (typeId == null) {
            return false;
        }
        if (mobileMode()) {
            // 移动模式没有「片」的概念：把每个片中心转换成一次手动风暴，让命令/API 与片模式同样生效。
            // 最多尝试「手动风暴上限」次，避免 global 对成千上万个片逐个 removeWhere 扫描。
            int budget = WindField.intConfig(Config.MAX_MANUAL_STORMS_PER_DIMENSION, 16);
            if (WeatherTypes.CLEAR_ID.equals(typeId)) {
                // clear 不生成风暴：按处理过的片数扣预算，避免对全维度片逐个扫描
                for (String patch : patches) {
                    if (budget <= 0) {
                        break;
                    }
                    BlockPos center = patchCenter(patch);
                    if (center == null) {
                        continue;
                    }
                    budget--;
                    StormManager.clearAtColumn(level, center, 0.0D);
                }
                return true;
            }
            for (String patch : patches) {
                if (budget <= 0) {
                    break;
                }
                BlockPos center = patchCenter(patch);
                if (center == null || WeatherTypes.CLEAR_ID.equals(WeatherBiomeFilter.forcedType(level, patch))) {
                    continue;
                }
                // 按尝试次数扣预算：即使生成失败（上限/无档案）也不会继续扫后续片
                budget--;
                StormManager.spawnManual(level, center, typeId, 0, duration);
            }
            return true;
        }
        WeatherType type = WeatherTypes.resolve(level, typeId);
        if (type == null) {
            // 未注册类型不能静默回落到晴天：那会把片的类型改成 clear，而调用方以为设置成功
            Localizedrain.LOGGER.warn("[localizedrain] refusing to set unknown weather type {}", typeId);
            return false;
        }
        Collection<String> allowed = patches.stream()
                .filter(patch -> WeatherBiomeFilter.forcedType(level, patch) == null)
                .toList();
        if (allowed.isEmpty()) {
            return false;
        }
        boolean raining = type.hasPrecipitation();
        boolean thundering = raining && type.allowsThunder();
        data(level).setWeather(allowed, typeId.toString(), raining, thundering, duration, level.getRandom(), true);
        return true;
    }

    /** 兼容层：把全局的 setWeatherParameters 调用翻译成「已记录的所有范围一起变天」。 */
    public static void setAll(ServerLevel level, boolean raining, boolean thundering, int duration) {
        if (mobileMode()) {
            StormManager.setAllFromVanilla(level, raining, thundering, duration);
            return;
        }
        Collection<String> patches = allPatches(level);
        if (patches.isEmpty()) {
            // 该维度还没有任何天气记录（例如刚进入下界）：退化为玩家所在片，让原版 /weather 也能生效
            Set<String> playerPatches = new HashSet<>();
            for (ServerPlayer player : level.players()) {
                String patch = BiomePatches.patchAtBudgeted(level, player.blockPosition());
                if (patch != null) {
                    playerPatches.add(patch);
                }
            }
            patches = playerPatches;
        }
        ResourceLocation typeId = !raining
                ? WeatherTypes.CLEAR_ID
                : (thundering ? WeatherTypes.THUNDER_ID : WeatherTypes.RAIN_ID);
        setWeather(level, patches, typeId, duration <= 0 ? -1 : duration);
    }

    /** 跳过夜晚时调用：清空本维度所有范围的降雨/雷暴。 */
    public static void clearAll(ServerLevel level) {
        if (mobileMode()) {
            StormManager.clearNatural(level);
            return;
        }
        data(level).clearAll(level.getRandom());
    }

    /** 玩家的客户端状态会被重建（登录/跨维度/重生），清掉缓存以便重新推送一次完整天气。 */
    public static void forget(UUID playerId) {
        LAST_SENT.remove(playerId);
        WeatherEffects.forget(playerId);
    }

    /**
     * 应用数据包定义的玩法效果：{@code effects.damage} 按周期对暴露中的玩家造成伤害，
     * {@code effects.movement_speed} 作为乘算倍率挂在移动速度属性上。
     * 实际执行交给 {@link WeatherEffects}，与移动模式共用同一份状态。
     */
    private static void applyEffects(ServerPlayer player, ServerLevel level, WeatherData.WeatherState state,
                                     WeatherType type) {
        WeatherEffects.apply(player, level, type, state.isWeatherActive());
    }

    /** 配置重载后清空 cell 查询缓存，让黑白名单/强制天气判定重新计算。 */
    static void invalidateLookups() {
        LOOKUPS.clear();
    }

    /**
     * 原版雷暴的落雷密度是「每个正在 tick 的区块每 tick 1/100000」，而只有玩家附近的区块才会 tick。
     * 这里按视距内的区块数把密度折算成「每个玩家每 tick 的概率」，再挑一个视距内已加载的区块落地，
     * 用 {@link #isThunderingAt} 过滤掉非雷暴区域。这样不依赖原版 tickChunk 的维度级判定，
     * 局地雷暴也能正常出闪电。
     */
    private static void spawnLightning(ServerLevel level, Map<UUID, String> patchByPlayer, WeatherData data) {
        RandomSource random = level.getRandom();
        boolean anyStormyPatch = false;
        for (String patch : patchByPlayer.values()) {
            if (patch == null) {
                continue;
            }
            WeatherData.WeatherState state = data.get(patch);
            WeatherBiomeFilter.Decision decision = WeatherBiomeFilter.decision(level, patch);
            if (state != null
                    && state.isThundering()
                    && WeatherTypes.byId(level, state.typeId()).allowsThunder()
                    && !decision.hardLock()) {
                anyStormyPatch = true;
                break;
            }
        }
        if (!anyStormyPatch) {
            return;
        }
        int view = level.getServer().getPlayerList().getViewDistance();
        int side = 2 * view + 1;
        double chance = Math.min(1.0, (double) (side * side) / LIGHTNING_ROLL_BASE);
        for (ServerPlayer player : level.players()) {
            if (random.nextDouble() >= chance) {
                continue;
            }
            int chunkX = player.chunkPosition().x + random.nextInt(side) - view;
            int chunkZ = player.chunkPosition().z + random.nextInt(side) - view;
            if (!level.getChunkSource().hasChunk(chunkX, chunkZ)) {
                continue;
            }
            int x = (chunkX << 4) + random.nextInt(16);
            int z = (chunkZ << 4) + random.nextInt(16);
            BlockPos pos = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING,
                    new BlockPos(x, level.getMinBuildHeight(), z));
            if (!isThunderingAt(level, pos)) {
                continue;
            }
            // 与原版 tickChunk 相同的陷阱概率；抽到骷髅马时闪电只做视觉效果
            WeatherLightning.strike(level, pos, random);
        }
    }

    private static void refreshDayFactor(ServerLevel level) {
        dayFactorLevel = level;
        dayFactorValue = computeDayFactor(level);
    }

    private static double dayFactor(ServerLevel level) {
        if (dayFactorLevel == level) {
            return dayFactorValue;
        }
        dayFactorLevel = level;
        dayFactorValue = computeDayFactor(level);
        return dayFactorValue;
    }

    private static double computeDayFactor(ServerLevel level) {
        return 0.5 + 2.0 * Mth.clamp((double) Mth.cos(level.getTimeOfDay(1.0F) * (float) (Math.PI * 2)), -0.25, 0.25);
    }

    static WeatherData data(ServerLevel level) {
        WeatherData data = DATA_CACHE.get(level);
        if (data == null) {
            data = level.getDataStorage().computeIfAbsent(WeatherData.factory(), WeatherData.FILE_ID);
            DATA_CACHE.put(level, data);
        }
        return data;
    }

    private static WeatherData.WeatherState stateOrNull(ServerLevel level, BlockPos pos) {
        if (level.isOutsideBuildHeight(pos)) {
            return null;
        }
        CellLookup lookup = LOOKUPS.get(level);
        if (lookup == null) {
            lookup = new CellLookup();
            LOOKUPS.put(level, lookup);
        }
        // 缓存键：水平与垂直都用 quart（4 方块）——3D 片的段边界就落在 quart 边界上，
        // 用 section 会把同一 section 内不同段的查询结果混淆，导致天气边界被吸附到 16 格
        long cell = SectionPos.asLong(
                QuartPos.fromBlock(pos.getX()),
                QuartPos.fromBlock(pos.getY()),
                QuartPos.fromBlock(pos.getZ()));
        LookupCell cached = lookup.cells.get(cell);
        if (cached == null) {
            // 便宜检查（缓存未命中不触发洪水填充）；同一格的名单判定、降水类型与 WeatherData 一起缓存
            String patch = BiomePatches.cachedPatchAt(level, pos);
            if (patch == null) {
                return null;
            }
            WeatherBiomeFilter.Decision decision = WeatherBiomeFilter.decision(level, patch);
            cached = new LookupCell(patch, decision);
            lookup.cells.put(cell, cached);
        }
        if (lookup.data == null) {
            lookup.data = data(level);
        }
        WeatherData.WeatherState state = lookup.data.get(cached.patch());
        if (cached.decision().hardLock()) {
            return null;
        }
        return state;
    }

    /** 只读查询：没有记录的位置按晴天处理，不会创建存档条目，也不会触发轮廓计算。 */
    private static WeatherData.WeatherState stateAt(ServerLevel level, BlockPos pos) {
        WeatherData.WeatherState state = stateOrNull(level, pos);
        return state != null ? state : WeatherData.WeatherState.clearDefault();
    }

    private static void send(ServerPlayer player, WeatherData.WeatherState state) {
        // 热移除的类型不能再回落到晴天：那会让 HUD 显示晴天、强度恒 1，而实际雨量仍在
        WeatherType type = WeatherTypes.resolve(player.serverLevel(), state.typeId());
        // 原版雨/雪渲染与雷声都只有一条通道：自定义类型靠 hasPrecipitation/allowsThunder 决定是否借用它们。
        boolean raining = type != null && state.isRaining() && type.hasPrecipitation();
        boolean thundering = type != null && state.isThundering() && type.allowsThunder();
        boolean builtin = type != null && WeatherTypes.isBuiltin(state.typeId());
        String typeKey = (type == null || builtin) ? "" : type.displayKey();
        String typeId = (type == null || builtin) ? "" : state.typeId().toString();
        float targetRain = type == null ? 0.0F : state.rainLevel();
        float targetThunder = type == null ? 0.0F : state.thunderLevel();
        // 展示强度：有降水的类型跟随雨量；无降水类型（雾/沙尘暴）按「是否处于天气现象中」给满值
        float intensity = type == null ? 0.0F
                : (type.hasPrecipitation()
                        ? state.rainLevel()
                        : (state.isWeatherActive() ? 1.0F : 0.0F));
        if (type != null) {
            // 数据包定义的伤害/移速效果（只在片模式经过这里）
            applyEffects(player, player.serverLevel(), state, type);
        } else {
            WeatherEffects.clear(player);   // 类型被移除后残留的移速/伤害不能继续挂着
        }

        SentState last = LAST_SENT.get(player.getUUID());
        if (last == null) {
            // 首次同步直接给目标值，避免刚进服就看到一段无意义的渐变
            LAST_SENT.put(player.getUUID(),
                    new SentState(raining, thundering, typeKey, typeId, intensity, targetRain, targetThunder));
            player.connection.send(new ClientboundGameEventPacket(eventType(raining), 0.0F));
            player.connection.send(new ClientboundGameEventPacket(ClientboundGameEventPacket.RAIN_LEVEL_CHANGE, targetRain));
            player.connection.send(new ClientboundGameEventPacket(ClientboundGameEventPacket.THUNDER_LEVEL_CHANGE, targetThunder));
            // HUD（Xaero 天气行）与客户端视觉层要的是目标值，不能等 5 秒渐变
            player.connection.send(new WeatherStatePayload(raining, thundering, typeKey, typeId, intensity));
            return;
        }

        float rainLevel = approach(last.rainLevel, targetRain);
        float thunderLevel = approach(last.thunderLevel, targetThunder);
        boolean changed = rainLevel != last.rainLevel || thunderLevel != last.thunderLevel;
        boolean targetChanged = last.raining != raining
                || last.thundering != thundering
                || !last.typeKey.equals(typeKey)
                || !last.typeId.equals(typeId)
                // 天气刚开始时 raining 先翻转、强度往往还接近 0；不比较强度的话之后爬升不再发包，
                // 片模式下降水型自定义天气的客户端强度会永远停在首个快照值（几乎看不到粒子）
                || Math.abs(last.intensity - intensity) > 0.005F;

        last.rainLevel = rainLevel;
        last.thunderLevel = thunderLevel;

        if (last.raining != raining) {
            // START/STOP 会把客户端雨量强制改成 0/1，随后的 level 包会立即纠正
            last.raining = raining;
            player.connection.send(new ClientboundGameEventPacket(eventType(raining), 0.0F));
            player.connection.send(new ClientboundGameEventPacket(ClientboundGameEventPacket.RAIN_LEVEL_CHANGE, rainLevel));
            player.connection.send(new ClientboundGameEventPacket(ClientboundGameEventPacket.THUNDER_LEVEL_CHANGE, thunderLevel));
        } else if (changed) {
            player.connection.send(new ClientboundGameEventPacket(ClientboundGameEventPacket.RAIN_LEVEL_CHANGE, rainLevel));
            player.connection.send(new ClientboundGameEventPacket(ClientboundGameEventPacket.THUNDER_LEVEL_CHANGE, thunderLevel));
        }

        if (targetChanged) {
            // 只给 HUD 与客户端视觉层用；上面的雨量/雷量包仍按 0.01/tick 平滑，渲染保持原样
            last.thundering = thundering;
            last.typeKey = typeKey;
            last.typeId = typeId;
            last.intensity = intensity;
            player.connection.send(new WeatherStatePayload(raining, thundering, typeKey, typeId, intensity));
        }
    }

    private static float approach(float current, float target) {
        if (current < target) {
            return Math.min(target, current + LEVEL_STEP);
        }
        if (current > target) {
            return Math.max(target, current - LEVEL_STEP);
        }
        return current;
    }

    private static ClientboundGameEventPacket.Type eventType(boolean raining) {
        return raining ? ClientboundGameEventPacket.START_RAINING : ClientboundGameEventPacket.STOP_RAINING;
    }

    private record LookupCell(String patch, WeatherBiomeFilter.Decision decision) {}

    private static final class CellLookup {
        private final Map<Long, LookupCell> cells = new LinkedHashMap<>(256, 0.75F, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Long, LookupCell> eldest) {
                return size() > MAX_LOOKUP_CELLS;
            }
        };
        private WeatherData data;
    }

    private static final class SentState {
        private boolean raining;
        private boolean thundering;
        /** 自定义天气的 HUD 显示键；内置类型/晴天为空串。 */
        private String typeKey;
        /** 自定义天气的类型 id；内置类型/晴天为空串（用于客户端视觉层解析粒子/音效）。 */
        private String typeId;
        /** 展示强度：有降水类型跟随雨量，无降水类型是 0/1。 */
        private float intensity;
        /** 上一次发给客户端的（平滑后）雨量/雷量，用来只在变化时发包。 */
        private float rainLevel;
        private float thunderLevel;

        private SentState(boolean raining, boolean thundering, String typeKey, String typeId, float intensity,
                          float rainLevel, float thunderLevel) {
            this.raining = raining;
            this.thundering = thundering;
            this.typeKey = typeKey;
            this.typeId = typeId;
            this.intensity = intensity;
            this.rainLevel = rainLevel;
            this.thunderLevel = thunderLevel;
        }
    }
}
