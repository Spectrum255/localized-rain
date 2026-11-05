package com.spectrum255.localizedrain.weather;

import com.spectrum255.localizedrain.network.WeatherStatePayload;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundGameEventPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

public final class WeatherManager {
    /** 原版 tickChunk 里雷击抽签的上限值，1/100000 每区块每 tick。 */
    public static final int LIGHTNING_ROLL_BASE = 100_000;
    /** 每个 tick 让客户端显示的雨量/雷量向目标靠近的步长，和原版的 0.01/tick 一致。 */
    private static final float LEVEL_STEP = 0.01F;

    /** 每个维度一份 SavedData，避免在降水/查询这类热点路径上反复走 DimensionDataStorage。 */
    private static final Map<ServerLevel, WeatherData> DATA_CACHE = new WeakHashMap<>();
    /** 每个玩家的客户端显示状态（含平滑中的雨量/雷量）。 */
    private static final Map<UUID, SentState> LAST_SENT = new HashMap<>();

    /** 天空亮度公式里跟时间相关的部分，每 tick 刷新一次，避免光照查询里反复算 cos。 */
    private static ServerLevel dayFactorLevel;
    private static double dayFactorValue = 1.0;

    private WeatherManager() {}

    public static void tick(ServerLevel level) {
        // 原版只在天光维度推进雨量；下界/末地直接跳过
        if (!level.dimensionType().hasSkyLight()) {
            return;
        }

        WeatherData data = data(level);
        boolean cycleWeather = level.getGameRules().getBoolean(GameRules.RULE_WEATHER_CYCLE);

        // 只推进玩家所在的那一片群系；玩家换了片才需要重新算轮廓
        Map<UUID, String> patchByPlayer = new HashMap<>();
        Set<String> patches = new HashSet<>();
        for (ServerPlayer player : level.players()) {
            String patch = BiomePatches.patchAt(level, player.blockPosition());
            patchByPlayer.put(player.getUUID(), patch);
            patches.add(patch);
        }
        data.tick(level.getRandom(), cycleWeather, patches);
        refreshDayFactor(level);

        float maximumRain = 0.0F;
        float maximumThunder = 0.0F;
        for (String patch : patches) {
            WeatherData.WeatherState state = data.getOrCreate(patch, level.getRandom());
            maximumRain = Math.max(maximumRain, state.rainLevel());
            maximumThunder = Math.max(maximumThunder, state.thunderLevel());
        }
        level.setRainLevel(maximumRain);
        level.setThunderLevel(maximumThunder);
        spawnLightning(level, patchByPlayer, data);

        for (ServerPlayer player : level.players()) {
            String patch = patchByPlayer.get(player.getUUID());
            if (patch != null) {
                send(player, data.getOrCreate(patch, level.getRandom()));
            }
        }
    }

    /** 取（必要时计算）该位置所在群系范围的标识。 */
    public static String patchAt(ServerLevel level, BlockPos pos) {
        return BiomePatches.patchAt(level, pos);
    }

    /** 原版 isRainingAt 的完整语义：局部天气 + 头顶无遮挡 + 未被方块挡住 + 该群系确实降的是雨。 */
    public static boolean isRainingAt(ServerLevel level, BlockPos pos) {
        return level.dimensionType().hasSkyLight()
                && stateAt(level, pos).isRaining()
                && canPrecipitateAt(level, pos);
    }

    /** 降水（积雪、炼药锅、水结冰）只看局部天气，降水类型由原版按群系自行判断。 */
    public static boolean isPrecipitatingAt(ServerLevel level, BlockPos pos) {
        return level.dimensionType().hasSkyLight() && stateAt(level, pos).isRaining();
    }

    public static boolean isThunderingAt(ServerLevel level, BlockPos pos) {
        return level.dimensionType().hasSkyLight()
                && !level.dimensionType().hasCeiling()
                && stateAt(level, pos).isThundering()
                && canPrecipitateAt(level, pos);
    }

