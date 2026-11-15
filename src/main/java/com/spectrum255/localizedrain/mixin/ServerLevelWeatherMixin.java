package com.spectrum255.localizedrain.mixin;

import com.spectrum255.localizedrain.weather.WeatherManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.Heightmap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerLevel.class)
public abstract class ServerLevelWeatherMixin {
    @Inject(method = "advanceWeatherCycle", at = @At("HEAD"), cancellable = true)
    private void localizedrain$advanceRegionalWeather(CallbackInfo callback) {
        if (WeatherManager.vanillaMode()) {
            return;                     // 放行原版 advanceWeatherCycle
        }
        WeatherManager.tick((ServerLevel) (Object) this);
        callback.cancel();
    }

    @Redirect(method = "tickPrecipitation",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;isRaining()Z"))
    private boolean localizedrain$localizedRaining(ServerLevel level, BlockPos pos) {
        if (WeatherManager.vanillaMode()) {
            return level.isRaining();
        }
        // 只替换 tickPrecipitation 内的这一个调用点：积雪（shouldSnow）与炼药锅集水（handlePrecipitation）
        // 按区域恢复；而 Level#isRaining() 对第三方仍然是晴天（不再写回维度级 rainLevel）。
        // 原版入参是 tickChunk 的随机方块坐标（y 恒为 0–15），真正用于放置雪/炼药锅的是方法内部算出的
        // 高度图顶端；这里先换算，再用原始坐标兜底 BAND 洞穴风暴。结冰判定发生在这个调用点之前，不受影响。
        BlockPos top = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, pos);
        if (WeatherManager.isPrecipitatingAt(level, top)) {
            return true;
        }
        // 原始坐标只对移动模式的 BAND/COLUMN 风暴有意义：片模式下它位于地表以下，
        // 洞片段降水不应让地表积雪/炼药锅集水。
        return WeatherManager.mobileMode() && WeatherManager.isPrecipitatingAt(level, pos);
    }

    @Redirect(method = "tick",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;isRaining()Z"))
    private boolean localizedrain$localizedSleepGate(ServerLevel level) {
        if (WeatherManager.vanillaMode()) {
            return level.isRaining();
        }
        // 原版睡眠块用维度级 isRaining() 决定是否 resetWeatherCycle；本模组不再写回 rainLevel，
        // 这里接上局部天气，睡觉才会清掉区域天气（resetWeatherCycle 的注入因此可达）。
        return level.isRaining() || WeatherManager.hasLocalPrecipitation(level);
    }

    @Inject(method = "resetWeatherCycle", at = @At("HEAD"), cancellable = true)
    private void localizedrain$clearRegionalWeather(CallbackInfo callback) {
        if (WeatherManager.vanillaMode()) {
            return;
        }
        // 原版只清 serverLevelData，而那个字段已经没人读取了，这里同步清掉区域天气
        WeatherManager.clearAll((ServerLevel) (Object) this);
        if (WeatherManager.mobileMode()) {
            callback.cancel();          // 移动模式下不能再让原版写 serverLevelData 并发晴雨包
        }
    }

    @Inject(method = "setWeatherParameters", at = @At("HEAD"), cancellable = true)
    private void localizedrain$applyGlobalWeather(int clearTime, int weatherTime, boolean raining, boolean thundering,
                                                  CallbackInfo callback) {
        if (WeatherManager.vanillaMode()) {
            return;                     // 原版自己写 serverLevelData
        }
        // 其他模组/测试框架仍可能调用原版全局天气接口，转发成「已记录的所有区域一起变天」
        WeatherManager.setAll((ServerLevel) (Object) this, raining, thundering, raining ? weatherTime : clearTime);
        if (WeatherManager.mobileMode()) {
            callback.cancel();
        }
    }

    @Redirect(
            method = "tickChunk",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;isRainingAt(Lnet/minecraft/core/BlockPos;)Z"))
    private boolean localizedrain$filterLightning(ServerLevel level, BlockPos pos) {
        if (WeatherManager.vanillaMode()) {
            return level.isRainingAt(pos);   // 直通原版判定（LevelWeatherMixin 此时也直通）
        }
        if (WeatherManager.mobileMode()) {
            // 移动模式的落雷由 StormManager 自己抽签并生成，放行原版会与之叠加
            return false;
        }
        // 原版雷击只看维度级天气，这里收紧到目标坐标所在的群系范围；
        // 只在实际落雷区域放行，避免晴天区域被落雷。真正的落雷由 WeatherManager#spawnLightning 负责。
        return WeatherManager.isThunderingAt(level, pos);
    }
}
