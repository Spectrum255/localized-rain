package com.spectrum255.localizedrain.mixin;

import com.spectrum255.localizedrain.weather.WeatherManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerLevel.class)
public abstract class ServerLevelWeatherMixin {
    @Inject(method = "advanceWeatherCycle", at = @At("HEAD"), cancellable = true)
    private void localizedrain$advanceRegionalWeather(CallbackInfo callback) {
        WeatherManager.tick((ServerLevel) (Object) this);
        callback.cancel();
    }

    @Inject(method = "tickPrecipitation",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;isRaining()Z"),
            cancellable = true)
    private void localizedrain$filterPrecipitation(BlockPos pos, CallbackInfo callback) {
        // 结冰判定发生在这个调用点之前，取消降水不会影响冰/雪层的冻结逻辑。
        // 这里只看「该区域是否在下雨」，降水类型交给原版按群系判断。
        if (!WeatherManager.isPrecipitatingAt((ServerLevel) (Object) this, pos)) {
            callback.cancel();
        }
    }

    @Inject(method = "resetWeatherCycle", at = @At("HEAD"))
    private void localizedrain$clearRegionalWeather(CallbackInfo callback) {
        // 原版只清 serverLevelData，而那个字段已经没人读取了，这里同步清掉区域天气
        WeatherManager.clearAll((ServerLevel) (Object) this);
    }

    @Inject(method = "setWeatherParameters", at = @At("HEAD"))
    private void localizedrain$applyGlobalWeather(int clearTime, int weatherTime, boolean raining, boolean thundering,
                                                  CallbackInfo callback) {
        // 其他模组/测试框架仍可能调用原版全局天气接口，转发成「已记录的所有区域一起变天」
        WeatherManager.setAll((ServerLevel) (Object) this, raining, thundering, raining ? weatherTime : clearTime);
    }

    @Redirect(
            method = "tickChunk",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;isRainingAt(Lnet/minecraft/core/BlockPos;)Z"))
    private boolean localizedrain$filterLightning(ServerLevel level, BlockPos pos) {
        // 原版雷击只看维度级天气，这里收紧到目标坐标所在的群系范围；
        // 只在实际落雷区域放行，避免晴天区域被落雷。真正的落雷由 WeatherManager#spawnLightning 负责。
        return WeatherManager.isThunderingAt(level, pos);
    }
}
