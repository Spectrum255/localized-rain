package com.spectrum255.localizedrain.storm;

import com.spectrum255.localizedrain.api.StormProfile;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BiomeTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.Heightmap;

/** 环境层：温度趋近脚下群系、水分按群系类型增减，并把结果变成阶段速度系数与阈值。 */
final class StormEvolution {
    /** 温湿度是缓慢漂移量：每 4 tick 更新一次，增益/插值按 4 倍补偿，稳态开销降到约 1/4。 */
    private static final int UPDATE_INTERVAL = 4;

    private StormEvolution() {}

    static void tick(ServerLevel level, StormObject storm, StormProfile profile) {
        long offset = Math.floorMod(storm.id.hashCode(), (int) Integer.MAX_VALUE) % (long) UPDATE_INTERVAL;
        if (Math.floorMod(level.getGameTime() + offset, UPDATE_INTERVAL) != 0) {
            return;
        }
        BlockPos pos = BlockPos.containing(storm.x(), sampleY(level, storm), storm.z());
        Holder<Biome> holder = level.getBiome(pos);
        Biome biome = holder.value();

        float targetTemperature = Mth.clamp((biome.getBaseTemperature() - 0.5F) / 1.5F, 0.0F, 1.0F);
        storm.temperature = Mth.lerp(0.01F * UPDATE_INTERVAL, storm.temperature, targetTemperature);

        double gain = profile.environment().moistureGain() * UPDATE_INTERVAL
                * (biome.getPrecipitationAt(pos) == Biome.Precipitation.RAIN ? 2.0D : 0.3D)
                * (isWetBiome(holder) ? 1.5D : 1.0D);
        double decay = profile.environment().moistureDecay() * UPDATE_INTERVAL
                * (biome.getBaseTemperature() > 1.0F ? 2.0D : 1.0D);
        storm.moisture = Mth.clamp((float) (storm.moisture + gain - decay), 0.0F, 1.0F);
    }

    /** 阶段速度系数：手动风暴恒为 1，未开启环境驱动恒为 1。 */
    static double speedFactor(StormObject storm, StormProfile profile) {
        if (storm.manual() || !profile.environment().stageSpeedFromMoisture()) {
            return 1.0D;
        }
        return Mth.lerp(storm.moisture, 0.5F, 1.5F);
    }

    /** 阈值触发：返回 true 表示本 tick 应该强制推进到下一阶段。 */
    static boolean shouldAdvance(StormObject storm, StormProfile profile) {
        if (storm.manual()) {
            return false;
        }
        return profile.stage(storm.stageIndex).advanceWhenMoisture()
                .map(threshold -> storm.moisture >= threshold)
                .orElse(false);
    }

    /** 阶段推进结果：{@code stage < 0} 表示没有可去的阶段（进入淡出）；{@code type} 可选覆盖目标阶段类型。 */
    record Next(int stage, ResourceLocation type) {
        static final Next END = new Next(-1, null);
    }

    /**
     * 选择下一阶段：优先按当前阶段 {@code transitions} 的权重抽取「条件满足」的分支；
     * 没有 transitions、没有满足项或目标就是当前阶段时，回退到线性下一阶段；末阶段返回 {@link Next#END}。
     */
    static Next nextStage(ServerLevel level, StormObject storm, StormProfile profile) {
        StormProfile.Stage current = profile.stage(storm.stageIndex());
        int linear = storm.stageIndex() + 1 < profile.stages().size() ? storm.stageIndex() + 1 : -1;
        List<StormProfile.Transition> transitions = current.transitions();
        if (transitions.isEmpty()) {
            return linear < 0 ? Next.END : new Next(linear, null);
        }
        int total = 0;
        for (StormProfile.Transition transition : transitions) {
            if (eligible(transition, storm)) {
                total += transition.weight();
            }
        }
        if (total <= 0) {
            return linear < 0 ? Next.END : new Next(linear, null);
        }
        int pick = level.getRandom().nextInt(total);
        for (StormProfile.Transition transition : transitions) {
            if (!eligible(transition, storm)) {
                continue;
            }
            pick -= transition.weight();
            if (pick < 0) {
                return new Next(transition.stage(), transition.type().orElse(null));
            }
        }
        return linear < 0 ? Next.END : new Next(linear, null);
    }

    /** 分支可用：目标不是当前阶段，且温湿度落在条件区间内。 */
    private static boolean eligible(StormProfile.Transition transition, StormObject storm) {
        if (transition.stage() == storm.stageIndex()) {
            return false;
        }
        if (transition.minMoisture().isPresent() && storm.moisture < transition.minMoisture().get()) {
            return false;
        }
        if (transition.maxMoisture().isPresent() && storm.moisture > transition.maxMoisture().get()) {
            return false;
        }
        if (transition.minTemperature().isPresent() && storm.temperature < transition.minTemperature().get()) {
            return false;
        }
        return transition.maxTemperature().map(value -> storm.temperature <= value).orElse(true);
    }

    /** 阈值触发：返回 true 表示本 tick 应该开始消亡。 */
    static boolean shouldDie(StormObject storm, StormProfile profile) {
        if (storm.manual()) {
            return false;
        }
        return profile.stage(storm.stageIndex).dieWhenMoisture()
                .map(threshold -> storm.moisture <= threshold)
                .orElse(false);
    }

    private static boolean isWetBiome(Holder<Biome> holder) {
        // 1.21.1 的 BiomeTags 没有 IS_SWAMP，用海洋/丛林近似「潮湿群系」
        return holder.is(BiomeTags.IS_OCEAN) || holder.is(BiomeTags.IS_JUNGLE);
    }

    /**
     * 采样群系的 Y 坐标：风暴竖直区间覆盖地表时按地表采样，否则取区间中点。
     * SURFACE 档案的 minY 是地表 -16，直接取样会落进洞穴群系导致温湿度偏移。
     */
    private static int sampleY(ServerLevel level, StormObject storm) {
        int surface = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                Mth.floor(storm.x()), Mth.floor(storm.z()));
        int min = (int) Math.ceil(storm.minY());
        int max = (int) Math.floor(storm.maxY()) - 1;
        if (surface >= min && surface <= max) {
            return surface;
        }
        return Mth.clamp((min + max) / 2, level.getMinBuildHeight(), level.getMaxBuildHeight() - 1);
    }
}
