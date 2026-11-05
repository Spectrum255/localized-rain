package com.spectrum255.localizedrain.client;

import com.spectrum255.localizedrain.client.compat.XaeroWeatherCompat;
import com.spectrum255.localizedrain.network.ClientWeatherTargets;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.neoforged.fml.ModList;

/**
 * 客户端侧的可选兼容钩子。Xaero 的桥接类不引用任何 Xaero 的类（全部走反射），
 * 没装 Xaero 时这里只会做一次 ModList 检查，不会加载任何 Xaero 代码。
 */
public final class ClientWeatherHooks {
    private static final String XAERO_MOD_ID = "xaerominimap";

    /** 已经确认 Xaero 不可用（没装或版本不匹配），不再重复尝试。 */
    private static boolean unavailable;
    private static int ticks;
    /** 上一次看到的维度；跨维度时清空残留的目标天气（下界/末地没有天光天气）。 */
    private static ResourceKey<Level> lastDimension;

    private ClientWeatherHooks() {}

    public static void onClientTick() {
        Minecraft minecraft = Minecraft.getInstance();
        ResourceKey<Level> dimension = minecraft.level != null ? minecraft.level.dimension() : null;
        if (lastDimension != null && !lastDimension.equals(dimension)) {
            ClientWeatherTargets.clear();
        }
        lastDimension = dimension;

        // 每 20 tick（约 1 秒）检查一次：Xaero 重开世界时可能重建内置信息行
        if (ticks++ % 20 != 0) {
            return;
        }
        tryInstallXaero();
    }

    /** 登出时由客户端监听调用，避免把上一局的目标天气带到下一个世界。 */
    public static void onLoggingOut() {
        ClientWeatherTargets.clear();
        lastDimension = null;
    }

    public static void tryInstallXaero() {
        if (unavailable) {
            return;
        }
        if (!ModList.get().isLoaded(XAERO_MOD_ID)) {
            unavailable = true;
            return;
        }
        try {
            XaeroWeatherCompat.install();
        } catch (Throwable throwable) {
            // 版本不匹配之类：禁用兼容并记录一次，不要每 tick 刷日志
            unavailable = true;
            com.spectrum255.localizedrain.Localizedrain.LOGGER.warn(
                    "[localizedrain] Xaero's Minimap weather display integration disabled: {}",
                    throwable.toString());
        }
    }
}
