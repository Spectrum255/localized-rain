package com.spectrum255.localizedrain.mixin;

import com.spectrum255.localizedrain.weather.CompatWeatherQueries;
import com.spectrum255.localizedrain.weather.WeatherManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
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

    // ------------------------------------------------------------------
    // 位置上下文：原版无坐标的 getRainLevel/getThunderLevel/isRaining 无法知道调用者位置，
    // 只能在「已知坐标的帧」里压栈，让 CompatWeatherQueries 取到该位置的天气。
    // 帧之间不成对的风险由 WeatherManager.tick 开头的 clear() 兜底。
    // ------------------------------------------------------------------

    @Inject(method = "tickNonPassenger", at = @At("HEAD"))
    private void localizedrain$entityScopeBegin(Entity entity, CallbackInfo callback) {
        // 帧里存实体引用，帧内的查询按实体当前坐标解析（实体可能在 tick 内移动）
        CompatWeatherQueries.beginEntity((ServerLevel) (Object) this, entity);
    }

    @Inject(method = "tickNonPassenger", at = @At("RETURN"))
    private void localizedrain$entityScopeEnd(Entity entity, CallbackInfo callback) {
        CompatWeatherQueries.end();
    }

    /**
     * 区块 tick：用区块中心（地表高度）压帧，让原版 {@code tickChunk} 里的
     * {@code isRaining()/isThundering()} 门控按该区块所在位置解析，而不是维度最大值。
     *
     * <p>这是区块级近似：16×16 区块跨越风暴边界时整块按中心判定，只影响原版雷击抽签与
     * {@code flag}；真正的落雷点仍由 {@code isRainingAt} 逐位置判定，不会打偏。</p>
     */
    @Inject(method = "tickChunk", at = @At("HEAD"))
    private void localizedrain$chunkScopeBegin(LevelChunk chunk, int randomTickSpeed, CallbackInfo callback) {
        ServerLevel level = (ServerLevel) (Object) this;
        if (CompatWeatherQueries.active()) {
            ChunkPos chunkPos = chunk.getPos();
            // 用 int 重载直接压栈，避免每个区块每 tick 分配一个 BlockPos
            CompatWeatherQueries.begin(level, chunkPos.getMiddleBlockX(),
                    chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, 8, 8), chunkPos.getMiddleBlockZ());
        } else {
            // 即使开关关闭也登记一个 -1 token，保证 RETURN 侧的 end() 配对不会错位
            CompatWeatherQueries.begin(level, (BlockPos) null);
        }
    }

    @Inject(method = "tickChunk", at = @At("RETURN"))
    private void localizedrain$chunkScopeEnd(LevelChunk chunk, int randomTickSpeed, CallbackInfo callback) {
        CompatWeatherQueries.end();
    }

    @Inject(method = "tickPrecipitation", at = @At("HEAD"))
    private void localizedrain$precipitationScopeBegin(BlockPos pos, CallbackInfo callback) {
        CompatWeatherQueries.begin((ServerLevel) (Object) this, pos);
    }

    @Inject(method = "tickPrecipitation", at = @At("RETURN"))
    private void localizedrain$precipitationScopeEnd(BlockPos pos, CallbackInfo callback) {
        CompatWeatherQueries.end();
    }

    /** 方块计划刻：原版通过 {@code blockTicks.tick(..., this::tickBlock)} 回调。 */
    @Inject(method = "tickBlock", at = @At("HEAD"))
    private void localizedrain$blockTickScopeBegin(BlockPos pos, Block block, CallbackInfo callback) {
        CompatWeatherQueries.begin((ServerLevel) (Object) this, pos);
    }

    @Inject(method = "tickBlock", at = @At("RETURN"))
    private void localizedrain$blockTickScopeEnd(BlockPos pos, Block block, CallbackInfo callback) {
        CompatWeatherQueries.end();
    }

    /** 流体计划刻。 */
    @Inject(method = "tickFluid", at = @At("HEAD"))
    private void localizedrain$fluidTickScopeBegin(BlockPos pos, Fluid fluid, CallbackInfo callback) {
        CompatWeatherQueries.begin((ServerLevel) (Object) this, pos);
    }

    @Inject(method = "tickFluid", at = @At("RETURN"))
    private void localizedrain$fluidTickScopeEnd(BlockPos pos, Fluid fluid, CallbackInfo callback) {
        CompatWeatherQueries.end();
    }

    /** 方块随机刻：包住 tickChunk 里对 BlockState#randomTick 的调用。 */
    @Redirect(method = "tickChunk",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/block/state/BlockState;randomTick(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;Lnet/minecraft/util/RandomSource;)V"))
    private void localizedrain$randomTickScopeBlock(BlockState state, ServerLevel level, BlockPos pos,
                                                    RandomSource random) {
        if (!CompatWeatherQueries.active()) {
            state.randomTick(level, pos, random);
            return;
        }
        CompatWeatherQueries.runAt(level, pos, () -> state.randomTick(level, pos, random));
    }

    /** 流体随机刻。真正的描述符是 {@code FluidState#randomTick(Level, BlockPos, RandomSource)}，
     *  参数类型是 {@link Level} 而不是 {@code ServerLevel}（1.21.1 字节码里 aload 的声明类型为 Level）。 */
    @Redirect(method = "tickChunk",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/material/FluidState;randomTick(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/util/RandomSource;)V"))
    private void localizedrain$randomTickScopeFluid(FluidState state, Level level, BlockPos pos,
                                                    RandomSource random) {
        if (!CompatWeatherQueries.active()) {
            state.randomTick(level, pos, random);
            return;
        }
        CompatWeatherQueries.runAt(level, pos, () -> state.randomTick(level, pos, random));
    }
}
