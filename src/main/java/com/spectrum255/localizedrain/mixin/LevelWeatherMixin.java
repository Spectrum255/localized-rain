package com.spectrum255.localizedrain.mixin;

import com.spectrum255.localizedrain.weather.CompatWeatherQueries;
import com.spectrum255.localizedrain.weather.WeatherManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Level.class)
public abstract class LevelWeatherMixin {
    @Inject(method = "isRainingAt", at = @At("HEAD"), cancellable = true)
    private void localizedrain$filterRainAt(BlockPos pos, CallbackInfoReturnable<Boolean> callback) {
        // 先用 instanceof 排除客户端：客户端的 Level 不应读服务端天气配置（配置未加载时也会走异常路径）
        if (!((Object) this instanceof ServerLevel level)) {
            return;
        }
        if (WeatherManager.vanillaMode()) {
            return;
        }
        // 服务端整体接管：用局部区域天气，但保留原版的见天/遮挡/降水类型判断
        callback.setReturnValue(WeatherManager.isRainingAt(level, pos));
    }

    /**
     * 原版 {@code getRainLevel} 没有坐标参数，第三方模组只能读维度级值。
     * 这里在返回值上叠一层本模组的近似值（仅服务端，受 compatVanillaWeatherQueries 控制），
     * 于是 {@code isRaining()}、{@code isRainingAt()} 的客户端版本都会随之变化。
     */
    @Inject(method = "getRainLevel", at = @At("RETURN"), cancellable = true)
    private void localizedrain$compatRainLevel(float delta, CallbackInfoReturnable<Float> callback) {
        float local = CompatWeatherQueries.localRainLevel((Level) (Object) this);
        if (local > callback.getReturnValueF()) {
            callback.setReturnValue(local);
        }
    }

    /** 同 {@link #localizedrain$compatRainLevel(float, CallbackInfoReturnable)}，对应原版雷量。 */
    @Inject(method = "getThunderLevel", at = @At("RETURN"), cancellable = true)
    private void localizedrain$compatThunderLevel(float delta, CallbackInfoReturnable<Float> callback) {
        float local = CompatWeatherQueries.localThunderLevel((Level) (Object) this);
        if (local > callback.getReturnValueF()) {
            callback.setReturnValue(local);
        }
    }

    /**
     * 原版 {@code updateSkyBrightness()} 会用维度级雨雷量压暗全局天空亮度；本模组的天空变暗
     * 走 {@code WeatherManager.skyDarkenAt} 的逐位置路径（实体天光、刷怪光照），所以这里临时屏蔽代理，
     * 避免“某处局部下雨 → 整张地图的 skyDarken 被压低”。
     */
    @Inject(method = "updateSkyBrightness", at = @At("HEAD"))
    private void localizedrain$suppressGlobalSkyBrightness(CallbackInfo callback) {
        CompatWeatherQueries.suppressBegin();
    }

    @Inject(method = "updateSkyBrightness", at = @At("RETURN"))
    private void localizedrain$restoreGlobalSkyBrightness(CallbackInfo callback) {
        CompatWeatherQueries.suppressEnd();
    }

    /**
     * 方块实体 tick 期间压入它的坐标：方块实体里对无坐标天气接口的调用按该位置解析。
     * 只在服务端生效；客户端原版 {@code ClientLevel} 也走这个方法，直接放行。
     */
    @Redirect(method = "tickBlockEntities",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/block/entity/TickingBlockEntity;tick()V"))
    private void localizedrain$blockEntityScope(TickingBlockEntity ticker) {
        if (!((Object) this instanceof ServerLevel server)) {
            ticker.tick();
            return;
        }
        CompatWeatherQueries.runAt(server, ticker.getPos(), ticker::tick);
    }

    /**
     * 方块邻居更新：{@code Level#neighborChanged} 会把更新分发给六个方向的邻居，
     * 第三方的 {@code neighborChanged} / {@code updateShape} / 红石比较器逻辑因此也能拿到所在位置的天气。
     *
     * <p>HEAD/RETURN 必须同时判断 {@code ServerLevel}：{@code ClientLevel} 也继承这个方法，
     * 只给 HEAD 加判断会让 RETURN 侧的 {@code end()} 弹出外层 token。</p>
     */
    @Inject(method = "neighborChanged(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/Block;"
            + "Lnet/minecraft/core/BlockPos;)V", at = @At("HEAD"))
    private void localizedrain$neighborScopeBegin(BlockPos pos, Block sourceBlock, BlockPos sourcePos,
                                                  CallbackInfo callback) {
        if (!((Object) this instanceof ServerLevel level)) {
            return;
        }
        // 只有最外层压栈；深层邻居链/红石链复用最外层的位置，避免吃光帧栈与 token 栈
        CompatWeatherQueries.beginNeighbor(level, pos);
    }

    @Inject(method = "neighborChanged(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/Block;"
            + "Lnet/minecraft/core/BlockPos;)V", at = @At("RETURN"))
    private void localizedrain$neighborScopeEnd(BlockPos pos, Block sourceBlock, BlockPos sourcePos,
                                                CallbackInfo callback) {
        if (!((Object) this instanceof ServerLevel)) {
            return;
        }
        CompatWeatherQueries.endNeighbor();
    }
}
