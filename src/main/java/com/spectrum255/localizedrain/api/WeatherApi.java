package com.spectrum255.localizedrain.api;

import com.spectrum255.localizedrain.Config;
import com.spectrum255.localizedrain.storm.StormManager;
import com.spectrum255.localizedrain.storm.WindField;
import com.spectrum255.localizedrain.weather.WeatherBiomeFilter;
import com.spectrum255.localizedrain.weather.WeatherManager;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;

/**
 * 本模组对外的天气查询/设置入口。
 *
 * <p>所有查询都是按“位置”解析的：同一个群系片内共享天气，群系片按 3D 群系段
 * 的连通分量划分（见 {@code BiomePatches}）。查询走缓存、不加载区块；
 * {@link #typeAt} 在缓存未命中时会按需解析一次该位置所在的列，解析不到的位置按晴天处理。
 * 该解析不受每 tick 列扫描预算限制（避免预算耗尽时短暂读到晴天），所以只适合低频调用：
 * 不要每 tick 对成百上千个不同坐标调用 {@link #typeAt}，批量读取请先 {@link #patches} / {@link #patchAt} 复用结果。</p>
 *
 * <p>写操作同样按群系片生效：手动设置会覆盖白名单/黑名单（名单只约束自然循环），
 * 但会跳过被配置强制天气锁定的片。</p>
 */
public final class WeatherApi {
    private WeatherApi() {}

    /**
     * 该位置当前天气类型；缓存未命中时会现场解析一次（可能触发一次列扫描），无记录返回晴天。
     *
     * <p>冷路径不受扫描预算限制，适合低频查询；不要对大量坐标高频调用。</p>
     */
    public static WeatherType typeAt(ServerLevel level, BlockPos pos) {
        return WeatherManager.weatherTypeAt(level, pos);
    }

    /** 该位置所在群系片的标识（不存在时返回 null）。 */
    public static String patchAt(ServerLevel level, BlockPos pos) {
        return WeatherManager.patchAt(level, pos);
    }

    /** 该位置是否正在下雨（按天气类型、见天、遮挡与群系降水判定）。 */
    public static boolean isRainingAt(ServerLevel level, BlockPos pos) {
        return WeatherManager.isRainingAt(level, pos);
    }

    /** 该位置是否在降水（用于积雪/炼药锅等，不要求群系降水类型为雨）。 */
    public static boolean isPrecipitatingAt(ServerLevel level, BlockPos pos) {
        return WeatherManager.isPrecipitatingAt(level, pos);
    }

    /** 该位置是否处于雷暴（含见天与遮挡判定）。 */
    public static boolean isThunderingAt(ServerLevel level, BlockPos pos) {
        return WeatherManager.isThunderingAt(level, pos);
    }

    /** 该位置所在群系片是否处于雷暴（不做见天判断，供刷怪/光照使用）。 */
    public static boolean isThunderingRegionAt(ServerLevel level, BlockPos pos) {
        return WeatherManager.isThunderingRegionAt(level, pos);
    }

    /** 该位置的雷量（0–1）；无记录返回 0。 */
    public static float thunderLevelAt(ServerLevel level, BlockPos pos) {
        return WeatherManager.thunderLevelAt(level, pos);
    }

    /** 该位置的天空变暗值（0–11），已按局部天气计算。 */
    public static int skyDarkenAt(ServerLevel level, BlockPos pos) {
        return WeatherManager.skyDarkenAt(level, pos);
    }

    /** 已经记录过天气的群系片。 */
    public static Set<String> patches(ServerLevel level) {
        return WeatherManager.allPatches(level);
    }

    /**
     * 把一批群系片的天气设为指定类型；duration 为 tick 数，{@code < 0} 表示使用该类型的随机区间。
     * 只对已存在/有玩家的群系片生效。白名单/黑名单只约束自然循环，手动设置可以覆盖它们；
     * 被强制天气锁定的片会被跳过。设置的状态记录为手动覆盖，计时结束后回到晴天，
     * 再由名单决定是否重新参与自然循环。
     */
    public static void setWeather(ServerLevel level, Collection<String> patches, ResourceLocation typeId, int duration) {
        WeatherManager.setWeather(level, patches, typeId, duration);
    }

