package com.spectrum255.localizedrain.mixin;

import com.spectrum255.localizedrain.weather.WeatherManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Entity.class)
public abstract class EntitySkyLightMixin {
    /**
     * 原版这个值走的是 Level#getMaxLocalRawBrightness → 维度级 getSkyDarken()。
     * 我们改用「实体所在区域」的变暗值，否则局部降雨会让晴天群系的亮度也掉到亡灵不燃烧的程度。
     */
    @Inject(method = "getLightLevelDependentMagicValue", at = @At("HEAD"), cancellable = true)
    private void localizedrain$localSkyBrightness(CallbackInfoReturnable<Float> callback) {
        Entity self = (Entity) (Object) this;
        Level level = self.level();
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        boolean chunkLoaded = serverLevel.getChunkSource().hasChunk(
                SectionPos.blockToSectionCoord(self.getBlockX()), SectionPos.blockToSectionCoord(self.getBlockZ()));
        if (!chunkLoaded) {
            callback.setReturnValue(0.0F);
            return;
        }
        BlockPos pos = BlockPos.containing(self.getX(), self.getEyeY(), self.getZ());
        int brightness = ((LevelReader) level).getMaxLocalRawBrightness(pos, WeatherManager.skyDarkenAt(serverLevel, pos));
        float f = (float) brightness / 15.0F;
        float f1 = f / (4.0F - 3.0F * f);
        callback.setReturnValue(Mth.lerp(level.dimensionType().ambientLight(), f1, 1.0F));
    }
}
