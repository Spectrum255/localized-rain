package com.spectrum255.localizedrain.client;

import com.spectrum255.localizedrain.network.StormSyncPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;

/** 客户端渐变：玩家距离 + 高度 + 群系隔离 → 目标雨雷量 → 0.01/tick 逼近 → 回写原版通道。 */
public final class ClientStormWeather {
    private static final float LEVEL_STEP = 0.01F;
    private static float currentRain;
    private static float currentThunder;

    private ClientStormWeather() {}

    public static void tick(Minecraft minecraft) {
        ClientLevel level = minecraft.level;
        LocalPlayer player = minecraft.player;
        if (level == null || player == null) {
            return;
        }
        if (!ClientStormCache.hasEverReceived() || !ClientStormCache.active()) {
            // 从未收到快照，或服务端已不在移动模式：交还逐玩家雨量包驱动，保持原版/片模式渲染
            currentRain = 0.0F;
            currentThunder = 0.0F;
            return;
        }
        double falloffRatio = ClientStormCache.falloffRatio();
        boolean isolated = level.getBiome(player.blockPosition()).is(
                com.spectrum255.localizedrain.api.StormTags.STORM_ISOLATED);
        String isolatedMode = ClientStormCache.isolatedMode();
        if (isolated && "patch".equals(isolatedMode)) {
            // 隔离群系 + patch 模式：由服务端的片天气逐玩家包驱动，客户端不要覆盖
            return;
        }
        // clear：隔离群系永远晴；manual：只显示手动风暴
        boolean manualOnly = isolated && "manual".equals(isolatedMode);
        boolean blockAll = isolated && !manualOnly;
        float targetRain = 0.0F;
        float targetThunder = 0.0F;
        if (!blockAll) {
            for (ClientStormCache.Storm storm : ClientStormCache.storms()) {
                if (manualOnly && !storm.manual()) {
                    continue;
                }
                double distance = Math.hypot(player.getX() - storm.x(), player.getZ() - storm.z());
                double falloff = Math.max(1.0D, storm.radius() * falloffRatio);
                double horizontal = Mth.clamp((storm.radius() + falloff - distance) / falloff, 0.0D, 1.0D);
                double vertical = player.getY() >= storm.minY() && player.getY() <= storm.maxY() ? 1.0D : 0.0D;
                if (horizontal <= 0.0D || vertical <= 0.0D) {
                    continue;
                }
                // 淡出由客户端按剩余 tick 平滑，服务端只发未乘 fade 的基值
                float fade = storm.dying()
                        ? Math.max(0.0F, Math.min(1.0F,
                                storm.dyingTicksLeft() / (float) StormSyncPayload.FADE_TICKS))
                        : 1.0F;
                // 类型能力位随快照下发：非降水类型不渲染雨，禁止雷暴的类型不渲染雷
                float typeRain = storm.hasPrecipitation() ? storm.rain() : 0.0F;
                float typeThunder = storm.allowsThunder() ? storm.thunder() : 0.0F;
                targetRain = Math.max(targetRain, (float) (typeRain * horizontal * vertical * fade));
                targetThunder = Math.max(targetThunder, (float) (typeThunder * horizontal * vertical * fade));
            }
        }
        currentRain = approach(currentRain, targetRain);
        currentThunder = approach(currentThunder, targetThunder);
        level.setRainLevel(currentRain);
        level.setThunderLevel(currentThunder);
        level.getLevelData().setRaining(currentRain > 0.05F);
    }

    private static float approach(float current, float target) {
        if (current < target) {
            return Math.min(target, current + LEVEL_STEP);
        }
        return Math.max(target, current - LEVEL_STEP);
    }
}
