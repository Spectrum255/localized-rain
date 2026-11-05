package com.spectrum255.localizedrain.mixin;

import com.spectrum255.localizedrain.weather.WeatherManager;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.dimension.DimensionType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Monster.class)
public abstract class MonsterSpawnMixin {
    /**
     * 原版用维度级 isThundering() 决定刷怪判定是否额外减 10 点光照，
     * 于是任何一处雷暴都会让全图白天刷怪。这里换成「该位置所在区域」的雷暴状态。
     */
    @Inject(method = "isDarkEnoughToSpawn", at = @At("HEAD"), cancellable = true)
    private static void localizedrain$localSpawnDarkness(ServerLevelAccessor level, BlockPos pos, RandomSource random,
                                                         CallbackInfoReturnable<Boolean> callback) {
        if (level.getBrightness(LightLayer.SKY, pos) > random.nextInt(32)) {
            callback.setReturnValue(false);
            return;
        }
        DimensionType dimensionType = level.dimensionType();
        int blockLightLimit = dimensionType.monsterSpawnBlockLightLimit();
        if (blockLightLimit < 15 && level.getBrightness(LightLayer.BLOCK, pos) > blockLightLimit) {
            callback.setReturnValue(false);
            return;
        }
        boolean thundering = WeatherManager.isThunderingRegionAt(level.getLevel(), pos);
        int brightness = thundering ? level.getMaxLocalRawBrightness(pos, 10) : level.getMaxLocalRawBrightness(pos);
        callback.setReturnValue(brightness <= dimensionType.monsterSpawnLightTest().sample(random));
    }
}
