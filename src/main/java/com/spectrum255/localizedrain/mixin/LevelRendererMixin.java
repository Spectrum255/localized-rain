package com.spectrum255.localizedrain.mixin;

import com.spectrum255.localizedrain.client.ClientWeatherVisuals;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 自定义天气带自有粒子时，取消原版雨雪粒子，避免「原版雨 + 自定义粒子」叠加。
 * 只挂在客户端渲染阶段，不影响服务端降水判定。
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {
    @Inject(method = "renderSnowAndRain", at = @At("HEAD"), cancellable = true)
    private void localizedrain$cancelVanillaRain(CallbackInfo callbackInfo) {
        if (ClientWeatherVisuals.suppressVanillaRain()) {
            callbackInfo.cancel();
        }
    }
}
