package com.spectrum255.localizedrain.mixin;

import com.spectrum255.localizedrain.client.WeatherSky;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.CubicSampler;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 数据包天空盒覆盖：只改原版取值，不改渲染流程。
 *
 * <ul>
 *   <li>{@code getSkyColor}：把高斯采样出来的群系基色换成 {@code sky_color} 的颜色；</li>
 *   <li>{@code getCloudColor} / {@code getStarBrightness}：在返回值上叠加云色调与星星亮度乘数。</li>
 * </ul>
 *
 * <p>群系基色的调用点在 lambda 里（不能在外层方法 redirect {@code Biome#getSkyColor}），
 * 所以这里 redirect 外层方法里的 {@code CubicSampler.gaussianSampleVec3} 调用，
 * 先取原版采样结果再与自定义颜色插值。</p>
 */
@Mixin(ClientLevel.class)
public abstract class ClientLevelMixin {
    @Redirect(
            method = "getSkyColor",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/util/CubicSampler;gaussianSampleVec3(Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/util/CubicSampler$Vec3Fetcher;)Lnet/minecraft/world/phys/Vec3;"))
    private static Vec3 localizedrain$skyBaseColor(Vec3 pos, CubicSampler.Vec3Fetcher fetcher) {
        return WeatherSky.skyBaseColor(CubicSampler.gaussianSampleVec3(pos, fetcher));
    }

    @Inject(method = "getCloudColor", at = @At("RETURN"), cancellable = true)
    private void localizedrain$cloudColor(float partialTick, CallbackInfoReturnable<Vec3> callback) {
        callback.setReturnValue(WeatherSky.cloudColor(callback.getReturnValue()));
    }

    @Inject(method = "getStarBrightness", at = @At("RETURN"), cancellable = true)
    private void localizedrain$starBrightness(float partialTick, CallbackInfoReturnable<Float> callback) {
        callback.setReturnValue(WeatherSky.starBrightness(callback.getReturnValue()));
    }
}