    /** 该位置是否真的能淋到雨：头顶见天、高度图上方没有遮挡、且群系降水类型是雨。 */
    private static boolean canPrecipitateAt(ServerLevel level, BlockPos pos) {
        return level.canSeeSky(pos)
                && level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, pos).getY() <= pos.getY()
                && level.getBiome(pos).value().getPrecipitationAt(pos) == Biome.Precipitation.RAIN;
    }

    /**
     * 该位置所属范围是否处于雷暴（对应原版维度级的 isThundering()，但不做见天判断）。
     * 原版刷怪判定用它决定要不要额外减 10 点光照，必须按位置而不是整个维度来算。
     */
    public static boolean isThunderingRegionAt(ServerLevel level, BlockPos pos) {
        return level.dimensionType().hasSkyLight()
                && !level.dimensionType().hasCeiling()
                && stateAt(level, pos).isThundering();
    }

    /**
     * 返回该位置所在群系片当前的雷量（0.0F–1.0F），供外部模组读取（如气象协议）。
     *
     * <p>只读：不会创建天气状态、不会触发群系轮廓计算、不会加载区块；没有记录的区域、没有天光的维度，
     * 以及数值异常（NaN/Infinity）都返回 {@code 0.0F}。远处没有被 tick 的群系片会停在上次 tick 时的值。</p>
     *
     * <p>不做见天判断、不做降水门控：即使该处没有下雨，也返回存储的雷量。与
     * {@link #isThunderingRegionAt(ServerLevel, BlockPos)} 保持一致——后者为 true 时本方法必然大于 0.9。</p>
     */
    public static float thunderLevelAt(ServerLevel level, BlockPos pos) {
        if (level == null || pos == null || !level.dimensionType().hasSkyLight()) {
            return 0.0F;
        }
        WeatherData.WeatherState state = stateOrNull(level, pos);
        if (state == null) {
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
        WeatherData.WeatherState state = stateOrNull(level, pos);
        float rain = state == null ? 0.0F : state.rainLevel();
        float thunder = state == null ? 0.0F : state.thunderLevel();
        double rainFactor = 1.0 - (double) (rain * 5.0F) / 16.0;
        double thunderFactor = 1.0 - (double) (thunder * 5.0F) / 16.0;
        return (int) ((1.0 - dayFactor(level) * rainFactor * thunderFactor) * 11.0);
    }

    /** 已经记录过的所有范围（探索过的），用于 global 与全局天气兼容层。 */
    public static Set<String> allPatches(ServerLevel level) {
        return Set.copyOf(data(level).patches());
    }

    public static void setWeather(ServerLevel level, Collection<String> patches,
                                  WeatherData.WeatherType weather, int duration) {
        if (!level.dimensionType().hasSkyLight()) {
            return;
        }
        data(level).setWeather(patches, weather, duration, level.getRandom());
    }

    /** 兼容层：把全局的 setWeatherParameters 调用翻译成「已记录的所有范围一起变天」。 */
    public static void setAll(ServerLevel level, boolean raining, boolean thundering, int duration) {
        if (!level.dimensionType().hasSkyLight()) {
            return;
        }
        WeatherData.WeatherType weather = !raining
                ? WeatherData.WeatherType.CLEAR
                : (thundering ? WeatherData.WeatherType.THUNDER : WeatherData.WeatherType.RAIN);
        setWeather(level, allPatches(level), weather, duration <= 0 ? -1 : duration);
    }

    /** 跳过夜晚时调用：清空本维度所有范围的降雨/雷暴。 */
    public static void clearAll(ServerLevel level) {
        data(level).clearAll(level.getRandom());
    }

    /** 玩家的客户端状态会被重建（登录/跨维度/重生），清掉缓存以便重新推送一次完整天气。 */
    public static void forget(UUID playerId) {
        LAST_SENT.remove(playerId);
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
            if (data.getOrCreate(patch, random).isThundering()) {
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
            LightningBolt bolt = EntityType.LIGHTNING_BOLT.create(level);
            if (bolt == null) {
                continue;
            }
            bolt.moveTo(Vec3.atBottomCenterOf(pos));
            bolt.setVisualOnly(false);
            level.addFreshEntity(bolt);
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

    private static WeatherData data(ServerLevel level) {
        WeatherData data = DATA_CACHE.get(level);
        if (data == null) {
            data = level.getDataStorage().computeIfAbsent(WeatherData.factory(), WeatherData.FILE_ID);
            DATA_CACHE.put(level, data);
        }
        return data;
    }

    private static WeatherData.WeatherState stateOrNull(ServerLevel level, BlockPos pos) {
        String patch = BiomePatches.cachedPatchAt(level, pos);
        return patch == null ? null : data(level).get(patch);
    }

    /** 只读查询：没有记录的位置按晴天处理，不会创建存档条目，也不会触发轮廓计算。 */
    private static WeatherData.WeatherState stateAt(ServerLevel level, BlockPos pos) {
        WeatherData.WeatherState state = stateOrNull(level, pos);
        return state != null ? state : WeatherData.WeatherState.clearDefault();
    }

    private static void send(ServerPlayer player, WeatherData.WeatherState state) {
        boolean raining = state.isRaining();
        boolean thundering = state.isThundering();
        float targetRain = state.rainLevel();
        float targetThunder = state.thunderLevel();

        SentState last = LAST_SENT.get(player.getUUID());
        if (last == null) {
            // 首次同步直接给目标值，避免刚进服就看到一段无意义的渐变
            LAST_SENT.put(player.getUUID(), new SentState(raining, thundering, targetRain, targetThunder));
            player.connection.send(new ClientboundGameEventPacket(eventType(raining), 0.0F));
            player.connection.send(new ClientboundGameEventPacket(ClientboundGameEventPacket.RAIN_LEVEL_CHANGE, targetRain));
            player.connection.send(new ClientboundGameEventPacket(ClientboundGameEventPacket.THUNDER_LEVEL_CHANGE, targetThunder));
            // HUD（Xaero 天气行）要的是目标值，不能等 5 秒渐变
            player.connection.send(new WeatherStatePayload(raining, thundering));
            return;
        }

        float rainLevel = approach(last.rainLevel, targetRain);
        float thunderLevel = approach(last.thunderLevel, targetThunder);
        boolean changed = rainLevel != last.rainLevel || thunderLevel != last.thunderLevel;
        boolean targetChanged = last.raining != raining || last.thundering != thundering;

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
            // 只给 HUD 用；上面的雨量/雷量包仍按 0.01/tick 平滑，渲染保持原样
            last.thundering = thundering;
            player.connection.send(new WeatherStatePayload(raining, thundering));
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

    private static final class SentState {
        private boolean raining;
        private boolean thundering;
        /** 上一次发给客户端的（平滑后）雨量/雷量，用来只在变化时发包。 */
        private float rainLevel;
        private float thunderLevel;

        private SentState(boolean raining, boolean thundering, float rainLevel, float thunderLevel) {
            this.raining = raining;
            this.thundering = thundering;
            this.rainLevel = rainLevel;
            this.thunderLevel = thunderLevel;
        }
    }
}