    /**
     * 把该位置所在的群系片设为指定天气（{@link #setWeather} 的坐标版）。
     *
     * <p>移动模式下：{@code clear} 只按列移除覆盖该位置的风暴，不使用半径；
     * 其它类型按档案半径区间生成一个手动风暴，若该位置被强制晴天锁定或没有匹配的档案则返回 {@code false}。</p>
     *
     * @return 位置无对应群系片（世界外或解析失败）、被强制晴天锁定、或没有匹配档案时返回 {@code false}
     */
    public static boolean setWeatherAt(ServerLevel level, BlockPos pos, ResourceLocation typeId, int duration) {
        if (WeatherMode.current() == WeatherMode.MOBILE) {
            if (WeatherTypes.CLEAR_ID.equals(typeId)) {
                // clear 不是「生成晴天风暴」，而是移除覆盖该位置的风暴：不生成、也不使用半径
                StormManager.clearAtColumn(level, pos, 0.0D);
                return true;
            }
            // 与命令一致：只有强制晴天阻止手动设置
            if (WeatherBiomeFilter.manualBlockedInMobile(level, pos)) {
                return false;
            }
            // 无天光维度不传半径：让档案自己的区间生效（cave_rain 是 48–96）
            int radius = StormManager.allowsManualStorms(level)
                    ? WindField.intConfig(Config.MANUAL_STORM_RADIUS, 128) : 0;
            return StormManager.spawnManual(level, pos, typeId, radius, duration) != null;
        }
        String patch = WeatherManager.patchAt(level, pos);
        if (patch == null) {
            return false;
        }
        WeatherManager.setWeather(level, List.of(patch), typeId, duration);
        return true;
    }

    // ---- 移动天气：查询 ----
    /** 当前天气接管模式。 */
    public static WeatherMode weatherMode() { return WeatherMode.current(); }

    /** 覆盖该坐标的全部风暴快照（不含群系隔离判定）。 */
    public static List<StormSnapshot> stormsAt(ServerLevel level, BlockPos pos) {
        List<StormSnapshot> result = new ArrayList<>();
        double px = pos.getX() + 0.5D;
        double py = pos.getY() + 0.5D;
        double pz = pos.getZ() + 0.5D;
        for (StormSnapshot snapshot : StormManager.snapshots(level)) {
            if (snapshot.covers(px, py, pz)) {
                result.add(snapshot);
            }
        }
        return result;
    }

    /** 该维度全部风暴快照。 */
    public static List<StormSnapshot> listStorms(ServerLevel level) {
        return StormManager.snapshots(level);
    }

    /** 按 id 取风暴快照，不存在返回 null。 */
    public static StormSnapshot storm(ServerLevel level, UUID id) {
        return StormManager.snapshotById(level, id);
    }

    /** 该维度当前风场。 */
    public static WindState wind(ServerLevel level) { return StormManager.wind(level); }

    /** 该位置是否处于风暴隔离群系。 */
    public static boolean isStormIsolatedAt(ServerLevel level, BlockPos pos) {
        return StormManager.isIsolatedAt(level, pos);
    }

    // ---- 移动天气：写入 ----
    /** 生成风暴；档案缺失或被事件取消时返回 null。 */
    public static StormSnapshot spawnStorm(ServerLevel level, StormSpawnSpec spec) {
        return StormManager.spawn(level, spec);
    }

    /** 按 id 移除风暴。 */
    public static boolean removeStorm(ServerLevel level, UUID id) {
        return StormManager.remove(level, id);
    }

    /** 按快照谓词批量移除，返回移除数量。 */
    public static int removeStorms(ServerLevel level, java.util.function.Predicate<StormSnapshot> filter) {
        return StormManager.removeWhere(level, filter);
    }

    public static boolean advanceStage(ServerLevel level, UUID id) { return StormManager.advanceStage(level, id); }
    public static boolean setStage(ServerLevel level, UUID id, int index) { return StormManager.setStage(level, id, index); }
    public static boolean setVelocity(ServerLevel level, UUID id, double x, double z) { return StormManager.setVelocity(level, id, x, z); }
    public static boolean setIntensity(ServerLevel level, UUID id, float rain, float thunder) { return StormManager.setIntensity(level, id, rain, thunder); }
    public static void setWind(ServerLevel level, double x, double z) { StormManager.setWind(level, x, z); }
}
