package com.spectrum255.localizedrain.mixin;

import com.spectrum255.localizedrain.weather.WeatherManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Level.class)
public abstract class LevelWeatherMixin {
    @Inject(method = "isRainingAt", at = @At("HEAD"), cancellable = true)
    private void localizedrain$filterRainAt(BlockPos pos, CallbackInfoReturnable<Boolean> callback) {
        // 服务端整体接管：用局部区域天气，但保留原版的见天/遮挡/降水类型判断
        if ((Object) this instanceof ServerLevel level) {
            callback.setReturnValue(WeatherManager.isRainingAt(level, pos));
        }
    }
}
