package com.spectrum255.localizedrain.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;

/**
 * 室内外能见度估算：每客户端 tick 用最多 6 次 {@code canSeeSky} 判断镜头是否暴露在天空下，
 * 得出一个 0–1 的 exposure，再把配置里的「室内保留比例」换算成视觉系数与声音系数。
 *
 * <p>判定顺序（短路，平均成本远低于上限）：头顶 1.0；稍高一点能见天空、或四个水平方向的
 * 相邻列能见天空 → 0.5（屋内靠窗/门口）；否则 0。不做体积雾或逐方块遮挡——那需要
 * 屏幕空间或光照探针，成本与兼容性都不可接受。</p>
 *
 * <p>同一 tick 内多处查询（雾、粒子、声音、天空覆盖）共用同一份结果。</p>
 */
public final class ClientWeatherExposure {
    private static float exposure = 1.0F;
    private static float visualFactor = 1.0F;
    private static float soundFactor = 1.0F;
    private static float pitch = 1.0F;

    private ClientWeatherExposure() {}

    /** 每客户端 tick 调用一次（在渲染/发声之前）。 */
    public static void update(Minecraft minecraft) {
        LocalPlayer player = minecraft.player;
        if (minecraft.level == null || player == null) {
            exposure = 1.0F;
            visualFactor = 1.0F;
            soundFactor = 1.0F;
            pitch = 1.0F;
            return;
        }
        BlockPos eye = BlockPos.containing(player.getEyePosition());
        exposure = sample(minecraft, eye);
        visualFactor = ClientWeatherConfig.indoorVisualFactor() * (1.0F - exposure) + exposure;
        soundFactor = ClientWeatherConfig.indoorSoundFactor() * (1.0F - exposure) + exposure;
        // 闷在室内时把环境音调低一档音高：近似原版 WEATHER_RAIN_ABOVE 的听感
        pitch = exposure < 0.5F ? 0.6F : 1.0F;
    }

    private static float sample(Minecraft minecraft, BlockPos eye) {
        var level = minecraft.level;
        if (level.canSeeSky(eye)) {
            return 1.0F;
        }
        if (level.canSeeSky(eye.above())) {
            return 0.5F;
        }
        int[][] probes = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] offset : probes) {
            if (level.canSeeSky(eye.offset(offset[0], 0, offset[1]))) {
                return 0.5F;
            }
        }
        return 0.0F;
    }

    /** 当前帧的 exposure（1 = 露天，0.5 = 靠窗/屋檐，0 = 室内）。 */
    public static float exposure() {
        return exposure;
    }

    /** 视觉系数：作用于雾、粒子、天空覆盖与 {@code currentIntensity()}。 */
    public static float visualFactor() {
        return visualFactor;
    }

    /** 声音系数：作用于天气环境音音量。 */
    public static float soundFactor() {
        return soundFactor;
    }

    /** 环境音音高：室内 0.6，露天 1.0。 */
    public static float pitch() {
        return pitch;
    }
}
