package com.spectrum255.localizedrain.network;

/**
 * 客户端保存的「目标」天气（服务端当前所在群系片的布尔状态），供 HUD 类兼容读取。
 *
 * <p>只保存两个布尔值，不引用任何客户端专用类，因此在两端都能安全加载。</p>
 */
public final class ClientWeatherTargets {
    private static volatile boolean raining;
    private static volatile boolean thundering;

    private ClientWeatherTargets() {}

    public static void set(boolean raining, boolean thundering) {
        ClientWeatherTargets.raining = raining;
        ClientWeatherTargets.thundering = thundering;
    }

    /** 断开连接时清空，避免下次进入世界的第一帧显示上一个存档的天气。 */
    public static void clear() {
        set(false, false);
    }

    public static boolean isRaining() {
        return raining;
    }

    public static boolean isThundering() {
        return thundering;
    }
}
