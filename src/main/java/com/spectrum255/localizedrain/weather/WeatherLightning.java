package com.spectrum255.localizedrain.weather;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.animal.horse.SkeletonHorse;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/** 统一的一次落雷：片模式与移动模式共用，避免两处各写一份陷阱概率。 */
public final class WeatherLightning {
    private WeatherLightning() {}

    /**
     * 在指定位置生成闪电。<p>
     * 与原版 {@code ServerLevel#tickChunk} 一致：按难度概率 {@code effectiveDifficulty * 0.01} 生成骷髅马陷阱，
     * 命中时闪电只做视觉效果（{@code visualOnly}），不造成伤害；落点正下方是避雷针时跳过陷阱。
     */
    public static void strike(ServerLevel level, BlockPos pos, RandomSource random) {
        LightningBolt bolt = EntityType.LIGHTNING_BOLT.create(level);
        if (bolt == null) {
            return;
        }
        bolt.moveTo(Vec3.atBottomCenterOf(pos));
        DifficultyInstance difficulty = level.getCurrentDifficultyAt(pos);
        boolean trap = level.getGameRules().getBoolean(GameRules.RULE_DOMOBSPAWNING)
                && random.nextDouble() < difficulty.getEffectiveDifficulty() * 0.01D
                && !level.getBlockState(pos.below()).is(Blocks.LIGHTNING_ROD);
        if (trap) {
            SkeletonHorse horse = EntityType.SKELETON_HORSE.create(level);
            if (horse != null) {
                horse.setTrap(true);
                horse.setAge(0);
                horse.setPos(pos.getX(), pos.getY(), pos.getZ());
                level.addFreshEntity(horse);
            }
        }
        bolt.setVisualOnly(trap);
        level.addFreshEntity(bolt);
    }
}
