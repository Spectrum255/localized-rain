package com.spectrum255.localizedrain.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import com.spectrum255.localizedrain.client.ClientWeatherVisuals;
import com.spectrum255.localizedrain.client.WeatherSky;
import net.minecraft.client.renderer.DimensionSpecialEffects;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 客户端天空渲染钩子：
 * <ul>
 *   <li>自定义天气带自有粒子时取消原版雨雪，避免「原版雨 + 自定义粒子」叠加；</li>
 *   <li>按数据包字段隐藏日月（把贴图换成全透明）与替换日出/日落颜色。</li>
 * </ul>
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

    /** 太阳方片：{@code renderSky} 里第一处 {@code setShaderTexture}（ordinal 0）。 */
    @Redirect(
            method = "renderSky",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/systems/RenderSystem;setShaderTexture(ILnet/minecraft/resources/ResourceLocation;)V",
                    ordinal = 0))
    private static void localizedrain$sunTexture(int shaderTexture, ResourceLocation texture) {
        RenderSystem.setShaderTexture(shaderTexture, WeatherSky.sunTexture(texture));
    }

    /** 月亮方片：{@code renderSky} 里第二处 {@code setShaderTexture}（ordinal 1）。 */
    @Redirect(
            method = "renderSky",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/systems/RenderSystem;setShaderTexture(ILnet/minecraft/resources/ResourceLocation;)V",
                    ordinal = 1))
    private static void localizedrain$moonTexture(int shaderTexture, ResourceLocation texture) {
        RenderSystem.setShaderTexture(shaderTexture, WeatherSky.moonTexture(texture));
    }

    /** 日出/日落扇形的颜色；原版返回 null（不该画）时保持 null。 */
    @Redirect(
            method = "renderSky",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/DimensionSpecialEffects;getSunriseColor(FF)[F"))
    private float[] localizedrain$sunriseColor(DimensionSpecialEffects effects, float timeOfDay, float partialTicks) {
        return WeatherSky.sunriseColor(effects.getSunriseColor(timeOfDay, partialTicks));
    }
}
