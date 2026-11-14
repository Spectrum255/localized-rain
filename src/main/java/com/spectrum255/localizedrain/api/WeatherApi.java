package com.spectrum255.localizedrain.api;

import com.spectrum255.localizedrain.weather.WeatherManager;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;

/**
 * 本模组对外的天气查询/设置入口。
 *
 * <p>所有查询都是按“位置”解析的：同一个群系片内共享天气，群系片按 3D 群系段
 * 的连通分量划分（见 {@code BiomePatches}）。查询走缓存、不加载区块；
 * {@link #typeAt} 在缓存未命中时会按需解析一次该位置所在的列，解析不到的位置按晴天处理。</p>
 *
 * <p>写操作同样按群系片生效，且会遵循配置里的黑白名单与强制天气规则。</p>
 */
public final class WeatherApi {
    private WeatherApi() {}

    /** 该位置当前天气类型；缓存未命中时会现场解析一次（可能触发轮廓计算），无记录返回晴天。 */
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
     * 只对已存在/有玩家的群系片生效；被黑白名单或强制天气排除的片会被跳过。
     */
    public static void setWeather(ServerLevel level, Collection<String> patches, ResourceLocation typeId, int duration) {
        WeatherManager.setWeather(level, patches, typeId, duration);
    }

    /**
     * 把该位置所在的群系片设为指定天气（{@link #setWeather} 的坐标版）。
     *
     * @return 位置无对应群系片（世界外或解析失败）时返回 {@code false}
     */
    public static boolean setWeatherAt(ServerLevel level, BlockPos pos, ResourceLocation typeId, int duration) {
        String patch = WeatherManager.patchAt(level, pos);
        if (patch == null) {
            return false;
        }
        WeatherManager.setWeather(level, List.of(patch), typeId, duration);
        return true;
    }
}
