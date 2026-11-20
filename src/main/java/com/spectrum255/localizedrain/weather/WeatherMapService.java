package com.spectrum255.localizedrain.weather;

import com.spectrum255.localizedrain.api.StormSnapshot;
import com.spectrum255.localizedrain.api.WeatherApi;
import com.spectrum255.localizedrain.api.WeatherMode;
import com.spectrum255.localizedrain.api.WindState;
import com.spectrum255.localizedrain.network.WeatherMapPayload;
import com.spectrum255.localizedrain.storm.StormManager;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 天气地图（云图）的服务端数据汇总。
 *
 * <p>只响应客户端显式请求，同一玩家 10 tick 内最多一次；只发送玩家附近、确实存在天气的片，
 * 避免大半径下把成千上万个片全发出去。</p>
 */
public final class WeatherMapService {
    /** 云图覆盖半径（方块）：512 格 ≈ 32×32 个片。 */
    public static final int RADIUS = 512;
    /** 同一玩家两次请求的最小间隔（tick）。 */
    private static final int REQUEST_INTERVAL_TICKS = 10;
    /** 限流表上限，超过后整体清空（同时在线玩家不会很多）。 */
    private static final int MAX_TRACKED_PLAYERS = 64;

    private static final Map<UUID, Long> LAST_REQUEST = new ConcurrentHashMap<>();

    private WeatherMapService() {}

    /** 处理客户端的地图请求；服务端线程调用。 */
    public static void handleRequest(ServerPlayer player) {
        if (player == null || player.isRemoved()) {
            return;
        }
        ServerLevel level = player.serverLevel();
        long now = level.getGameTime();
        Long last = LAST_REQUEST.get(player.getUUID());
        if (last != null && now - last < REQUEST_INTERVAL_TICKS) {
            return;
        }
        LAST_REQUEST.put(player.getUUID(), now);
        if (LAST_REQUEST.size() > MAX_TRACKED_PLAYERS) {
            LAST_REQUEST.clear();
            LAST_REQUEST.put(player.getUUID(), now);
        }
        PacketDistributor.sendToPlayer(player, build(level, player, now));
    }

    private static WeatherMapPayload build(ServerLevel level, ServerPlayer player, long now) {
        BlockPos origin = player.blockPosition();
        List<WeatherMapPayload.PatchView> patches = patches(level, origin);
        List<WeatherMapPayload.StormView> storms = storms(level, origin);
        // 风场只在移动模式推进；片/原版模式不画箭头，避免展示无意义的风
        boolean mobile = WeatherMode.current() == WeatherMode.MOBILE;
        WindState wind = mobile ? StormManager.wind(level) : null;
        List<WeatherMapPayload.ForecastView> forecast = forecast(level, origin);
        return new WeatherMapPayload(now, RADIUS,
                wind == null ? 0.0F : (float) wind.velocityX(),
                wind == null ? 0.0F : (float) wind.velocityZ(),
                patches, storms, forecast);
    }

    private static List<WeatherMapPayload.PatchView> patches(ServerLevel level, BlockPos origin) {
        // 只有片模式才有「片」；移动模式与原版模式（片状态已冻结）都不该画片
        if (WeatherMode.current() != WeatherMode.PATCHES) {
            return List.of();
        }
        WeatherData data = WeatherManager.data(level);
        List<WeatherMapPayload.PatchView> result = new ArrayList<>();
        for (String patch : WeatherManager.allPatches(level)) {
            WeatherData.WeatherState state = data.get(patch);
            if (state == null || !state.isWeatherActive()) {
                continue;
            }
            BlockPos center = patchCenter(patch);
            if (center == null) {
                continue;
            }
            double dx = center.getX() - origin.getX();
            double dz = center.getZ() - origin.getZ();
            if (dx * dx + dz * dz > (double) RADIUS * RADIUS) {
                continue;
            }
            WeatherBiomeFilter.Decision decision = WeatherBiomeFilter.decision(level, patch);
            result.add(new WeatherMapPayload.PatchView(
                    BlockPos.asLong(center.getX(), 0, center.getZ()),
                    state.typeId(), state.rainLevel(), state.thunderLevel(),
                    state.rainTimeRemaining(), state.thunderTimeRemaining(),
                    state.isForced(), decision != null && decision.locked()));
        }
        result.sort(Comparator.comparingDouble(view ->
                squaredDistance(view.x(), view.z(), origin.getX(), origin.getZ())));
        if (result.size() > WeatherMapPayload.MAX_PATCHES) {
            return List.copyOf(result.subList(0, WeatherMapPayload.MAX_PATCHES));
        }
        return List.copyOf(result);
    }

    private static List<WeatherMapPayload.StormView> storms(ServerLevel level, BlockPos origin) {
        List<WeatherMapPayload.StormView> result = new ArrayList<>();
        for (StormSnapshot snap : StormManager.snapshots(level)) {
            if (snap.dying()) {
                continue;
            }
            double dx = snap.x() - origin.getX();
            double dz = snap.z() - origin.getZ();
            double limit = RADIUS + snap.radius();
            if (dx * dx + dz * dz > limit * limit) {
                continue;
            }
            result.add(new WeatherMapPayload.StormView(snap.id(), snap.x(), snap.z(),
                    snap.velocityX(), snap.velocityZ(), snap.radius(), snap.weatherType(),
                    snap.rainIntensity(), snap.thunderIntensity(), snap.stageIndex(), snap.stageCount()));
        }
        result.sort(Comparator.comparingDouble(view ->
                squaredDistance(view.x(), view.z(), origin.getX(), origin.getZ())));
        if (result.size() > WeatherMapPayload.MAX_STORMS) {
            return List.copyOf(result.subList(0, WeatherMapPayload.MAX_STORMS));
        }
        return List.copyOf(result);
    }

    private static List<WeatherMapPayload.ForecastView> forecast(ServerLevel level, BlockPos origin) {
        List<WeatherApi.ForecastStep> steps = WeatherManager.forecastAt(level, origin, 1200);
        if (steps.isEmpty()) {
            return List.of();
        }
        List<WeatherMapPayload.ForecastView> result = new ArrayList<>(Math.min(steps.size(),
                WeatherMapPayload.MAX_FORECAST));
        for (WeatherApi.ForecastStep step : steps) {
            if (result.size() >= WeatherMapPayload.MAX_FORECAST) {
                break;
            }
            result.add(new WeatherMapPayload.ForecastView(step.inTicks(), step.typeId(), step.intensity()));
        }
        return List.copyOf(result);
    }

    private static BlockPos patchCenter(String patch) {
        if (patch == null || !patch.startsWith("p3:")) {
            return null;
        }
        String[] parts = patch.substring(3).split(",");
        if (parts.length != 3) {
            return null;
        }
        try {
            return new BlockPos(Integer.parseInt(parts[0]) * 16 + 8, 0,
                    Integer.parseInt(parts[1]) * 16 + 8);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static double squaredDistance(double x, double z, double ox, double oz) {
        double dx = x - ox;
        double dz = z - oz;
        return dx * dx + dz * dz;
    }
}
